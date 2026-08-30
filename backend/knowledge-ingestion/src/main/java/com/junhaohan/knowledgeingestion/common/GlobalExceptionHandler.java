package com.junhaohan.knowledgeingestion.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 文件大小超过限制
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSize(
            MaxUploadSizeExceededException e) {

        log.warn("上传文件超过大小限制", e);

        return buildResponse(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "上传文件超过大小限制"
        );
    }

    /**
     * Multipart 请求解析失败
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipartException(
            MultipartException e) {

        log.error("Multipart请求解析失败", e);

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                "文件上传请求解析失败：" + getMessage(e)
        );
    }

    /**
     * 参数、文件格式等业务参数异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException e) {

        log.warn("请求参数异常: {}", e.getMessage(), e);

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                e.getMessage()
        );
    }

    /**
     * 未知异常兜底
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {

        log.error("服务器内部异常", e);

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "服务器内部错误"
        );
    }

    private ResponseEntity<Map<String, Object>> buildResponse(
            HttpStatus status,
            String message) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", message);

        return ResponseEntity.status(status).body(body);
    }

    private String getMessage(Throwable e) {
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }

        return e.getClass().getSimpleName();
    }
}