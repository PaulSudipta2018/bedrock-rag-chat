package com.sudiptapaul.bedrock.exception;

public class GuardrailServiceException extends RuntimeException {

    public GuardrailServiceException(String message) {
        super(message);
    }

    public GuardrailServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
