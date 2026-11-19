package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import devPilot.backend.services.embedding.CloudflareEmbeddingService;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class EmbeddingConfig {
    @Bean
    CloudflareEmbeddingService cloudflareEmbeddingService(@Value("${CLOUDFLARE_ACCOUNT_ID:}") String accountId, @Value("${CLOUDFLARE_API_TOKEN:}") String token, JdbcTemplate jdbcTemplate) {
        return new CloudflareEmbeddingService(accountId, token, jdbcTemplate);
    }

    @Bean
    EmbeddingModel embeddingModel(CloudflareEmbeddingService service) {
        return service;
    }

    @Bean
    VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .dimensions(384)
            .vectorTableName("devpilot_vectors_cf_384")
            .initializeSchema(false)
            .build();
    }
}
