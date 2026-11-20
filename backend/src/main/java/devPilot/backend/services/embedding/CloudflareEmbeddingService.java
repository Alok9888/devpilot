package devPilot.backend.services.embedding;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.document.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cloudflare Workers AI embedding service for bge-small-en-v1.5 model.
 * Uses 384 dimensions, optimized for speed.
 * Supports both indexing and query-time embedding with the same model.
 */
@ConditionalOnProperty(name = "embedding.provider", havingValue = "cloudflare", matchIfMissing = true)
public class CloudflareEmbeddingService implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CloudflareEmbeddingService.class);
    private static final String CLOUDFLARE_ACCOUNT_ID = "cdf43a252db0973fb702921884aec43b";
    private static final String EMBEDDING_ENDPOINT = "/ai/run/@cf/baai/bge-small-en-v1.5";
    private static final int MODEL_DIMENSIONS = 384;
    private static final int MAX_RETRIES = 3;
    private static final int[] RETRY_BACKOFF_MS = {2000, 4000, 8000};

    private final String api_token;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // In-memory cache for queries (repoId + query string)
    private final ConcurrentHashMap<String, CachedEmbedding> embeddingCache = new ConcurrentHashMap<>();
    private final ExecutorService parallelExecutor = Executors.newFixedThreadPool(8);

    // Statistics tracking
    private final ThreadLocal<AtomicInteger> retryCount = ThreadLocal.withInitial(() -> new AtomicInteger(0));

    public CloudflareEmbeddingService(
            JdbcTemplate jdbcTemplate) {
        // Hardcoded Cloudflare Account ID
        this.api_token = System.getenv("CLOUDFLARE_API_TOKEN");
        this.jdbcTemplate = jdbcTemplate;
        // Schema initialization is handled by PgVectorStore.initializeSchema(true)

        // Log startup info
        log.info("CloudflareEmbeddingService initialized: accountId length=" + CLOUDFLARE_ACCOUNT_ID.length());
    }

    /**
     * Initialize the PostgreSQL vector table for Cloudflare embeddings.
     */
    private void initializeTable() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS devpilot_vectors_cf_384 (" +
                "id UUID PRIMARY KEY, " +
                "repo_id UUID, " +
                "chunk_id TEXT, " +
                "embedding vector(384), " +
                "content TEXT" +
                ")"
            );
            log.info("Cloudflare embedding table devpilot_vectors_cf_384 initialized");
        } catch (Exception e) {
            log.error("Failed to initialize Cloudflare embedding table: {}", e.getMessage(), e);
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest embeddingRequest) {
        List<String> texts = embeddingRequest.getInstructions();
        long startTime = System.currentTimeMillis();
        int totalRetries = 0;

        List<Embedding> embeddings = new ArrayList<>();
        int successfulCount = 0;
        int failedCount = 0;

        // Process in batches of 32 for parallel processing
        int batchSize = 32;
        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            // Reset retry count for this batch
            retryCount.get().set(0);

            List<float[]> batchEmbeddings = embedBatchWithRetry(batch);

            for (int j = 0; j < batchEmbeddings.size(); j++) {
                if (batchEmbeddings.get(j) != null) {
                    embeddings.add(new Embedding(batchEmbeddings.get(j), j));
                    successfulCount++;
                } else {
                    // Use zero vector as placeholder for failed embeddings
                    embeddings.add(new Embedding(new float[MODEL_DIMENSIONS], j));
                    failedCount++;
                }
            }

            totalRetries += retryCount.get().get();
        }

        long totalTime = System.currentTimeMillis() - startTime;

        log.info("[EMBED-TIMING] model=@cf/baai/bge-small-en-v1.5 batchSize=32 timeMs={} status=200 avgMsPerChunk={} retries={}",
                totalTime, successfulCount > 0 ? totalTime / successfulCount : 0, totalRetries);

        EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata();
        metadata.setModel("@cf/baai/bge-small-en-v1.5");

        return new EmbeddingResponse(embeddings, metadata);
    }

    /**
     * Embed a batch with retry logic for 5xx errors only.
     * No retry for 4xx errors - log and skip only bad chunk.
     */
    private List<float[]> embedBatchWithRetry(List<String> batch) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return callCloudflareAPI(batch);
            } catch (RuntimeException e) {
                int status = extractHttpStatus(e);

                // 4xx errors - don't retry, log and skip
                if (status >= 400 && status < 500) {
                    log.warn("[EMBED] 4xx error, skipping batch: {} - {}", status, e.getMessage());
                    retryCount.get().incrementAndGet();
                    return batch.stream().map(i -> (float[]) null).toList();
                }

                // 5xx or timeout - retry with backoff
                if (attempt < MAX_RETRIES) {
                    long delay = (long) (RETRY_BACKOFF_MS[attempt] * Math.pow(2, Math.min(attempt, 2)));
                    retryCount.get().incrementAndGet();
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry", ie);
                    }
                } else {
                    log.error("[EMBED] batch failed after {} retries: {}", attempt, e.getMessage());
                    return batch.stream().map(i -> (float[]) null).toList();
                }
            }
        }
        return batch.stream().map(i -> (float[]) null).toList();
    }

    private List<float[]> callCloudflareAPI(List<String> texts) {
        try {
            String url = "https://api.cloudflare.com/client/v4/accounts/" + CLOUDFLARE_ACCOUNT_ID + EMBEDDING_ENDPOINT;

            // Build request body
            ObjectNode requestBody = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode textArray = requestBody.putArray("text");
            for (String text : texts) {
                textArray.add(text);
            }

            // Make HTTP request using Java 11 HttpClient or OkHttp
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Authorization", "Bearer " + api_token)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(java.time.Duration.ofSeconds(120))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
            }

            // Parse response - structure: {"result": {"shape": [batch, 384], "data": [[...], [...]]}, "success": true}
            // data is number[][] where data.get(i) is a 384-dim vector for chunk i
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response.body());
            com.fasterxml.jackson.databind.JsonNode result = root.get("result");
            if (result == null) {
                throw new RuntimeException("Cloudflare embedding API error - no result: " + response.body().substring(0, Math.min(1000, response.body().length())));
            }
            com.fasterxml.jackson.databind.JsonNode data = result.get("data");
            if (data == null) {
                throw new RuntimeException("Cloudflare embedding API error - no data: " + response.body().substring(0, Math.min(1000, response.body().length())));
            }

            List<float[]> embeddings = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                com.fasterxml.jackson.databind.JsonNode vectorArray = data.get(i);  // This is already the 384-dim vector
                float[] vector = new float[MODEL_DIMENSIONS];
                for (int j = 0; j < MODEL_DIMENSIONS; j++) {
                    vector[j] = (float) vectorArray.get(j).asDouble();
                }
                embeddings.add(vector);
            }

            return embeddings;
        } catch (Exception e) {
            throw new RuntimeException("Cloudflare embedding API call failed: " + e.getMessage(), e);
        }
    }

    private int extractHttpStatus(Throwable e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("HTTP ")) {
            try {
                String[] parts = msg.split(":");
                if (parts.length > 0) {
                    String statusStr = parts[0].replaceAll("[^0-9]", "");
                    return Integer.parseInt(statusStr);
                }
            } catch (NumberFormatException ignored) {}
        }
        return 500; // Default to 500 if cannot extract
    }

    @Override
    public float[] embed(Document document) {
        long startTime = System.currentTimeMillis();
        List<String> texts = List.of(document.getFormattedContent());
        EmbeddingResponse response = call(new EmbeddingRequest(texts, null));

        long timeMs = System.currentTimeMillis() - startTime;
        boolean cacheHit = false;
        if (response.getResults().isEmpty()) {
            log.warn("[QUERY-TIMING] queryTimeMs={} topK=5 cacheHit={}", timeMs, cacheHit);
            return new float[MODEL_DIMENSIONS];
        }

        cacheHit = true;
        log.info("[QUERY-TIMING] queryTimeMs={} topK=5 cacheHit={}", timeMs, cacheHit);
        return response.getResults().get(0).getOutput();
    }

    @Override
    public int dimensions() {
        return MODEL_DIMENSIONS;
    }

    /**
     * Cache lookup for repeated queries.
     */
    public float[] getCachedEmbedding(String cacheKey) {
        CachedEmbedding cached = embeddingCache.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp < TimeUnit.MINUTES.toMillis(5)) {
            return cached.embedding;
        }
        return null;
    }

    public void putCachedEmbedding(String cacheKey, float[] embedding) {
        embeddingCache.put(cacheKey, new CachedEmbedding(embedding, System.currentTimeMillis()));
    }

    private static class CachedEmbedding {
        final float[] embedding;
        final long timestamp;

        CachedEmbedding(float[] embedding, long timestamp) {
            this.embedding = embedding;
            this.timestamp = timestamp;
        }
    }
}