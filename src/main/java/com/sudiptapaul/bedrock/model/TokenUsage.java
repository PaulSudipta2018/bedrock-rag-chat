package com.sudiptapaul.bedrock.model;

public record TokenUsage(
        int inputTokens,
        int outputTokens,
        double estimatedCostUsd
) {

    public static TokenUsage from(int input, int output, double inputRate, double outputRate) {
        double cost = (input * inputRate + output * outputRate) / 1_000_000.0;
        return new TokenUsage(input, output, cost);
    }
}
