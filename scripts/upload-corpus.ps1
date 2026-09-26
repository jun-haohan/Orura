<#
.SYNOPSIS
    Upload every corpus file under datasets\computer_docs_200 and wait for parsing and
    vectorization to finish.

.DESCRIPTION
    The script uses the existing knowledge-ingestion HTTP API. It does not
    access MongoDB, Milvus, or MinIO directly.

    Documents that have already been parsed, vectorized, and passed the
    Chunk/Vector consistency check are skipped so interrupted runs can resume.

    A file is counted as successful only when:
      1. The upload and parsing request succeeds.
      2. embeddingStatus becomes SUCCESS.
      3. The existing embedding consistency endpoint returns true.

.EXAMPLE
    .\scripts\upload-corpus.ps1

.EXAMPLE
    .\scripts\upload-corpus.ps1 -FilesDirectory 'D:\corpus' `
        -BaseUrl 'http://localhost:18400' -UploadTimeoutMinutes 15 `
        -EmbeddingTimeoutMinutes 60
#>

[CmdletBinding()]
param(
    [Parameter()]
    [string]$FilesDirectory = '',

    [Parameter()]
    [string]$BaseUrl = 'http://localhost:18400',

    [Parameter()]
    [ValidateRange(1, 300)]
    [int]$PollIntervalSeconds = 2,

    [Parameter()]
    [ValidateRange(1, 1440)]
    [int]$UploadTimeoutMinutes = 15,

    [Parameter()]
    [ValidateRange(1, 1440)]
    [int]$EmbeddingTimeoutMinutes = 30,

    [Parameter()]
    [ValidateRange(1, 100)]
    [int]$MaxPollingErrors = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
    # Output encoding is cosmetic; continue if the host does not allow it.
}

Add-Type -AssemblyName System.Net.Http

$BaseUrl = $BaseUrl.TrimEnd('/')
$supportedExtensions = @('.txt', '.md', '.markdown', '.pdf', '.docx')

if ([string]::IsNullOrWhiteSpace($FilesDirectory)) {
    $repositoryRoot = Split-Path -Parent $PSScriptRoot
    $FilesDirectory = Join-Path (Join-Path $repositoryRoot 'datasets') 'computer_docs_200'
}

function Read-ResponseBody {
    param(
        [Parameter(Mandatory)]
        [System.Net.Http.HttpResponseMessage]$Response
    )

    return $Response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
}

function Get-ErrorDescription {
    param(
        [AllowEmptyString()]
        [string]$Body,

        [string]$Fallback = 'Unknown error'
    )

    if ([string]::IsNullOrWhiteSpace($Body)) {
        return $Fallback
    }

    try {
        $payload = $Body | ConvertFrom-Json
        foreach ($propertyName in @('message', 'detail', 'error', 'errorMessage')) {
            $property = $payload.PSObject.Properties[$propertyName]
            if ($null -ne $property -and -not [string]::IsNullOrWhiteSpace([string]$property.Value)) {
                return [string]$property.Value
            }
        }
    } catch {
        # The server may return plain text rather than JSON.
    }

    return $Body.Trim()
}

function Invoke-GetRequest {
    param(
        [Parameter(Mandatory)]
        [System.Net.Http.HttpClient]$Client,

        [Parameter(Mandatory)]
        [string]$Url,

        [Parameter()]
        [ValidateRange(1, 3600)]
        [int]$TimeoutSeconds = 30
    )

    $response = $null
    $cancellation = [System.Threading.CancellationTokenSource]::new()
    try {
        $cancellation.CancelAfter([TimeSpan]::FromSeconds($TimeoutSeconds))
        try {
            $response = $Client.GetAsync($Url, $cancellation.Token).GetAwaiter().GetResult()
        } catch [System.OperationCanceledException] {
            throw [System.TimeoutException]::new(
                "GET request timed out after $TimeoutSeconds second(s): $Url"
            )
        }
        $body = Read-ResponseBody -Response $response
        return [pscustomobject]@{
            IsSuccess  = $response.IsSuccessStatusCode
            StatusCode = [int]$response.StatusCode
            Body       = $body
        }
    } finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        $cancellation.Dispose()
    }
}

