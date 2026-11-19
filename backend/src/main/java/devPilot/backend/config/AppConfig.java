package devPilot.backend.config;

import java.util.concurrent.Executor;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import com.google.genai.Client;
import com.openai.client.OpenAIClient;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;

@Configuration
@EnableAsync
public class AppConfig {
    // Gemini API key for last-resort chat fallback
    @Value("${spring.ai.google.genai.api-key:}")
    private String geminiApiKey;

    @Bean(name = "geminiFallbackChatModel")
    ChatModel geminiFallbackChatModel(
            ToolCallingManager toolCallingManager,
            RetryTemplate retryTemplate,
            ObservationRegistry observationRegistry) {
        // Create a GenAi client directly from the API key
        Client genAiClient = Client.builder()
                .apiKey(geminiApiKey)
                .build();

        return GoogleGenAiChatModel.builder()
                .genAiClient(genAiClient)
                .options(GoogleGenAiChatOptions.builder().model("gemini-2.5-flash").build())
                .toolCallingManager(toolCallingManager)
                .retryTemplate(retryTemplate)
                .observationRegistry(observationRegistry)
                .build();
    }

    @Bean
    RetryTemplate genAiSingleAttemptRetryTemplate() {
        // Quota fallback is model-to-model; repeating the same request burns latency and quota.
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }

    @Bean(name = "indexingExecutor")
    Executor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("index-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "chatExecutor")
    ThreadPoolTaskExecutor chatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("chat-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "ragExecutor")
    ThreadPoolTaskExecutor ragExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("rag-");
        executor.initialize();
        return executor;
    }

    // NVIDIA embedding model configuration with dedicated API key and base URL
    @Value("${spring.ai.openai.embedding.base-url:https://integrate.api.nvidia.com/v1}")
    private String nvEmbeddingBaseUrl;

    @Value("${spring.ai.openai.embedding.api-key:}")
    private String nvApiKey;

    @Value("${spring.ai.openai.embedding.model:}")
    private String nvEmbeddingModel;

    /**
     * Creates OpenAIClient for NVIDIA embeddings using Spring AI's OpenAiSetup.
     * This separates NV embedding auth from Groq chat auth.
     */
    @Bean(name = "nvOpenAIClient")
    OpenAIClient nvOpenAIClient(ObservationRegistry observationRegistry,
                                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry) {
        return org.springframework.ai.openai.setup.OpenAiSetup.setupSyncClient(
                nvEmbeddingBaseUrl,                              // baseUrl
                nvApiKey,                                        // apiKey
                null,                                            // credential
                null,                                            // azureDeploymentName
                null,                                            // azureOpenAiServiceVersion
                null,                                            // organizationId
                false,                                           // isAzure
                false,                                           // isGitHubModels
                nvEmbeddingModel,                              // modelName
                Duration.ofSeconds(60),                        // timeout
                0,                                               // maxRetries
                null,                                            // proxy
                null,                                            // customHeaders
                observationRegistry,                             // observationRegistry
                meterRegistry.getIfUnique(() -> null),         // meterRegistry
                List.of()                                        // httpClientCustomizers
        );
    }

    /**
     * NVIDIA embedding model with dedicated API key.
     * This avoids sharing the Groq API key for NV embeddings.
     */
    @Bean(name = "nvEmbeddingModel")
    EmbeddingModel nvEmbeddingModel(OpenAIClient nvOpenAIClient) {
        return OpenAiEmbeddingModel.builder()
                .openAiClient(nvOpenAIClient)
                .options(OpenAiEmbeddingOptions.builder()
                        .model(nvEmbeddingModel)
                        .dimensions(768)
                        .build())
                .build();
    }

    /**
     * Custom PGVector store with NV embedding model.
     * This ensures embeddings go through NVIDIA's endpoint with its own API key,
     * not the Groq API key.
     */
    @Bean
    VectorStore pgVectorStore(
            JdbcTemplate jdbcTemplate,
            EmbeddingModel nvEmbeddingModel,
            ObservationRegistry observationRegistry) {
        return PgVectorStore.builder(jdbcTemplate, nvEmbeddingModel)
                .dimensions(768)
                .vectorTableName("devpilot_vectors_nv_768")
                .indexType(PgIndexType.HNSW)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .initializeSchema(true)
                .build();
    }
}