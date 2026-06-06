package com.sudiptapaul.bedrock.service;

import com.sudiptapaul.bedrock.model.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

/**
 * Stateless service responsible for tracking token consumption and estimated cost
 * for every AWS Bedrock model invocation.
 *
 * <p>Each invocation produces a {@link TokenUsage} record and pushes three Micrometer
 * counters (input tokens, output tokens, estimated cost) tagged with the model ID.
 * These counters are automatically exported to CloudWatch via
 * {@code micrometer-registry-cloudwatch2} when enabled in {@code application.yml}.
 *
 * <p>This service has no persistence — it is a fire-and-forget metrics emitter.
 * Durable cost records are written to DynamoDB by the audit pipeline instead.
 */
@Slf4j
@Service
public class TokenTrackingService {

    /** Amazon Nova Lite input token rate in USD per one million tokens. */
    private static final double NOVA_LITE_INPUT_RATE  = 0.06;

    /** Amazon Nova Lite output token rate in USD per one million tokens. */
    private static final double NOVA_LITE_OUTPUT_RATE = 0.24;

    private final MeterRegistry meterRegistry;

    /**
     * Constructs a {@code TokenTrackingService} with the given Micrometer registry.
     *
     * @param meterRegistry Micrometer registry used to publish token and cost counters
     */
    public TokenTrackingService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records token usage for a single Bedrock model invocation, logs the consumption
     * summary, and increments the corresponding Micrometer counters.
     *
     * <p>Cost is calculated using the Nova Lite rates:
     * <pre>
     *   estimatedCostUsd = (inputTokens * INPUT_RATE + outputTokens * OUTPUT_RATE) / 1_000_000
     * </pre>
     *
     * <p>Three counters are published, all tagged with {@code model=<modelId>}:
     * <ul>
     *   <li>{@code bedrock.tokens.input}  — cumulative input tokens</li>
     *   <li>{@code bedrock.tokens.output} — cumulative output tokens</li>
     *   <li>{@code bedrock.cost.usd}      — cumulative estimated cost in USD</li>
     * </ul>
     *
     * @param modelId      the Bedrock model ID (e.g. {@code amazon.nova-lite-v1:0}),
     *                     used as the {@code model} tag on all metrics
     * @param inputTokens  number of tokens in the prompt sent to the model
     * @param outputTokens number of tokens in the model's response
     * @return a {@link TokenUsage} record containing the token counts and estimated cost
     */
    public TokenUsage track(String modelId, int inputTokens, int outputTokens) {
        TokenUsage tokenUsage = TokenUsage.from(inputTokens, outputTokens,
                NOVA_LITE_INPUT_RATE, NOVA_LITE_OUTPUT_RATE);

        log.info("Model={}, Input={}tokens, Output={}tokens, Cost=${}",
                modelId, inputTokens, outputTokens,
                String.format("%.6f", tokenUsage.estimatedCostUsd()));

        meterRegistry.counter("bedrock.tokens.input",  "model", modelId).increment(inputTokens);
        meterRegistry.counter("bedrock.tokens.output", "model", modelId).increment(outputTokens);
        meterRegistry.counter("bedrock.cost.usd",      "model", modelId).increment(tokenUsage.estimatedCostUsd());

        return tokenUsage;
    }

    /**
     * Parses a Bedrock {@code InvokeModel} response body to extract input and output token counts.
     *
     * <p>The Bedrock runtime wraps token usage in a top-level {@code usage} object.
     * For Amazon Nova models the structure is:
     * <pre>
     * {
     *   "usage": {
     *     "inputTokens":  123,
     *     "outputTokens": 45
     *   }
     * }
     * </pre>
     *
     * <p>If the {@code usage} field is absent (e.g. the model does not report token counts,
     * or the response body is malformed), the method logs a warning and returns {@code {0, 0}}
     * so the caller can still proceed without failing.
     *
     * @param responseBody the parsed JSON response body from a Bedrock {@code InvokeModel} call
     * @return a two-element array {@code [inputTokens, outputTokens]};
     *         returns {@code {0, 0}} when the {@code usage} field is missing
     */
    public int[] extractTokenCounts(JSONObject responseBody) {
        if (!responseBody.has("usage")) {
            log.warn("Bedrock response body has no 'usage' field — token counts unavailable. " +
                     "Model may not report usage or response structure has changed.");
            return new int[]{0, 0};
        }

        JSONObject usage = responseBody.getJSONObject("usage");
        int inputTokens  = usage.optInt("inputTokens",  0);
        int outputTokens = usage.optInt("outputTokens", 0);
        return new int[]{inputTokens, outputTokens};
    }
}
