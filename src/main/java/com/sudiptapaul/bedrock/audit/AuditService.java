package com.sudiptapaul.bedrock.audit;

// Fire-and-forget audit service. Failures are logged
// but swallowed. Main request flow must never be impacted
// by audit persistence issues.

import com.sudiptapaul.bedrock.model.AuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AuditService {

    private static final long TTL_DAYS = 90;

    private final DynamoDbClient dynamoDbClient;

    @Value("${dynamodb.table.audit-log}")
    private String auditLogTable;

    public AuditService(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Persists a guardrail trigger event to the DynamoDB audit-log table.
     *
     * <p>Table schema:
     * <ul>
     *   <li>Partition key: {@code sessionId} (String)</li>
     *   <li>Sort key: {@code timestamp} (String, ISO-8601)</li>
     *   <li>Attributes: {@code triggerType}, {@code originalInput},
     *       {@code maskedInput}, {@code action}, {@code userId}</li>
     *   <li>TTL attribute: {@code expiresAt} (Number, epoch seconds) —
     *       DynamoDB automatically deletes records after 90 days</li>
     * </ul>
     *
     * <p>All exceptions are caught and logged. Audit failure must never
     * propagate to the caller or affect the user's response.
     *
     * @param auditLog the populated audit record to persist
     */
    public void logGuardrailTrigger(AuditLog auditLog) {
        try {
            long expiresAt = Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS).getEpochSecond();

            Map<String, AttributeValue> item = new HashMap<>();
            item.put("sessionId",     AttributeValue.fromS(auditLog.getSessionId()));
            item.put("timestamp",     AttributeValue.fromS(auditLog.getTimestamp()));
            item.put("triggerType",   AttributeValue.fromS(nullSafe(auditLog.getTriggerType())));
            item.put("originalInput", AttributeValue.fromS(nullSafe(auditLog.getOriginalInput())));
            item.put("maskedInput",   AttributeValue.fromS(nullSafe(auditLog.getMaskedInput())));
            item.put("action",        AttributeValue.fromS(nullSafe(auditLog.getAction())));
            item.put("userId",        AttributeValue.fromS(nullSafe(auditLog.getUserId())));
            item.put("expiresAt",     AttributeValue.fromN(String.valueOf(expiresAt)));

            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(auditLogTable)
                    .item(item)
                    .build());

            log.debug("Audit log saved. sessionId={}, triggerType={}, expiresAt={}",
                    auditLog.getSessionId(), auditLog.getTriggerType(), expiresAt);

        } catch (Exception e) {
            log.error("Failed to save audit log — swallowing to protect main flow. " +
                      "sessionId={}, triggerType={}, error={}",
                    auditLog.getSessionId(), auditLog.getTriggerType(), e.getMessage(), e);
        }
    }

    /**
     * Retrieves all audit log entries for a session, ordered by timestamp ascending.
     *
     * <p>Intended for a future admin endpoint. Uses {@code #ts} as an expression
     * attribute name alias for the {@code timestamp} sort key, which is a
     * DynamoDB reserved word.
     *
     * <p>Returns an empty list on any error — never throws.
     *
     * @param sessionId the session to query
     * @return chronologically ordered list of {@link AuditLog} records;
     *         empty list on any DynamoDB error
     */
    public List<AuditLog> getAuditLogs(String sessionId) {
        try {
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(auditLogTable)
                    .keyConditionExpression("sessionId = :sid")
                    .expressionAttributeValues(Map.of(
                            ":sid", AttributeValue.fromS(sessionId)))
                    .expressionAttributeNames(Map.of("#ts", "timestamp"))
                    .projectionExpression(
                            "sessionId, #ts, triggerType, originalInput, maskedInput, action, userId")
                    .scanIndexForward(true)
                    .build();

            return dynamoDbClient.query(queryRequest).items().stream()
                    .map(this::toAuditLog)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to retrieve audit logs — returning empty list. sessionId={}, error={}",
                    sessionId, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private AuditLog toAuditLog(Map<String, AttributeValue> item) {
        return AuditLog.builder()
                .sessionId(getString(item, "sessionId"))
                .timestamp(getString(item, "timestamp"))
                .triggerType(getString(item, "triggerType"))
                .originalInput(getString(item, "originalInput"))
                .maskedInput(getString(item, "maskedInput"))
                .action(getString(item, "action"))
                .userId(getString(item, "userId"))
                .build();
    }

    private String getString(Map<String, AttributeValue> item, String key) {
        AttributeValue val = item.get(key);
        return val != null ? val.s() : null;
    }

    /** Returns the string value, or an empty string if null — DynamoDB rejects null String attributes. */
    private String nullSafe(String value) {
        return value != null ? value : "";
    }
}
