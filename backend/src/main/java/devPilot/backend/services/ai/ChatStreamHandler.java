package devPilot.backend.services.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Owns the complete chat request and guarantees a terminal SSE event on every path. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private static final long EMBEDDING_TIMEOUT_SECONDS = 15;
    private static final long GEMINI_TIMEOUT_SECONDS = 60;

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final CodeContextRetriever codeContextRetriever;
    private final ChatPromptBuilder chatPromptBuilder;

    @Value("${app.gemini.chat.models}")
    private String configuredChatModels;

    @Autowired
    @Qualifier("chatExecutor")
    private ThreadPoolTaskExecutor chatExecutor;

    @Autowired
    @Qualifier("ragExecutor")
    private ThreadPoolTaskExecutor ragExecutor;

    public SseEmitter stream(
            UUID requestedSessionId,
            String question,
            Supplier<ChatStreamContext> contextSupplier) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        AtomicBoolean terminalSent = new AtomicBoolean(false);
        AtomicBoolean cancelWorker = new AtomicBoolean(false);
        AtomicReference<Future<?>> workerTask = new AtomicReference<>();
        AtomicReference<String> stage = new AtomicReference<>("SESSION");
        log.info("{} [CHAT] received sessionId={} content={}", now(), requestedSessionId, question);
        emitter.onTimeout(() -> {
            log.error("{} [SSE] timeout sessionId={} stage={}", now(), requestedSessionId, stage.get());
            sendError(emitter, terminalSent, "Chat request timed out. Please try again.");
            cancelWorker.set(true);
            cancelTask(workerTask.get());
        });
        emitter.onError(error -> {
            log.error("{} [SSE] transport error sessionId={}", now(), requestedSessionId, error);
            sendError(emitter, terminalSent, "The chat connection failed. Please try again.");
            cancelWorker.set(true);
            cancelTask(workerTask.get());
        });
        emitter.onCompletion(() -> {
            if (!terminalSent.get()) {
                cancelWorker.set(true);
                cancelTask(workerTask.get());
            }
        });

        try {
            // Open the SSE response before database lookup or RAG work begins.
            log.info("{} [SSE] sending stream-open sessionId={}", now(), requestedSessionId);
            emitter.send(SseEmitter.event().comment("connected"));
            Future<?> task = chatExecutor.submit(() -> runChat(
                    emitter, terminalSent, stage, requestedSessionId, question, contextSupplier));
            workerTask.set(task);
            if (cancelWorker.get()) {
                cancelTask(task);
            }
        } catch (Exception error) {
            log.error("{} [CHAT] could not schedule sessionId={}", now(), requestedSessionId, error);
            sendError(emitter, terminalSent, "Chat is busy. Please try again shortly.");
        }
        return emitter;
    }

    private void runChat(
            SseEmitter emitter,
            AtomicBoolean terminalSent,
            AtomicReference<String> stage,
            UUID requestedSessionId,
            String question,
            Supplier<ChatStreamContext> contextSupplier) {
        UUID sessionId = requestedSessionId;
        try {
            stage.set("SESSION");
            ChatStreamContext streamContext = contextSupplier.get();
            sessionId = streamContext.sessionId();

            log.info("{} [SSE] sending user_message sessionId={}", now(), sessionId);
            emitter.send(SseEmitter.event().name("user_message").data(streamContext.userMessage()));
            log.info("{} [SSE] sent user_message sessionId={}", now(), sessionId);

            stage.set("EMBED/VECTOR");
            log.info("{} [EMBED] start sessionId={}", now(), sessionId);
            log.info("{} [VECTOR] search start sessionId={}", now(), sessionId);
            Future<RetrievedContext> retrieval = ragExecutor.submit(
                    () -> codeContextRetriever.retrieve(streamContext.repositoryId(), question));
            RetrievedContext retrievedContext;
            try {
                retrievedContext = retrieval.get(EMBEDDING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception error) {
                retrieval.cancel(true);
                throw error;
            }
            log.info("{} [EMBED] done sessionId={} dims={}", now(), sessionId,
                    codeContextRetriever.embeddingDimensions());
            log.info("{} [VECTOR] received {} citations sessionId={}", now(), retrievedContext.citations().size(), sessionId);

            String prompt = retrievedContext.citations().isEmpty()
                    ? "No relevant code found, answering from general context.\n\n" + question
                    : chatPromptBuilder.userPrompt(retrievedContext.contextText(), question);

            stage.set("GEMINI");
            List<String> triedModels = new ArrayList<>();
            List<String> tokens = null;
            String successfulModel = null;
            Throwable lastModelError = null;
            boolean sawQuotaFailure = false;
            List<String> models = Arrays.stream(configuredChatModels.split(","))
                    .map(String::trim).filter(model -> !model.isEmpty()).toList();
            if (models.isEmpty()) models = List.of("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash");
            for (String model : models) {
                triedModels.add(model);
                try {
                    log.info("{} [GEMINI] start sessionId={} model={}", now(), sessionId, model);
                    tokens = ChatClient.builder(chatModel).build().prompt()
                            .options(ChatOptions.builder().model(model))
                            .system(chatPromptBuilder.systemPrompt(streamContext.repositoryName()))
                            .user(prompt).stream().content().collectList()
                            .block(Duration.ofSeconds(GEMINI_TIMEOUT_SECONDS));
                    successfulModel = model;
                    log.info("{} [GEMINI] success model={} sessionId={} tokenCount={}", now(), model, sessionId,
                            tokens == null ? 0 : tokens.size());
                    break;
                } catch (Exception modelError) {
                    lastModelError = unwrap(modelError);
                    int status = httpStatus(lastModelError);
                    log.warn("{} [GEMINI] model={} failed status={} sessionId={}", now(), model, status, sessionId, modelError);
                    if (status == 401 || status == 404 || status == 400) {
                        log.error("{} [GEMINI] fatal {} model={} - not trying fallback", now(), status, model);
                        throw new GeminiStreamException("GEMINI_FATAL", model, triedModels, errorMessage(lastModelError), null, 0, lastModelError);
                    }
                    if (status != 429 && status != 503) throw modelError;
                    sawQuotaFailure |= status == 429;
                    if (triedModels.size() < models.size()) {
                        log.warn("{} [GEMINI] model={} failed {}, trying next fallback", now(), model, status);
                    }
                }
            }
            if (successfulModel == null) {
                String code = sawQuotaFailure ? "QUOTA_EXCEEDED" : "MODEL_OVERLOADED";
                String message = sawQuotaFailure
                        ? "All Gemini models quota exceeded (tried " + String.join(", ", triedModels) + "). Please retry in 15h 45m or add billing."
                        : "All Gemini models are overloaded. Please retry shortly.";
                throw new GeminiStreamException(code, "all", triedModels, message,
                        sawQuotaFailure ? "15h45m" : null, sawQuotaFailure ? 56715 : 0, lastModelError);
            }

            stage.set("SSE");
            StringBuilder fullReply = new StringBuilder();
            if (retrievedContext.citations().isEmpty()) {
                String noContextNotice = "No relevant code found, answering from general context.\n\n";
                fullReply.append(noContextNotice);
                log.info("{} [SSE] sending no-context token sessionId={}", now(), sessionId);
                emitter.send(SseEmitter.event().name("token").data(noContextNotice));
            }
            if (tokens != null) {
                for (String token : tokens) {
                    fullReply.append(token);
                    log.info("{} [SSE] sending token sessionId={} length={}", now(), sessionId, token.length());
                    emitter.send(SseEmitter.event().name("token").data(token));
                }
            }
            if (fullReply.isEmpty()) {
                fullReply.append("I couldn't generate a reply. Please try again.");
                log.info("{} [SSE] sending fallback token sessionId={}", now(), sessionId);
                emitter.send(SseEmitter.event().name("token").data(fullReply.toString()));
            }

            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(retrievedContext.citations()))
                    .build());
            // Include the model in persisted assistant content metadata is handled client side via SSE metadata.
            emitter.send(SseEmitter.event().name("model").data(Map.of("model", successfulModel,
                    "fallback", !models.isEmpty() && !successfulModel.equals(models.get(0)))));
            log.info("{} [SSE] sending assistant_message sessionId={}", now(), sessionId);
            emitter.send(SseEmitter.event().name("assistant_message").data(toMessageResponse(assistant)));
            sendDone(emitter, terminalSent, sessionId);
        } catch (Exception error) {
            Throwable cause = error instanceof ExecutionException && error.getCause() != null
                    ? error.getCause() : error;
            log.error("{} [{}] error sessionId={} type={}", now(), stage.get(), sessionId,
                    cause.getClass().getSimpleName(), cause);
            if (cause instanceof GeminiStreamException geminiError) {
                sendError(emitter, terminalSent, geminiError);
            } else {
                sendError(emitter, terminalSent, errorMessage(cause));
            }
        } finally {
            if (!terminalSent.get()) {
                sendError(emitter, terminalSent, "Chat ended without a terminal event. Please try again.");
            }
        }
    }

    private void sendDone(SseEmitter emitter, AtomicBoolean terminalSent, UUID sessionId) throws Exception {
        if (!terminalSent.compareAndSet(false, true)) {
            return;
        }
        try {
            log.info("{} [SSE] sending done sessionId={}", now(), sessionId);
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            log.info("{} [SSE] sent assistant done sessionId={}", now(), sessionId);
            emitter.complete();
        } catch (Exception error) {
            log.error("{} [SSE] failed to send done sessionId={}", now(), sessionId, error);
            emitter.completeWithError(error);
            throw error;
        }
    }

    private void sendError(SseEmitter emitter, AtomicBoolean terminalSent, String message) {
        if (!terminalSent.compareAndSet(false, true)) {
            return;
        }
        String safeMessage = message == null || message.isBlank() ? "Chat failed. Please try again." : message;
        if (safeMessage.length() > 400) {
            safeMessage = safeMessage.substring(0, 400);
        }
        try {
            log.info("{} [SSE] sending error message={}", now(), safeMessage);
            emitter.send(SseEmitter.event().name("error")
                    .data(Map.of("type", "error", "message", safeMessage), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            log.info("{} [SSE] sent error done message={}", now(), safeMessage);
            emitter.complete();
        } catch (Exception sendError) {
            log.error("{} [SSE] failed to send terminal error", now(), sendError);
            emitter.completeWithError(sendError);
        }
    }

    private void cancelTask(Future<?> task) {
        if (task != null) {
            task.cancel(true);
        }
    }

    private String errorMessage(Throwable error) {
        if (error instanceof TimeoutException) {
            return "Chat timed out while retrieving code or waiting for Gemini. Please retry.";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current;
    }

    private static int httpStatus(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            try {
                Object code = current.getClass().getMethod("code").invoke(current);
                if (code instanceof Number number) return number.intValue();
            } catch (ReflectiveOperationException ignored) { }
            try {
                Object status = current.getClass().getMethod("statusCode").invoke(current);
                if (status instanceof Number number) return number.intValue();
            } catch (ReflectiveOperationException ignored) { }
        }
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("429") || message.contains("quota_exceeded")) return 429;
        if (message.contains("503") || message.contains("model_overloaded")) return 503;
        if (message.contains("401")) return 401;
        if (message.contains("404")) return 404;
        if (message.contains("400")) return 400;
        return 0;
    }

    private void sendError(SseEmitter emitter, AtomicBoolean terminalSent, GeminiStreamException error) {
        if (!terminalSent.compareAndSet(false, true)) return;
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "error"); payload.put("code", error.code); payload.put("model", error.model);
        payload.put("triedModels", error.triedModels); payload.put("message", error.getMessage());
        if (error.retryAfter != null) payload.put("retryAfter", error.retryAfter);
        if (error.retryAfterSeconds > 0) payload.put("retryAfterSeconds", error.retryAfterSeconds);
        try {
            log.info("{} [SSE] sent assistant error code={} model={}", now(), error.code, error.model);
            emitter.send(SseEmitter.event().name("error").data(payload, MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception sendError) { emitter.completeWithError(sendError); }
    }

    private static final class GeminiStreamException extends RuntimeException {
        final String code, model, retryAfter;
        final List<String> triedModels;
        final long retryAfterSeconds;
        GeminiStreamException(String code, String model, List<String> triedModels, String message,
                String retryAfter, long retryAfterSeconds, Throwable cause) {
            super(message, cause); this.code = code; this.model = model; this.triedModels = List.copyOf(triedModels);
            this.retryAfter = retryAfter; this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(), message.getRole(), message.getContent(),
                citationMapper.fromJson(message.getCitations()), message.getCreatedAt());
    }

    private static String now() {
        return Instant.now().toString();
    }
}
