# Bedrock RAG Chat

Production-grade RAG Q&A backend built with Amazon Bedrock and Spring Boot. Phase 1 — semantic retrieval with guardrails and resilience patterns. Chat memory and streaming coming in Phase 2.

## Features Completed

- RAG via Bedrock Knowledge Bases (Retrieve API)
- Guardrails (PII masking, prompt attacks, denied topics)
- Circuit breaker with model fallback (Resilience4j)
- Per-request token cost tracking
- Request tracing (MDC traceId)
- Audit trail (DynamoDB)

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/rag/ask` | One-shot Q&A with full guardrail pipeline |
| `GET`  | `/api/health/guardrail` | Guardrail availability probe |

### POST /api/rag/ask

```json
// Request
{ "message": "What channels are included in the sports package?" }

// Response
{
  "answer": "The sports package includes...",
  "sources": ["chunk text 1", "chunk text 2"],
  "sessionId": "abc-123",
  "blocked": false,
  "blockReason": null,
  "tokenUsage": { "inputTokens": 312, "outputTokens": 87, "estimatedCostUsd": 0.000039 }
}
```

## Coming in Phase 2

- `POST /api/chat/{sessionId}` — multi-turn conversation with DynamoDB history
- Streaming responses via SSE
- Rate limiting

## Configuration

Fill in `src/main/resources/application.yml`:

```yaml
aws:
  region: us-east-1
bedrock:
  model:
    primary: amazon.nova-lite-v1:0
    fallback: amazon.nova-micro-v1:0
  knowledgebase:
    id: <your-kb-id>
  guardrail:
    id: <your-guardrail-id>
    version: "1"
dynamodb:
  table:
    chat-history: bedrock-rag-chat-history
    audit-log: bedrock-rag-audit-log
```

Credentials are resolved via the AWS Default Credentials Provider chain (env vars, `~/.aws/credentials`, or IAM role).
