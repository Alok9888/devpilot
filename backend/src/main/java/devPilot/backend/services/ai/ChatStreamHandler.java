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
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
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
            log.info("{} [GEMINI] start sessionId={} model=gemini-3.8-flash", now(), sessionId);
            List<String> tokens = ChatClient.builder(chatModel).build().prompt()
                    .system(chatPromptBuilder.systemPrompt(streamContext.repositoryName()))
                    .user(prompt)
                    .stream()
                    .content()
                    .collectList()
                    .block(Duration.ofSeconds(GEMINI_TIMEOUT_SECONDS));
            log.info("{} [GEMINI] done sessionId={} tokenCount={}", now(), sessionId,
                    tokens == null ? 0 : tokens.size());

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
            log.info("{} [SSE] sending assistant_message sessionId={}", now(), sessionId);
            emitter.send(SseEmitter.event().name("assistant_message").data(toMessageResponse(assistant)));
            sendDone(emitter, terminalSent, sessionId);
        } catch (Exception error) {
            Throwable cause = error instanceof ExecutionException && error.getCause() != null
                    ? error.getCause() : error;
            log.error("{} [{}] error sessionId={} type={}", now(), stage.get(), sessionId,
                    cause.getClass().getSimpleName(), cause);
            sendError(emitter, terminalSent, errorMessage(cause));
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

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(), message.getRole(), message.getContent(),
                citationMapper.fromJson(message.getCitations()), message.getCreatedAt());
    }

    private static String now() {
        return Instant.now().toString();
    }
}
