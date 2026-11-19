package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.observation.ObservationRegistry;

import devPilot.backend.services.embedding.CloudflareEmbeddingService;

/**
 * Cloudflare Workers AI embedding configuration.
 * Activated when embedding.provider=cloudflare.
 */
@Configuration
@ConditionalOnProperty(name = "embedding.provider", havingValue = "cloudflare", matchIfMissing = true)
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
}