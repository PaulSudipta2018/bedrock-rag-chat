package com.sudiptapaul.bedrock.service;

import com.sudiptapaul.bedrock.model.ChatMessage;
import com.sudiptapaul.bedrock.model.TokenUsage;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestration service for the RAG chat pipeline.
 *
 * <p>Responsible for prompt construction, model invocation via
 * {@link BedrockRuntimeClient}, token cost tracking, and
 * conversation history persistence in DynamoDB.
 */
@Slf4j
@Service
public class ChatService {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String SYSTEM_INSTRUCTION =
            "You are a helpful IPTV customer support assistant. " +
            "Answer questions ONLY from the provided context. " +
            "If the answer is not in the context, say you don't have that information. " +
            "Be concise.";

    private static final String STATIC_FALLBACK_RESPONSE =
            "I'm temporarily unable to process your request. Please try again shortly.";

    /** Number of recent history turns included in the prompt context window. */
    private static final int MAX_HISTORY_FOR_PROMPT = 5;

    /** Maximum messages fetched from DynamoDB for a session. */
    private static final int MAX_HISTORY_QUERY = 10;

    /** DynamoDB BatchWriteItem hard limit per request. */
    private static final int DYNAMO_BATCH_SIZE = 25;

    /** Max tokens allowed in the model response. */
    private static final int MAX_TOKENS = 1024;

    // ── Inner record ──────────────────────────────────────────────────────────

    /**
     * Carries both the model's text answer and token usage metadata
     * back to the calling orchestrator.
     */
    public record ChatServiceResponse(String answer, TokenUsage tokenUsage) {}

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final DynamoDbClient       dynamoDbClient;
    private final TokenTrackingService tokenTrackingService;
    private final ObjectMapper objectMapper;

    @Value("${bedrock.model.primary:amazon.nova-lite-v1:0}")
    private String primaryModelId;

    @Value("${bedrock.model.fallback:amazon.nova-micro-v1:0}")
    private String fallbackModelId;

    @Value("${dynamodb.table.chat-history}")
    private String chatHistoryTable;

    public ChatService(BedrockRuntimeClient bedrockRuntimeClient,
                       DynamoDbClient dynamoDbClient,
                       TokenTrackingService tokenTrackingService,
                       ObjectMapper objectMapper) {
        this.bedrockRuntimeClient = bedrockRuntimeClient;
        this.dynamoDbClient       = dynamoDbClient;
        this.tokenTrackingService = tokenTrackingService;
        this.objectMapper         = objectMapper;
    }

    // ── Public methods ────────────────────────────────────────────────────────

