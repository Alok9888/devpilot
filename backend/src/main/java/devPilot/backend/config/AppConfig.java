package devPilot.backend.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    @Bean
    RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(requestFactory);
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
}