package devPilot.backend.services.ai;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.ai.embedding.EmbeddingModel;
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
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;

    public int embeddingDimensions() {
        return embeddingModel.dimensions();
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

        // PgVectorStore uses this shared JdbcTemplate for its SELECT. Keep database-side
        // vector searches bounded independently from the embedding request budget.
        jdbcTemplate.setQueryTimeout(10);
        var documents = vectorStore.similaritySearch(search);
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
}
