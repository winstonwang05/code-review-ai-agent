package com.codeguardian.service.ai.exception;

/**
 * @description: AI模型异常
 * AI模型调用过程中抛出的异常
 * @author: Winston
 * @date: 2026/3/4 14:57
 * @version: 1.0
 */
public class AIModelException extends RuntimeException {

    private final String provider;
    private final Integer statusCode;

    public AIModelException(String message) {
        super(message);
        this.provider = null;
        this.statusCode = null;
    }

    public AIModelException(String message, Throwable cause) {
        super(message, cause);
        this.provider = null;
        this.statusCode = null;
    }

    public AIModelException(String provider, String message) {
        super(String.format("[%s] %s", provider, message));
        this.provider = provider;
        this.statusCode = null;
    }

    public AIModelException(String provider, String message, Integer statusCode) {
        super(String.format("[%s] %s (status: %d)", provider, message, statusCode));
        this.provider = provider;
        this.statusCode = statusCode;
    }

    public AIModelException(String provider, String message, Throwable cause) {
        super(String.format("[%s] %s", provider, message), cause);
        this.provider = provider;
        this.statusCode = null;
    }

    public String getProvider() {
        return provider;
    }

    public Integer getStatusCode() {
        return statusCode;
    }}
