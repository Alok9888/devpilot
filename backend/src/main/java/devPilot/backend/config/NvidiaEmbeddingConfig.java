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
import org.springframework.beans.factory.ObjectProvider;

import com.openai.client.OpenAIClient;

/**
 * NVIDIA embedding configuration.
 * Activated when embedding.provider=nvidia.
 */
@Configuration
@ConditionalOnProperty(name = "embedding.provider", havingValue = "nvidia")
public class NvidiaEmbeddingConfig {

    @Bean(name = "nvOpenAIClient")
    OpenAIClient nvOpenAIClient(
            ObservationRegistry observationRegistry,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
            @Value("${spring.ai.openai.embedding.base-url:https://integrate.api.nvidia.com/v1}") String baseUrl,
            @Value("${spring.ai.openai.embedding.api-key:}") String apiKey,
            @Value("${spring.ai.openai.embedding.model:}") String modelName) {
        return org.springframework.ai.openai.setup.OpenAiSetup.setupSyncClient(
                baseUrl,
                apiKey,
                null,
                null,
                null,
                null,
                false,
                false,
                modelName,
                java.time.Duration.ofSeconds(60),
                0,
                null,
                null,
                observationRegistry,
                meterRegistry.getIfUnique(() -> null),
                java.util.List.of()
        );
    }

    @Bean(name = "nvEmbeddingModel")
    @Primary
    EmbeddingModel nvEmbeddingModel(OpenAIClient nvOpenAIClient,
            @Value("${spring.ai.openai.embedding.model:}") String modelName,
            @Value("${spring.ai.openai.embedding.dimensions:768}") Integer dimensions) {
        return new NvidiaEmbeddingModel(nvOpenAIClient, modelName, dimensions);
    }
}