    /**
     * Assembles the full prompt sent to the foundation model.
     *
     * <p>Structure:
     * <ol>
     *   <li>System instruction — defines assistant persona and constraints</li>
     *   <li>Context section — KB chunks joined with {@code "\n---\n"} separators</li>
     *   <li>History section — last {@value MAX_HISTORY_FOR_PROMPT} turns formatted
     *       as {@code "Human: ...\nAssistant: ..."}</li>
     *   <li>Current question</li>
     * </ol>
     *
     * @param question the user's current question
     * @param chunks   retrieved KB chunks to inject as grounding context
     * @param history  full conversation history for this session; only the
     *                 most recent {@value MAX_HISTORY_FOR_PROMPT} turns are used
     * @return the assembled prompt string ready for model invocation
     */
    public String buildPrompt(String question, List<String> chunks, List<ChatMessage> history) {
        StringBuilder prompt = new StringBuilder();

        prompt.append(SYSTEM_INSTRUCTION).append("\n\n");

        if (!chunks.isEmpty()) {
            prompt.append("### Context:\n");
            prompt.append(String.join("\n---\n", chunks));
            prompt.append("\n\n");
        }

        List<ChatMessage> recentHistory = history.size() > MAX_HISTORY_FOR_PROMPT
                ? history.subList(history.size() - MAX_HISTORY_FOR_PROMPT, history.size())
                : history;

        if (!recentHistory.isEmpty()) {
            prompt.append("### Conversation History:\n");
            for (ChatMessage msg : recentHistory) {
                String prefix = "user".equalsIgnoreCase(msg.role()) ? "Human" : "Assistant";
                prompt.append(prefix).append(": ").append(msg.content()).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("### Question:\n").append(question);

        return prompt.toString();
    }

    /**
     * Invokes the primary Bedrock foundation model with the assembled prompt.
     *
     * <p>The request body follows the Amazon Nova Messages API format:
     * <pre>
     * {
     *   "messages": [{"role": "user", "content": [{"text": "..."}]}],
     *   "inferenceConfig": {"maxTokens": 1024}
     * }
     * </pre>
     *
     * <p>The response is parsed to extract the answer text and token usage.
     * Token counts are forwarded to {@link TokenTrackingService} for metric publishing.
     *
     * <p>Protected by {@code @CircuitBreaker} and {@code @Retry} (instance {@code bedrock}).
     * On failure, Resilience4j routes to {@link #invokeFallback}.
     *
     * @param prompt the fully assembled prompt from {@link #buildPrompt}
     * @return a {@link ChatServiceResponse} containing the answer and token usage
     */
    @CircuitBreaker(name = "bedrock", fallbackMethod = "invokeFallback")
    @Retry(name = "bedrock")
    public ChatServiceResponse invoke(String prompt) {
        return invokeWithModel(prompt, primaryModelId);
    }

    /**
     * Fallback invoked by Resilience4j when the primary model call fails or the
     * {@code bedrock} circuit breaker is open.
     *
     * <p>Attempts the fallback model ({@code bedrock.model.fallback}).
     * If the fallback also fails, returns a static degraded-mode response
     * rather than propagating an exception to the end user.
     *
     * @param prompt the original prompt passed to {@link #invoke}
     * @param t      the exception or open-circuit signal that triggered the fallback
     * @return a {@link ChatServiceResponse} from the fallback model, or a static
     *         response if both models are unavailable
     */
    public ChatServiceResponse invokeFallback(String prompt, Throwable t) {
        log.warn("Primary model failed, trying fallback: {}", t.getMessage());
        try {
            return invokeWithModel(prompt, fallbackModelId);
        } catch (Exception e) {
            log.error("Fallback model also failed — returning static response. error={}", e.getMessage());
            return new ChatServiceResponse(STATIC_FALLBACK_RESPONSE, null);
        }
    }

    /**
     * Persists a single chat message to the DynamoDB chat-history table.
     *
     * <p>Table schema:
     * <ul>
     *   <li>Partition key: {@code sessionId} (String)</li>
     *   <li>Sort key: {@code timestamp} (String, ISO-8601)</li>
     *   <li>Attributes: {@code role}, {@code content}</li>
     * </ul>
     *
     * <p>Errors are logged but never rethrown — a DynamoDB write failure
     * must not break the main chat response flow.
     *
     * @param sessionId the conversation session identifier
     * @param message   the message to persist
     */
    public void saveMessage(String sessionId, ChatMessage message) {
        try {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("sessionId", AttributeValue.fromS(sessionId));
            item.put("timestamp", AttributeValue.fromS(message.timestamp()));
            item.put("role",      AttributeValue.fromS(message.role()));
            item.put("content",   AttributeValue.fromS(message.content()));

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(chatHistoryTable)
                    .item(item)
                    .build());

            log.debug("Saved message. sessionId={}, role={}", sessionId, message.role());

        } catch (Exception e) {
            log.error("Failed to save chat message. sessionId={}, role={}, error={}",
                    sessionId, message.role(), e.getMessage(), e);
        }
    }

    /**
     * Retrieves the most recent conversation history for a session from DynamoDB.
     *
     * <p>Returns messages in chronological order (oldest first) up to
     * {@value MAX_HISTORY_QUERY} items.
     *
     * <p>Returns an empty list on any DynamoDB error — the caller can still
     * respond to the user without history context.
     *
     * @param sessionId the conversation session identifier
     * @return chronologically ordered list of {@link ChatMessage}; empty on error
     */
    public List<ChatMessage> getHistory(String sessionId) {
        try {
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(chatHistoryTable)
                    .keyConditionExpression("sessionId = :sid")
                    .expressionAttributeValues(Map.of(
                            ":sid", AttributeValue.fromS(sessionId)))
                    .scanIndexForward(true)
                    .limit(MAX_HISTORY_QUERY)
                    .build();

            return dynamoDbClient.query(queryRequest).items().stream()
                    .map(item -> new ChatMessage(
                            item.get("role").s(),
                            item.get("content").s(),
                            item.get("timestamp").s()))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to retrieve chat history. sessionId={}, error={}",
                    sessionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Deletes all conversation messages for a session from DynamoDB.
     *
     * <p>Queries for all item keys belonging to the session, then issues
     * {@code BatchWriteItem} delete requests in chunks of {@value DYNAMO_BATCH_SIZE}
     * (the DynamoDB per-request limit).
     *
     * <p>Uses {@code #ts} as an expression attribute name alias for the
     * {@code timestamp} sort key, which is a DynamoDB reserved word.
     *
     * <p>Errors are logged but not rethrown — used by {@code DELETE /api/chat/{sessionId}}.
     *
     * @param sessionId the session whose history should be purged
     */
    public void clearHistory(String sessionId) {
        try {
            // timestamp is a DynamoDB reserved word — alias it with #ts
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(chatHistoryTable)
                    .keyConditionExpression("sessionId = :sid")
                    .expressionAttributeValues(Map.of(
                            ":sid", AttributeValue.fromS(sessionId)))
                    .projectionExpression("sessionId, #ts")
                    .expressionAttributeNames(Map.of("#ts", "timestamp"))
                    .build();

            List<Map<String, AttributeValue>> items =
                    dynamoDbClient.query(queryRequest).items();

            for (int i = 0; i < items.size(); i += DYNAMO_BATCH_SIZE) {
                List<WriteRequest> deleteRequests =
                        items.subList(i, Math.min(i + DYNAMO_BATCH_SIZE, items.size()))
                                .stream()
                                .map(item -> WriteRequest.builder()
                                        .deleteRequest(DeleteRequest.builder()
                                                .key(Map.of(
                                                        "sessionId", item.get("sessionId"),
                                                        "timestamp", item.get("timestamp")))
                                                .build())
                                        .build())
                                .collect(Collectors.toList());

                dynamoDbClient.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(chatHistoryTable, deleteRequests))
                        .build());
            }

            log.info("Cleared {} messages for sessionId={}", items.size(), sessionId);

        } catch (Exception e) {
            log.error("Failed to clear chat history. sessionId={}, error={}",
                    sessionId, e.getMessage(), e);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Core model invocation logic shared by {@link #invoke} and {@link #invokeFallback}.
     *
     * <p>Builds the Amazon Nova Messages API request body via Jackson, calls
     * {@code InvokeModel}, parses the JSON response for the answer text, extracts
     * token counts via {@link TokenTrackingService#extractTokenCounts}, and
     * publishes metrics via {@link TokenTrackingService#track}.
     *
     * @param prompt  the assembled prompt
     * @param modelId the Bedrock model ID to invoke
     * @return {@link ChatServiceResponse} with the model answer and token usage
     * @throws RuntimeException wrapping any SDK or JSON parsing failure
     */
    private ChatServiceResponse invokeWithModel(String prompt, String modelId) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode messages = requestBody.putArray("messages");
            messages.addObject()
                    .put("role", "user")
                    .putArray("content")
                    .addObject()
                    .put("text", prompt);
            requestBody.putObject("inferenceConfig").put("maxTokens", MAX_TOKENS);

            InvokeModelRequest request = InvokeModelRequest.builder()
                    .modelId(modelId)
                    .contentType("application/json")
                    .accept("application/json")
                    .body(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(requestBody)))
                    .build();

            InvokeModelResponse response = bedrockRuntimeClient.invokeModel(request);
            String responseBodyStr = response.body().asUtf8String();

            JsonNode root   = objectMapper.readTree(responseBodyStr);
            String   answer = root.path("output")
                                  .path("message")
                                  .path("content")
                                  .get(0)
                                  .path("text")
                                  .asText();

            int[]      tokenCounts = tokenTrackingService.extractTokenCounts(new JSONObject(responseBodyStr));
            TokenUsage tokenUsage  = tokenTrackingService.track(modelId, tokenCounts[0], tokenCounts[1]);

            log.debug("Model={} answered. inputTokens={}, outputTokens={}",
                    modelId, tokenCounts[0], tokenCounts[1]);

            return new ChatServiceResponse(answer, tokenUsage);

        } catch (Exception e) {
            throw new RuntimeException(
                    "Model invocation failed for modelId=" + modelId + ": " + e.getMessage(), e);
        }
    }
}
