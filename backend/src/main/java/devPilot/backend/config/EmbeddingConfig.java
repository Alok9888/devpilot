package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import devPilot.backend.services.embedding.CloudflareEmbeddingService;

@Configuration
public class EmbeddingConfig {
    @Bean
    EmbeddingModel cloudflareEmbeddingService(JdbcTemplate jdbcOperations) {
        return new CloudflareEmbeddingService(jdbcOperations);
    }

    @Bean
    VectorStore vectorStore(JdbcTemplate jdbcOperations, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcOperations, embeddingModel)
            .dimensions(384)
            .vectorTableName("devpilot_vectors_cf_384")
            .initializeSchema(false)
            .build();
    }
}