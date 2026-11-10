package devPilot.backend.services.ai;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/** Secondary, model-matched vector space used when Gemini's primary embedding quota is exhausted. */
@Component
public class FallbackEmbeddingVectorStore {
    private final VectorStore store;

    public FallbackEmbeddingVectorStore(JdbcTemplate jdbcTemplate,
            GoogleGenAiEmbeddingConnectionDetails connectionDetails, RetryTemplate retryTemplate) {
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model("text-embedding-004").dimensions(768)
                .taskType(GoogleGenAiTextEmbeddingOptions.TaskType.SEMANTIC_SIMILARITY).build();
        var model = new GoogleGenAiTextEmbeddingModel(connectionDetails, options, retryTemplate);
        this.store = PgVectorStore.builder(jdbcTemplate, model)
                .vectorTableName("devpilot_vectors_text_embedding_004")
                .dimensions(768).initializeSchema(true).build();
    }

    @PostConstruct
    void initialize() { ((PgVectorStore) store).afterPropertiesSet(); }

    public void add(List<Document> documents) { if (!documents.isEmpty()) store.add(documents); }
    public void delete(Filter.Expression filter) { store.delete(filter); }
    public List<Document> similaritySearch(SearchRequest request) { return store.similaritySearch(request); }
}