function Get-CompletedDocumentLookup {
    param(
        [Parameter(Mandatory)]
        [System.Net.Http.HttpClient]$Client
    )

    $response = Invoke-GetRequest -Client $Client -Url "$BaseUrl/documents"
    if (-not $response.IsSuccess) {
        throw "Unable to load existing documents: HTTP $($response.StatusCode): $(Get-ErrorDescription -Body $response.Body)"
    }

    $lookup = @{}
    $documents = @($response.Body | ConvertFrom-Json)

    foreach ($document in $documents) {
        $fileName = [string]$document.fileName
        $chunkCount = if ($null -eq $document.chunkCount) { 0 } else { [int]$document.chunkCount }

        if ([string]::IsNullOrWhiteSpace($fileName) -or
            [string]$document.status -ne 'SUCCESS' -or
            [string]$document.embeddingStatus -ne 'SUCCESS' -or
            $chunkCount -le 0) {
            continue
        }

        if (-not $lookup.ContainsKey($fileName)) {
            $lookup[$fileName] = [System.Collections.Generic.List[object]]::new()
        }
        $lookup[$fileName].Add($document)
    }

    return $lookup
}

function Find-CompletedDocument {
    param(
        [Parameter(Mandatory)]
        [System.Net.Http.HttpClient]$Client,

        [Parameter(Mandatory)]
        [hashtable]$Lookup,

        [Parameter(Mandatory)]
        [string]$FileName
    )

    if (-not $Lookup.ContainsKey($FileName)) {
        return $null
    }

    foreach ($document in $Lookup[$FileName]) {
        try {
            $checkResponse = Invoke-GetRequest -Client $Client `
                -Url "$BaseUrl/api/documents/$([string]$document.id)/embedding/check"

            if ($checkResponse.IsSuccess -and ($checkResponse.Body | ConvertFrom-Json) -eq $true) {
                return $document
            }
        } catch {
            Write-Warning "Could not verify existing document '$FileName' ($([string]$document.id)): $($_.Exception.Message)"
        }
    }

    return $null
}

function Format-ElapsedTime {
    param(
        [Parameter(Mandatory)]
        [TimeSpan]$Elapsed
    )

    $totalHours = [Math]::Floor($Elapsed.TotalHours)
    return '{0:00}:{1:00}:{2:00}' -f $totalHours, $Elapsed.Minutes, $Elapsed.Seconds
}

function Send-CorpusFile {
    param(
        [Parameter(Mandatory)]
        [System.Net.Http.HttpClient]$Client,

        [Parameter(Mandatory)]
        [System.IO.FileInfo]$File,

        [Parameter(Mandatory)]
        [string]$UploadUrl,

        [Parameter(Mandatory)]
        [string]$DisplayName,

        [Parameter(Mandatory)]
        [int]$TimeoutMinutes
    )

    $stream = $null
    $fileContent = $null
    $multipart = $null
    $response = $null
    $cancellation = [System.Threading.CancellationTokenSource]::new()

    try {
        $stream = [System.IO.File]::OpenRead($File.FullName)
        $fileContent = [System.Net.Http.StreamContent]::new($stream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new(
            'application/octet-stream'
        )

        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        $multipart.Add($fileContent, 'file', $File.Name)

        $requestStartedAt = [DateTime]::UtcNow
        $deadline = $requestStartedAt.AddMinutes($TimeoutMinutes)
        $requestTask = $Client.PostAsync($UploadUrl, $multipart, $cancellation.Token)

        while (-not $requestTask.IsCompleted) {
            $elapsedSeconds = [Math]::Floor(
                ([DateTime]::UtcNow - $requestStartedAt).TotalSeconds
            )
            Write-Progress -Id 2 -ParentId 1 `
                -Activity "Current file: $DisplayName" `
                -Status "Uploading and parsing... elapsed ${elapsedSeconds}s | timeout=$($TimeoutMinutes)m"

            if ([DateTime]::UtcNow -ge $deadline) {
                $cancellation.Cancel()
                return [pscustomobject]@{
                    IsSuccess  = $false
                    StatusCode = 0
                    Body       = ''
                    TimedOut   = $true
                    Error      = "Upload and parsing timed out after $TimeoutMinutes minute(s); skipped this file"
                }
            }

            Start-Sleep -Milliseconds 500
        }

        try {
            $response = $requestTask.GetAwaiter().GetResult()
        } catch [System.OperationCanceledException] {
            return [pscustomobject]@{
                IsSuccess  = $false
                StatusCode = 0
                Body       = ''
                TimedOut   = $true
                Error      = "Upload and parsing timed out after $TimeoutMinutes minute(s); skipped this file"
            }
        }
        $body = Read-ResponseBody -Response $response

        return [pscustomobject]@{
            IsSuccess  = $response.IsSuccessStatusCode
            StatusCode = [int]$response.StatusCode
            Body       = $body
            TimedOut   = $false
            Error      = ''
        }
    } finally {
        if ($null -ne $response) {
            $response.Dispose()
        }
        if ($null -ne $multipart) {
            $multipart.Dispose()
        } elseif ($null -ne $fileContent) {
            $fileContent.Dispose()
        } elseif ($null -ne $stream) {
            $stream.Dispose()
        }
        $cancellation.Dispose()
    }
}

function Wait-ForIngestion {
    param(
        [Parameter(Mandatory)]
        [System.Net.Http.HttpClient]$Client,

        [Parameter(Mandatory)]
        [string]$DocumentId,

        [Parameter(Mandatory)]
        [int]$TimeoutMinutes,

        [Parameter(Mandatory)]
        [int]$IntervalSeconds,

        [Parameter(Mandatory)]
        [int]$AllowedPollingErrors,

        [Parameter(Mandatory)]
        [string]$DisplayName
    )

    $startedAt = [DateTime]::UtcNow
    $deadline = [DateTime]::UtcNow.AddMinutes($TimeoutMinutes)
    $pollingErrors = 0
    $lastPollingError = $null
    $latestChunkCount = 0
    $waitingForConsistency = $false

    try {
        while ([DateTime]::UtcNow -lt $deadline) {
            try {
            $documentResponse = Invoke-GetRequest -Client $Client `
                -Url "$BaseUrl/documents/$DocumentId"

            if (-not $documentResponse.IsSuccess) {
                throw "HTTP $($documentResponse.StatusCode): $(Get-ErrorDescription -Body $documentResponse.Body)"
            }

            $document = $documentResponse.Body | ConvertFrom-Json
            $pollingErrors = 0

            if ($null -ne $document.chunkCount -and [int]$document.chunkCount -gt 0) {
                $latestChunkCount = [int]$document.chunkCount
            }

            $parseStatus = [string]$document.status
            $embeddingStatus = [string]$document.embeddingStatus
            $elapsedSeconds = [Math]::Floor(
                ([DateTime]::UtcNow - $startedAt).TotalSeconds
            )

            Write-Progress -Id 2 -ParentId 1 `
                -Activity "Current file: $DisplayName" `
                -Status "Parse=$parseStatus | Embedding=$embeddingStatus | Chunks=$latestChunkCount | elapsed=${elapsedSeconds}s"

            if ($parseStatus -eq 'FAILED') {
                return [pscustomobject]@{
                    Success    = $false
                    ChunkCount = $latestChunkCount
                    Reason     = "Parsing failed: $([string]$document.errorMessage)"
                }
            }

            if ($embeddingStatus -eq 'FAILED') {
                return [pscustomobject]@{
                    Success    = $false
                    ChunkCount = $latestChunkCount
                    Reason     = "Vectorization failed: $([string]$document.embeddingErrorMessage)"
                }
            }

            if ($parseStatus -eq 'SUCCESS' -and $embeddingStatus -eq 'SUCCESS') {
                $checkResponse = Invoke-GetRequest -Client $Client `
                    -Url "$BaseUrl/api/documents/$DocumentId/embedding/check"

                if (-not $checkResponse.IsSuccess) {
                    return [pscustomobject]@{
                        Success    = $false
                        ChunkCount = $latestChunkCount
                        Reason     = "Consistency check failed with HTTP $($checkResponse.StatusCode): $(Get-ErrorDescription -Body $checkResponse.Body)"
                    }
                }

                $isConsistent = $checkResponse.Body | ConvertFrom-Json
                if ($isConsistent -ne $true) {
                    $waitingForConsistency = $true
                    $elapsedSeconds = [Math]::Floor(
                        ([DateTime]::UtcNow - $startedAt).TotalSeconds
                    )
                    Write-Progress -Id 2 -ParentId 1 `
                        -Activity "Current file: $DisplayName" `
                        -Status "Embedding=SUCCESS | waiting for Chunk/Vector consistency | Chunks=$latestChunkCount | elapsed=${elapsedSeconds}s"
                } else {
                    return [pscustomobject]@{
                        Success    = $true
                        ChunkCount = $latestChunkCount
                        Reason     = ''
                    }
                }
            }
            } catch {
                $pollingErrors++
                $lastPollingError = $_.Exception.Message
                if ($pollingErrors -ge $AllowedPollingErrors) {
                    return [pscustomobject]@{
                        Success    = $false
                        ChunkCount = $latestChunkCount
                        Reason     = "Status polling failed $pollingErrors consecutive times: $lastPollingError"
                    }
                }
            }

            Start-Sleep -Seconds $IntervalSeconds
        }

        $timeoutReason = if ($waitingForConsistency) {
            "Timed out after $TimeoutMinutes minute(s) waiting for Chunk/Vector consistency"
        } else {
            "Timed out after $TimeoutMinutes minute(s) waiting for vectorization"
        }
        if (-not [string]::IsNullOrWhiteSpace($lastPollingError)) {
            $timeoutReason += "; last polling error: $lastPollingError"
        }

        return [pscustomobject]@{
            Success    = $false
            ChunkCount = $latestChunkCount
            Reason     = $timeoutReason
        }
    } finally {
        Write-Progress -Id 2 -ParentId 1 -Activity "Current file: $DisplayName" -Completed
    }
}

function Show-OverallProgress {
    param(
        [Parameter(Mandatory)]
        [int]$Completed,

        [Parameter(Mandatory)]
        [int]$Total,

        [Parameter(Mandatory)]
        [int]$Parsed,

        [Parameter(Mandatory)]
        [int]$Skipped,

        [Parameter(Mandatory)]
        [int]$Failed,

        [Parameter(Mandatory)]
        [long]$GeneratedChunks
    )

    $percentage = if ($Total -eq 0) {
        100
    } else {
        [Math]::Min(100, [Math]::Floor(($Completed * 100.0) / $Total))
    }

    $status = "$Completed/$Total ($percentage%) | parsed=$Parsed | skipped=$Skipped | failed=$Failed | chunks=$GeneratedChunks"
    Write-Progress -Id 1 -Activity 'Corpus ingestion progress' `
        -Status $status -PercentComplete $percentage
    Write-Host "  Progress: $status" -ForegroundColor DarkGray
}

if (-not (Test-Path -LiteralPath $FilesDirectory -PathType Container)) {
    Write-Error "Corpus directory does not exist: $FilesDirectory"
    exit 2
}

$files = @(Get-ChildItem -LiteralPath $FilesDirectory -File -Recurse | Sort-Object FullName)
$runStopwatch = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "Corpus directory : $FilesDirectory"
Write-Host "Service URL      : $BaseUrl"
Write-Host "Files discovered : $($files.Count)"
Write-Host "Upload timeout   : $UploadTimeoutMinutes minute(s) per file"
Write-Host "Embedding timeout: $EmbeddingTimeoutMinutes minute(s) per file"

if ($files.Count -eq 0) {
    $runStopwatch.Stop()
    Write-Host ''
    Write-Host '===== Ingestion summary ====='
    Write-Host "Total elapsed time       : $(Format-ElapsedTime -Elapsed $runStopwatch.Elapsed)"
    Write-Host 'Documents parsed         : 0'
    Write-Host 'Already indexed skipped  : 0'
    Write-Host 'Completed documents total: 0'
    Write-Host 'Failed files             : 0'
    Write-Host 'Total chunks             : 0'
    exit 0
}

$handler = [System.Net.Http.HttpClientHandler]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan

$results = [System.Collections.Generic.List[object]]::new()
$successfulSoFar = 0
$skippedSoFar = 0
$failedSoFar = 0
$timedOutSoFar = 0
$completedChunksSoFar = 0L

try {
    $pingResponse = Invoke-GetRequest -Client $client -Url "$BaseUrl/documents/ping"
    if (-not $pingResponse.IsSuccess -or $pingResponse.Body.Trim() -ne 'ok') {
        throw "Knowledge-ingestion service health check failed: HTTP $($pingResponse.StatusCode), body=$($pingResponse.Body)"
    }

    Write-Host 'Loading successfully indexed documents for resume support...'
    $completedDocumentLookup = Get-CompletedDocumentLookup -Client $client
    $completedDocumentCandidates = 0
    foreach ($candidateList in $completedDocumentLookup.Values) {
        $completedDocumentCandidates += $candidateList.Count
    }
    Write-Host "Indexed document candidates: $completedDocumentCandidates"

    for ($index = 0; $index -lt $files.Count; $index++) {
        $file = $files[$index]
        $position = $index + 1
        $relativePath = $file.FullName.Substring($FilesDirectory.TrimEnd('\', '/').Length).TrimStart('\', '/')
        $startingPercentage = [Math]::Floor(($index * 100.0) / $files.Count)

        Write-Progress -Id 1 -Activity 'Corpus ingestion progress' `
            -Status "$position/$($files.Count) | current=$relativePath" `
            -PercentComplete $startingPercentage

        Write-Host ''
        Write-Host "[$position/$($files.Count)] $relativePath"

        $extension = $file.Extension.ToLowerInvariant()
        if ($supportedExtensions -notcontains $extension) {
            $reason = "Unsupported file type: $extension"
            Write-Host "  FAILED: $reason" -ForegroundColor Red
            $results.Add([pscustomobject]@{
                File        = $relativePath
                DocumentId  = ''
                Success     = $false
                Outcome     = 'FAILED'
                TimedOut    = $false
                ChunkCount  = 0
                Reason      = $reason
            })
            $failedSoFar++
            Show-OverallProgress -Completed $position -Total $files.Count `
                -Parsed $successfulSoFar -Skipped $skippedSoFar -Failed $failedSoFar `
                -GeneratedChunks $completedChunksSoFar
            continue
        }

        $completedDocument = Find-CompletedDocument -Client $client `
            -Lookup $completedDocumentLookup -FileName $file.Name

        if ($null -ne $completedDocument) {
            $existingChunkCount = [int]$completedDocument.chunkCount
            $existingDocumentId = [string]$completedDocument.id
            Write-Host "  SKIPPED: already indexed | chunks=$existingChunkCount | documentId=$existingDocumentId" `
                -ForegroundColor Yellow

            $results.Add([pscustomobject]@{
                File        = $relativePath
                DocumentId  = $existingDocumentId
                Success     = $true
                Outcome     = 'SKIPPED'
                TimedOut    = $false
                ChunkCount  = $existingChunkCount
                Reason      = 'Already parsed, vectorized, and consistency-checked'
            })
            $skippedSoFar++
            $completedChunksSoFar += $existingChunkCount
            Show-OverallProgress -Completed $position -Total $files.Count `
                -Parsed $successfulSoFar -Skipped $skippedSoFar -Failed $failedSoFar `
                -GeneratedChunks $completedChunksSoFar
            continue
        }

        $currentDocumentId = ''
        $currentChunkCount = 0
        $currentTimedOut = $false

        try {
            $uploadResponse = Send-CorpusFile -Client $client -File $file `
                -UploadUrl "$BaseUrl/documents/upload" -DisplayName $relativePath `
                -TimeoutMinutes $UploadTimeoutMinutes

            if ($uploadResponse.TimedOut) {
                $currentTimedOut = $true
                throw [System.TimeoutException]::new([string]$uploadResponse.Error)
            }

            if (-not $uploadResponse.IsSuccess) {
                throw "Upload returned HTTP $($uploadResponse.StatusCode): $(Get-ErrorDescription -Body $uploadResponse.Body)"
            }

            $uploadResult = $uploadResponse.Body | ConvertFrom-Json
            $documentId = [string]$uploadResult.documentId
            if ([string]::IsNullOrWhiteSpace($documentId)) {
                throw "Upload response does not contain documentId: $($uploadResponse.Body)"
            }
            $currentDocumentId = $documentId

            $uploadChunkCount = 0
            if ($null -ne $uploadResult.chunkCount -and [int]$uploadResult.chunkCount -gt 0) {
                $uploadChunkCount = [int]$uploadResult.chunkCount
            }
            $currentChunkCount = $uploadChunkCount

            if ($uploadChunkCount -gt 0) {
                Write-Host "  PARSED: $relativePath | chunks=$uploadChunkCount | documentId=$documentId" `
                    -ForegroundColor Cyan
            } else {
                Write-Host "  Upload response received; checking parsing status | documentId=$documentId"
            }
            Write-Host '  Waiting for vectorization and consistency check...'

            $ingestionResult = Wait-ForIngestion -Client $client `
                -DocumentId $documentId `
                -TimeoutMinutes $EmbeddingTimeoutMinutes `
                -IntervalSeconds $PollIntervalSeconds `
                -AllowedPollingErrors $MaxPollingErrors `
                -DisplayName $relativePath

            $finalChunkCount = [Math]::Max($uploadChunkCount, [int]$ingestionResult.ChunkCount)
            $currentChunkCount = $finalChunkCount

            if ($ingestionResult.Success) {
                Write-Host "  SUCCESS: chunks=$finalChunkCount" -ForegroundColor Green
                $successfulSoFar++
                $completedChunksSoFar += $finalChunkCount
            } else {
                Write-Host "  FAILED: $($ingestionResult.Reason)" -ForegroundColor Red
                $failedSoFar++
                if ([string]$ingestionResult.Reason -like 'Timed out after*') {
                    $currentTimedOut = $true
                    $timedOutSoFar++
                }
            }

            $results.Add([pscustomobject]@{
                File        = $relativePath
                DocumentId  = $documentId
                Success     = [bool]$ingestionResult.Success
                Outcome     = if ($ingestionResult.Success) { 'SUCCESS' } else { 'FAILED' }
                TimedOut    = $currentTimedOut
                ChunkCount  = $finalChunkCount
                Reason      = [string]$ingestionResult.Reason
            })

            Show-OverallProgress -Completed $position -Total $files.Count `
                -Parsed $successfulSoFar -Skipped $skippedSoFar -Failed $failedSoFar `
                -GeneratedChunks $completedChunksSoFar
        } catch {
            Write-Progress -Id 2 -ParentId 1 -Activity "Current file: $relativePath" -Completed
            $reason = $_.Exception.Message
            if ($currentTimedOut) {
                Write-Host "  TIMED OUT: $reason" -ForegroundColor Yellow
            } else {
                Write-Host "  FAILED: $reason" -ForegroundColor Red
            }
            $results.Add([pscustomobject]@{
                File        = $relativePath
                DocumentId  = $currentDocumentId
                Success     = $false
                Outcome     = 'FAILED'
                TimedOut    = $currentTimedOut
                ChunkCount  = $currentChunkCount
                Reason      = $reason
            })
            $failedSoFar++
            if ($currentTimedOut) {
                $timedOutSoFar++
            }
            Show-OverallProgress -Completed $position -Total $files.Count `
                -Parsed $successfulSoFar -Skipped $skippedSoFar -Failed $failedSoFar `
                -GeneratedChunks $completedChunksSoFar
        }
    }
} catch {
    Write-Error $_.Exception.Message
    exit 2
} finally {
    Write-Progress -Id 2 -Activity 'Current file' -Completed
    Write-Progress -Id 1 -Activity 'Corpus ingestion progress' -Completed
    $client.Dispose()
    $handler.Dispose()
}

$runStopwatch.Stop()
$parsedResults = @($results | Where-Object { $_.Outcome -eq 'SUCCESS' })
$skippedResults = @($results | Where-Object { $_.Outcome -eq 'SKIPPED' })
$completedResults = @($results | Where-Object { $_.Success })
$failedResults = @($results | Where-Object { -not $_.Success })
$timedOutResults = @($failedResults | Where-Object { $_.TimedOut })
$parsedChunkCount = ($parsedResults | Measure-Object -Property ChunkCount -Sum).Sum
$totalChunkCount = ($completedResults | Measure-Object -Property ChunkCount -Sum).Sum

if ($null -eq $parsedChunkCount) {
    $parsedChunkCount = 0
}
if ($null -eq $totalChunkCount) {
    $totalChunkCount = 0
}

Write-Host ''
Write-Host '===== Ingestion summary ====='
Write-Host "Total elapsed time       : $(Format-ElapsedTime -Elapsed $runStopwatch.Elapsed)"
Write-Host "Documents parsed         : $($parsedResults.Count)"
Write-Host "Already indexed skipped  : $($skippedResults.Count)"
Write-Host "Completed documents total: $($completedResults.Count)"
Write-Host "Failed files             : $($failedResults.Count)"
Write-Host "Timed-out files          : $($timedOutResults.Count)"
Write-Host "Chunks parsed this run   : $parsedChunkCount"
Write-Host "Total chunks             : $totalChunkCount"

if ($failedResults.Count -gt 0) {
    Write-Host ''
    Write-Host 'Failed file details:'
    $failedResults |
        Select-Object File, DocumentId, TimedOut, ChunkCount, Reason |
        Format-Table -AutoSize -Wrap
    exit 1
}

exit 0
