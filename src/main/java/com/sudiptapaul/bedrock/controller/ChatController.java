package com.sudiptapaul.bedrock.controller;

// Phase 2 will add: POST /api/chat/{sessionId}, GET /api/chat/{sessionId}/history, DELETE /api/chat/{sessionId}

import com.sudiptapaul.bedrock.audit.AuditService;
import com.sudiptapaul.bedrock.model.AuditLog;
import com.sudiptapaul.bedrock.model.ChatRequest;
import com.sudiptapaul.bedrock.model.ChatResponse;
import com.sudiptapaul.bedrock.model.GuardrailResult;
import com.sudiptapaul.bedrock.service.ChatService;
import com.sudiptapaul.bedrock.service.GuardrailService;
import com.sudiptapaul.bedrock.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final GuardrailService guardrailService;
    private final RagService        ragService;
    private final ChatService       chatService;
    private final AuditService      auditService;

    /**
     * One-shot RAG Q&A with guardrail pipeline.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Guardrail input check — block or mask PII / denied topics</li>
     *   <li>Bedrock KB retrieve — semantic search for relevant chunks</li>
     *   <li>Prompt assembly + model invocation with circuit-breaker fallback</li>
     *   <li>Guardrail output check — block grounded / unsafe responses</li>
     * </ol>
     *
     * @param request contains the user's question and optional sessionId
     * @return {@link ChatResponse} with answer, sources, and token usage;
     *         {@code blocked=true} when a guardrail policy fires
     */
    @PostMapping("/rag/ask")
    public ResponseEntity<ChatResponse> ask(@RequestBody ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());

        // ── 1. Guardrail: input check ─────────────────────────────────────────
        GuardrailResult inputCheck = guardrailService.checkInput(request.message());

        if (inputCheck.isBlocked()) {
            log.warn("Input blocked by guardrail. sessionId={}, trigger={}",
                    sessionId, inputCheck.getTriggerType());

            auditService.logGuardrailTrigger(AuditLog.builder()
                    .sessionId(sessionId)
                    .timestamp(Instant.now().toString())
                    .triggerType(inputCheck.getTriggerType())
                    .originalInput(request.message())
                    .maskedInput(inputCheck.getMaskedText())
                    .action("BLOCKED_INPUT")
                    .userId("anonymous")
                    .build());

            return ResponseEntity.ok(new ChatResponse(
                    "Your request was blocked by the content policy.",
                    Collections.emptyList(),
                    sessionId,
                    true,
                    inputCheck.getTriggerReason(),
                    null
            ));
        }

        // Use masked text downstream — may have PII redacted by the guardrail
        String safeInput = inputCheck.getMaskedText();

        // ── 2. RAG: retrieve relevant chunks ──────────────────────────────────
        List<KnowledgeBaseRetrievalResult> chunks = ragService.retrieve(safeInput);

        if (chunks.isEmpty()) {
            log.info("No KB chunks retrieved. sessionId={}", sessionId);
            return ResponseEntity.ok(new ChatResponse(
                    "No relevant information found.",
                    Collections.emptyList(),
                    sessionId,
                    false,
                    null,
                    null
            ));
        }

        // ── 3. Build prompt and invoke model ──────────────────────────────────
        List<String> chunkTexts = ragService.extractChunkTexts(chunks);
        String prompt = chatService.buildPrompt(safeInput, chunkTexts, Collections.emptyList());
        ChatService.ChatServiceResponse modelResponse = chatService.invoke(prompt);

        // ── 4. Guardrail: output check ────────────────────────────────────────
        GuardrailResult outputCheck = guardrailService.checkOutput(modelResponse.answer());

        if (outputCheck.isBlocked()) {
            log.warn("Output blocked by guardrail. sessionId={}, trigger={}",
                    sessionId, outputCheck.getTriggerType());

            auditService.logGuardrailTrigger(AuditLog.builder()
                    .sessionId(sessionId)
                    .timestamp(Instant.now().toString())
                    .triggerType(outputCheck.getTriggerType())
                    .originalInput(modelResponse.answer())
                    .maskedInput(outputCheck.getMaskedText())
                    .action("BLOCKED_OUTPUT")
                    .userId("anonymous")
                    .build());

            return ResponseEntity.ok(new ChatResponse(
                    "The response was blocked by the content policy.",
                    Collections.emptyList(),
                    sessionId,
                    true,
                    outputCheck.getTriggerReason(),
                    null
            ));
        }

        return ResponseEntity.ok(new ChatResponse(
                modelResponse.answer(),
                chunkTexts,
                sessionId,
                false,
                null,
                modelResponse.tokenUsage()
        ));
    }

    /**
     * Probes the configured guardrail and reports availability.
     *
     * @return {@code {"guardrailAvailable": true}} when the guardrail API is reachable
     */
    @GetMapping("/health/guardrail")
    public ResponseEntity<Map<String, Boolean>> guardrailHealth() {
        return ResponseEntity.ok(Map.of("guardrailAvailable", guardrailService.isAvailable()));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Returns the caller-supplied sessionId if present, otherwise falls back to
     * the MDC traceId (injected by {@code TraceIdFilter}), and finally generates
     * a fresh UUID if neither is available.
     */
    private String resolveSessionId(String requestSessionId) {
        if (requestSessionId != null && !requestSessionId.isBlank()) {
            return requestSessionId;
        }
        String traceId = MDC.get("traceId");
        return traceId != null ? traceId : UUID.randomUUID().toString();
    }
}
