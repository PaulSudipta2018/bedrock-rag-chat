package com.sudiptapaul.bedrock.model;

import java.util.List;

public record ChatResponse(
        String answer,
        List<String> sources,
        String sessionId,
        boolean blocked,
        String blockReason,
        TokenUsage tokenUsage   // null for blocked responses
) {}
