#!/usr/bin/env python3
"""运行人工标注的 RAG 中文题集，并保存逐题结果与汇总。"""

import argparse
import json
import statistics
import sys
import time
import unicodedata
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def normalize_for_match(value: str) -> str:
    """统一全半角并忽略空白，用于粗略核对中文答案关键词。"""
    return "".join(unicodedata.normalize("NFKC", value).split()).casefold()


def load_cases(path: Path) -> list[dict]:
    """读取 JSONL 题集并检查运行评测所需的字段。"""
    cases = []
    seen_ids = set()
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            case = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"第 {line_number} 行：JSON 格式错误：{exc.msg}") from exc
        if not isinstance(case, dict):
            raise ValueError(f"第 {line_number} 行：必须是 JSON 对象")
        case_id = case.get("id")
        status = case.get("expectedStatus")
        refs = case.get("referenceChunkIds")
        if not isinstance(case_id, str) or not case_id or case_id in seen_ids:
            raise ValueError(f"第 {line_number} 行：id 缺失或重复")
        if not isinstance(case.get("question"), str) or not case["question"].strip():
            raise ValueError(f"第 {line_number} 行：question 不能为空")
        if status not in ("ANSWERED", "INSUFFICIENT_EVIDENCE"):
            raise ValueError(f"第 {line_number} 行：expectedStatus 不合法")
        if not isinstance(refs, list) or any(
            not isinstance(ref, str) or not ref.strip() for ref in refs
        ) or len(refs) != len(set(refs)):
            raise ValueError(f"第 {line_number} 行：referenceChunkIds 必须是不重复的非空字符串数组")
        if status == "ANSWERED" and not refs:
            raise ValueError(f"第 {line_number} 行：有答案题必须标注参考 Chunk")
        if status == "INSUFFICIENT_EVIDENCE" and refs:
            raise ValueError(f"第 {line_number} 行：证据不足题不能标注参考 Chunk")
        for field in ("documentIds", "answerKeywords", "answerKeyPoints"):
            value = case.get(field)
            if value is not None and (not isinstance(value, list) or any(
                not isinstance(item, str) or not item.strip() for item in value
            )):
                raise ValueError(f"第 {line_number} 行：{field} 必须是非空字符串组成的数组")
        category = case.get("category", "uncategorized")
        if not isinstance(category, str) or not category.strip():
            raise ValueError(f"第 {line_number} 行：category 必须是非空字符串")
        if "topK" in case and (type(case["topK"]) is not int or case["topK"] < 1):
            raise ValueError(f"第 {line_number} 行：topK 必须是正整数")
        if "candidateTopK" in case and (
            type(case["candidateTopK"]) is not int or case["candidateTopK"] < 1
        ):
            raise ValueError(f"第 {line_number} 行：candidateTopK 必须是正整数")
        if "rerank" in case and not isinstance(case["rerank"], bool):
            raise ValueError(f"第 {line_number} 行：rerank 必须是布尔值")
        seen_ids.add(case_id)
        cases.append(case)
    if not cases:
        raise ValueError("题集不能为空")
    return cases


def post_json(url: str, payload: dict, timeout: float) -> object:
    """向现有 Java 接口发送 JSON 请求并解析响应。"""
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "application/json"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body[:500]}") from exc
    except URLError as exc:
        raise RuntimeError(f"接口连接失败：{exc.reason}") from exc


