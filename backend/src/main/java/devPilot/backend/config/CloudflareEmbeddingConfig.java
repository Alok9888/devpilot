package devPilot.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;

import devPilot.backend.services.embedding.CloudflareEmbeddingService;

/**
 * Cloudflare Workers AI embedding configuration.
 * Activated when embedding.provider=cloudflare.
 */
@Configuration
@ConditionalOnProperty(name = "embedding.provider", havingValue = "cloudflare")
public class CloudflareEmbeddingConfig {

    @Bean
    CloudflareEmbeddingService cloudflareEmbeddingService(
            @Value("${CLOUDFLARE_ACCOUNT_ID:}") String account_id,
            @Value("${CLOUDFLARE_API_TOKEN:}") String api_token,
            JdbcTemplate jdbcTemplate) {
        return new CloudflareEmbeddingService(account_id, api_token, jdbcTemplate);
    }

    @Bean(name = "nvEmbeddingModel")
    EmbeddingModel nvEmbeddingModel(CloudflareEmbeddingService cloudflareEmbeddingService) {
        // Return the Cloudflare service as the embedding model
        return cloudflareEmbeddingService;
    }

    @Bean
    VectorStore pgVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel cloudflareEmbeddingService,
            ObservationRegistry observationRegistry) {
        return PgVectorStore.builder(jdbcTemplate, cloudflareEmbeddingService)
                .dimensions(384)
                .vectorTableName("devpilot_vectors_cf_384")
                .indexType(PgIndexType.HNSW)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(true)
                .build();
    }
}