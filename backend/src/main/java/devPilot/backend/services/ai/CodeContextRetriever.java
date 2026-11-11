package devPilot.backend.services.ai;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CodeContextRetriever {
    private static final String NO_MATCHES = "(no matching code chunks found)";

    private final VectorStore vectorStore;
    private final CitationMapper citationMapper;
    private final JdbcTemplate jdbcTemplate;

    public int embeddingDimensions() {
        return 768;
    }

    public RetrievedContext retrieve(UUID repositoryId, String question) {
        log.info("{} [VECTOR] search start repo={} queryLength={}", Instant.now(), repositoryId, question.length());
        var filter = new FilterExpressionBuilder()
                .eq(RagSettings.METADATA_REPO_ID, repositoryId.toString())
                .build();

        var search = SearchRequest.builder()
                .query(question)
                .topK(RagSettings.TOP_K_CHUNKS)
                .filterExpression(filter)
                .build();

        jdbcTemplate.setQueryTimeout(10);
        java.util.List<Document> documents;
        try {
            documents = retryVectorSearch(search);
        } catch (RuntimeException e) {
            log.error("{} [EMBED] vector search failed after retries repo={}: {}", Instant.now(), repositoryId, e.getMessage());
            documents = java.util.List.of();
        }
        if (documents == null) {
            documents = java.util.List.of();
        }
        log.info("{} [VECTOR] found {} results repo={}", Instant.now(), documents.size(), repositoryId);

        var citations = documents.stream()
                .map(citationMapper::fromDocument)
                .distinct()
                .toList();

        var contextText = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));

        if (contextText.isBlank()) {
            contextText = NO_MATCHES;
        }

        return new RetrievedContext(citations, contextText);
    }

    private java.util.List<Document> retryVectorSearch(SearchRequest search) {
        int[] delays = {2000, 5000, 10000};
        RuntimeException lastError = null;

        for (int attempt = 0; attempt <= delays.length; attempt++) {
            try {
                return vectorStore.similaritySearch(search);
            } catch (RuntimeException e) {
                lastError = e;
                if (!isRetryableError(e) || attempt >= delays.length) {
                    break;
                }
                log.warn("{} [EMBED] vector search failed (attempt {}/{}); retrying in {}ms: {}",
                        Instant.now(), attempt + 1, delays.length + 1, delays[attempt], e.getMessage());
                try {
                    Thread.sleep(delays[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during embedding retry", ie);
                }
            }
        }
        throw lastError != null ? lastError : new RuntimeException("Vector search failed after retries");
    }

    private static boolean isRetryableError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("429") || message.contains("503")
                    || message.contains("quota_exceeded") || message.contains("resource_exhausted")
                    || message.contains("service_unavailable")) {
                return true;
            }
        }
        return false;
    }
}