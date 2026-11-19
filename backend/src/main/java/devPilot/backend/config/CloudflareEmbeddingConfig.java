package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;

import devPilot.backend.services.embedding.CloudflareEmbeddingService;

/**
 * Cloudflare Workers AI embedding configuration.
 * Activated when embedding.provider=cloudflare.
 */
@Configuration
@ConditionalOnProperty(name = "EMBEDDING_PROVIDER", havingValue = "cloudflare", matchIfMissing = true)
public class CloudflareEmbeddingConfig {

    @Bean
    CloudflareEmbeddingService cloudflareEmbeddingService(
            @Value("${CLOUDFLARE_ACCOUNT_ID:}") String account_id,
            @Value("${CLOUDFLARE_API_TOKEN:}") String api_token,
            JdbcTemplate jdbcTemplate) {
        return new CloudflareEmbeddingService(account_id, api_token, jdbcTemplate);
    }

    @Bean
    EmbeddingModel embeddingModel(CloudflareEmbeddingService cloudflareEmbeddingService) {
        return cloudflareEmbeddingService;
    }

    @Bean
    VectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS devpilot_vectors_cf_384 (id UUID PRIMARY KEY, repo_id UUID, chunk_id TEXT, embedding vector(384), content TEXT)");
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
            .dimensions(384)
            .vectorTableName("devpilot_vectors_cf_384")
            .initializeSchema(false)
            .build();
    }
}
