package com.sudiptapaul.bedrock.model;

public record ChatRequest(
        String message,
        String sessionId   // nullable — server generates one if absent
) {}
