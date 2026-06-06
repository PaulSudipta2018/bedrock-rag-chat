package com.sudiptapaul.bedrock.model;

public record ChatMessage(
        String role,       // "user" or "assistant"
        String content,
        String timestamp
) {}
