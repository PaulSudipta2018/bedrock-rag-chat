package com.sudiptapaul.bedrock.model;

import java.time.Instant;

public record ErrorResponse(
        String traceId,
        String message,
        int statusCode,
        String timestamp
) {

    public static ErrorResponse of(String traceId, String message, int statusCode) {
        return new ErrorResponse(traceId, message, statusCode, Instant.now().toString());
    }
}
