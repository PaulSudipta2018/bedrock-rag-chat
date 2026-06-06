package com.sudiptapaul.bedrock.service;

import com.sudiptapaul.bedrock.exception.RagServiceException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseQuery;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseVectorSearchConfiguration;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service responsible for the Retrieval step of the RAG (Retrieval-Augmented Generation) pipeline.
 *
 * <p>Communicates with an AWS Bedrock Knowledge Base via {@link BedrockAgentRuntimeClient} to perform
 * semantic vector search against indexed documents. The retrieved chunks are later used by the
 * generation step to ground model responses in factual source material.
 *
 * <p>All outbound Bedrock calls are protected by a Resilience4j circuit breaker and retry policy
 * (instance name {@code bedrock}) configured in {@code application.yml}.
 */
@Slf4j
@Service
public class RagService {

    private static final int NUMBER_OF_RESULTS = 5;

    private final BedrockAgentRuntimeClient agentRuntimeClient;

    @Value("${bedrock.knowledgebase.id}")
    private String knowledgeBaseId;

    /**
     * Constructs a {@code RagService} with the given Bedrock Agent Runtime client.
     *
     * @param agentRuntimeClient AWS SDK v2 client for Bedrock Agent Runtime operations
     */
    public RagService(BedrockAgentRuntimeClient agentRuntimeClient) {
        this.agentRuntimeClient = agentRuntimeClient;
    }

    /**
     * Queries the configured Bedrock Knowledge Base and retrieves the top-N most semantically
     * relevant document chunks for the given question.
     *
     * <p>The search is vector-based: the question is embedded and compared against indexed document
     * embeddings using cosine similarity. Up to {@value NUMBER_OF_RESULTS} chunks are returned,
     * each carrying its text content, source location, and a relevance score (0.0–1.0).
     *
     * <p>This method is guarded by:
     * <ul>
     *   <li><b>@Retry</b> — retries up to 3 times with exponential backoff on
     *       {@code ThrottlingException} or {@code SdkServiceException}.</li>
     *   <li><b>@CircuitBreaker</b> — after 50% failure rate over a 10-call sliding window,
     *       the circuit opens and calls are immediately redirected to {@link #retrieveFallback}
     *       for 30 seconds before a half-open probe is attempted.</li>
     * </ul>
     *
     * @param question the natural-language question to search against the Knowledge Base
     * @return list of {@link KnowledgeBaseRetrievalResult} containing matched chunks with scores;
     *         never {@code null}, but may be empty if the KB returns no results
     * @throws RagServiceException if the AWS call fails after all retries are exhausted
     */
    @CircuitBreaker(name = "bedrock", fallbackMethod = "retrieveFallback")
    @Retry(name = "bedrock")
    public List<KnowledgeBaseRetrievalResult> retrieve(String question) {
        try {
            RetrieveRequest request = RetrieveRequest.builder()
                    .knowledgeBaseId(knowledgeBaseId)
                    .retrievalQuery(KnowledgeBaseQuery.builder()
                            .text(question)
                            .build())
                    .retrievalConfiguration(KnowledgeBaseRetrievalConfiguration.builder()
                            .vectorSearchConfiguration(KnowledgeBaseVectorSearchConfiguration.builder()
                                    .numberOfResults(NUMBER_OF_RESULTS)
                                    .build())
                            .build())
                    .build();

            List<KnowledgeBaseRetrievalResult> results = agentRuntimeClient.retrieve(request).retrievalResults();

            log.debug("Retrieved {} chunks for question: {}",
                    results.size(),
                    question.length() > 100 ? question.substring(0, 100) : question);

            return results;

        } catch (Exception e) {
            throw new RagServiceException("Failed to retrieve from Knowledge Base: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback invoked by Resilience4j when the {@code bedrock} circuit breaker is open
     * or when all retry attempts for {@link #retrieve} are exhausted.
     *
     * <p>Returns an empty list so the caller can handle degraded mode gracefully
     * (e.g. respond without KB context) rather than propagating a failure to the end user.
     * The circuit breaker will attempt a half-open probe after the configured
     * {@code waitDurationInOpenState} (30 s by default).
     *
     * <p><b>Note:</b> This method must not be annotated with {@code @CircuitBreaker} or
     * {@code @Retry} — Resilience4j requires fallback methods to be plain, undecorated methods
     * with the same signature as the guarded method plus a trailing {@link Throwable} parameter.
     *
     * @param question the original question that triggered the fallback (logged, truncated)
     * @param t        the exception or open-circuit signal that caused the fallback
     * @return an empty list, signalling to the caller that no KB context is available
     */
    public List<KnowledgeBaseRetrievalResult> retrieveFallback(String question, Throwable t) {
        log.warn("Bedrock KB unavailable, circuit breaker active. question={}, reason={}",
                question.length() > 100 ? question.substring(0, 100) : question,
                t.getMessage());
        return Collections.emptyList();
    }

    /**
     * Extracts plain text content from a list of Knowledge Base retrieval results.
     *
     * <p>Each {@link KnowledgeBaseRetrievalResult} wraps the chunk text inside a
     * {@code RetrievalResultContent} object. This method unwraps and flattens that
     * structure into a simple {@code List<String>} for use in prompt construction
     * or as the {@code sources} field in an API response.
     *
     * <p>Results with {@code null} content or blank text are silently filtered out.
     *
     * @param results raw retrieval results from {@link #retrieve}
     * @return list of non-blank chunk text strings; empty list if all results are unusable
     */
    public List<String> extractChunkTexts(List<KnowledgeBaseRetrievalResult> results) {
        return results.stream()
                .map(r -> r.content() != null ? r.content().text() : null)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Extracts chunk text paired with its relevance score, sorted by score descending.
     *
     * <p>The relevance score (0.0–1.0) reflects cosine similarity between the question
     * embedding and the chunk embedding in the Knowledge Base vector store. A score closer
     * to 1.0 indicates a stronger semantic match.
     *
     * <p>Results with {@code null} or blank content are excluded. Results with a {@code null}
     * score (uncommon but possible) are treated as {@code 0.0} so they sink to the bottom
     * of the ranked list rather than causing a {@link NullPointerException}.
     *
     * <p>This method is used to populate the {@code sources} field in {@code ChatResponse}
     * with citations ranked by confidence, giving the caller visibility into which chunks
     * most influenced the generated answer.
     *
     * @param results raw retrieval results from {@link #retrieve}
     * @return list of (chunkText, relevanceScore) entries sorted highest score first;
     *         empty list if no valid results are present
     */
    public List<Map.Entry<String, Double>> extractWithScores(List<KnowledgeBaseRetrievalResult> results) {
        return results.stream()
                .filter(r -> r.content() != null && r.content().text() != null && !r.content().text().isBlank())
                .map(r -> Map.entry(r.content().text(), r.score() != null ? r.score() : 0.0))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());
    }
}