def evaluate_case(base_url: str, case: dict, timeout: float) -> dict:
    """分别调用检索和问答接口，核对全部证据、引用与答案关键词。"""
    result = {
        "id": case["id"],
        "category": case.get("category", "uncategorized"),
        "question": case["question"],
        "expectedStatus": case["expectedStatus"],
        "referenceChunkIds": case["referenceChunkIds"],
        "answerKeyPoints": case.get("answerKeyPoints", []),
        "passed": False,
    }
    base = base_url.rstrip("/")
    common = {
        "mode": case.get("mode", "HYBRID"),
        "topK": case.get("topK", 5),
        "rerank": case.get("rerank", True),
    }
    document_ids = case.get("documentIds")
    allowed_ids = set(document_ids) if document_ids else None
    started = time.monotonic()
    stage = "retrieval"
    try:
        retrieval_request = {
            "query": case["question"],
            "candidateTopK": case.get("candidateTopK", 30),
            "filter": {"documentIds": document_ids} if document_ids is not None else None,
            **common,
        }
        hits = post_json(base + "/api/retrieval/search", retrieval_request, timeout)
        if not isinstance(hits, list) or any(not isinstance(hit, dict) for hit in hits):
            raise ValueError("检索接口未返回对象数组")
        result["retrievalMs"] = round((time.monotonic() - started) * 1000)

        stage = "rag"
        rag_request = {"question": case["question"], **common}
        if document_ids is not None:
            rag_request["documentIds"] = document_ids
        rag_started = time.monotonic()
        response = post_json(base + "/api/rag/ask", rag_request, timeout)
        result["ragMs"] = round((time.monotonic() - rag_started) * 1000)
        if not isinstance(response, dict):
            raise ValueError("RAG 接口未返回对象")
        citations = response.get("citations")
        answer = response.get("answer")
        if not isinstance(citations, list) or any(
            not isinstance(item, dict) for item in citations
        ):
            raise ValueError("citations 不是对象数组")
        if not isinstance(answer, str):
            raise ValueError("answer 不是字符串")
        expected_refs = set(case["referenceChunkIds"])
        retrieved_refs = {hit.get("chunkId") for hit in hits}
        cited_refs = {item.get("chunkId") for item in citations}
        expected_answered = case["expectedStatus"] == "ANSWERED"
        normalized_answer = normalize_for_match(answer)
        missing_keywords = [word for word in case.get("answerKeywords", [])
                            if normalize_for_match(word) not in normalized_answer]
        checks = {
            "status": response.get("status") == case["expectedStatus"],
            # 负例没有参考 Chunk，不参加召回率和引用覆盖率统计。
            "referenceRetrieved": expected_refs <= retrieved_refs if expected_answered else None,
            "referenceCited": expected_refs <= cited_refs if expected_answered else None,
            "answerKeywords": not missing_keywords,
            "citationMarkers": all(
                type(item.get("ref")) is int and item["ref"] > 0
                and f"[{item['ref']}]" in answer for item in citations
            ),
            "retrievalDocumentScope": allowed_ids is None or all(
                hit.get("documentId") in allowed_ids for hit in hits
            ),
            "documentScope": allowed_ids is None or all(
                item.get("documentId") in allowed_ids for item in citations
            ),
            "refusalHasNoCitations": expected_answered or not citations,
        }
        result.update({
            "checks": checks,
            "passed": all(value for value in checks.values() if value is not None),
            "failureReasons": [name for name, value in checks.items() if value is False],
            "missingAnswerKeywords": missing_keywords,
            "missingRetrievedChunkIds": sorted(expected_refs - retrieved_refs),
            "missingCitedChunkIds": sorted(expected_refs - cited_refs),
            "retrievedChunkIds": sorted(ref for ref in retrieved_refs if isinstance(ref, str)),
            "response": response,
        })
    except (RuntimeError, ValueError, OSError) as exc:
        result["errorStage"] = stage
        result["error"] = str(exc)
        result["failureReasons"] = [stage + "Error"]
    result["totalMs"] = round((time.monotonic() - started) * 1000)
    return result


def summarize(cases: list[dict], results: list[dict]) -> dict:
    """汇总总通过率、正例证据覆盖率和各题型结果。"""
    groups = defaultdict(list)
    for case, result in zip(cases, results):
        groups[case.get("category", "uncategorized")].append(result)
    answered = [result for result in results if result["expectedStatus"] == "ANSWERED"]
    by_category = {
        name: {
            "total": len(items),
            "passed": sum(item["passed"] for item in items),
            "failed": sum(not item["passed"] for item in items),
        }
        for name, items in sorted(groups.items())
    }
    return {
        "total": len(results),
        "passed": sum(result["passed"] for result in results),
        "failed": sum(not result["passed"] for result in results),
        "answeredCases": len(answered),
        "referenceRetrieved": sum(result.get("checks", {}).get("referenceRetrieved") is True
                                  for result in answered),
        "referenceCited": sum(result.get("checks", {}).get("referenceCited") is True
                              for result in answered),
        "averageTotalMs": round(statistics.mean(result["totalMs"] for result in results)),
        "baselineSizeReached": 20 <= len(cases) <= 30,
        "byCategory": by_category,
    }


def main() -> int:
    """运行评测，将逐题诊断和分类汇总写入 JSON 文件。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=Path, default=Path("docs/rag_eval_cases.jsonl"))
    parser.add_argument("--output", type=Path, default=Path("docs/rag_eval_results.json"))
    parser.add_argument("--base-url", default="http://127.0.0.1:18400")
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")

    cases = load_cases(args.cases)
    results = []
    for case in cases:
        result = evaluate_case(args.base_url, case, args.timeout)
        results.append(result)
        status = "PASS" if result["passed"] else "FAIL"
        reasons = ",".join(result.get("failureReasons", []))
        print(f"{case['id']} [{result['category']}]: {status} ({result['totalMs']} ms) {reasons}")

    summary = summarize(cases, results)
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "notes": "关键词只做空白归一化匹配；answerKeyPoints 与事实是否受引用支持需人工核对。",
        "summary": summary,
        "cases": results,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"汇总：{summary}；逐题结果：{args.output}")
    return 0 if summary["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
