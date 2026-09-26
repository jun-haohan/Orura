package com.junhaohan.knowledgeingestion.rag.llm;

/** 模型服务调用失败，保留是否超时供 API 层处理。 */
public class LlmClientException extends RuntimeException {

    private final boolean timeout;

    /** 记录不带底层异常的模型调用失败。 */
    public LlmClientException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }

    /** 记录带有底层异常的模型调用失败。 */
    public LlmClientException(String message, boolean timeout, Throwable cause) {
        super(message, cause);
        this.timeout = timeout;
    }

    /** 判断模型调用是否因超时失败。 */
    public boolean isTimeout() {
        return timeout;
    }
}