package devPilot.backend.services.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
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
    private Executor chatExecutor;

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            UUID repositoryId,
            String repositoryName,
            String question) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        AtomicBoolean terminalSent = new AtomicBoolean(false);
        AtomicReference<String> stage = new AtomicReference<>("CHAT");
        emitter.onTimeout(() -> sendError(emitter, terminalSent, "Chat request timed out. Please try again."));
        emitter.onError(error -> {
            log.error("{} [SSE] transport error sessionId={}", now(), sessionId, error);
            sendError(emitter, terminalSent, "The chat connection failed. Please try again.");
        });

        try {
            chatExecutor.execute(() -> runChat(
                    emitter, terminalSent, stage, sessionId, savedUserMessage, repositoryId, repositoryName, question));
        } catch (Exception error) {
            log.error("{} [CHAT] could not schedule sessionId={}", now(), sessionId, error);
            sendError(emitter, terminalSent, "Chat is busy. Please try again shortly.");
        }
        return emitter;
    }

    private void runChat(
            SseEmitter emitter,
            AtomicBoolean terminalSent,
            AtomicReference<String> stage,
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            UUID repositoryId,
            String repositoryName,
            String question) {
        try {
            log.info("{} [CHAT] received sessionId={} content={}", now(), sessionId, question);
            emitter.send(SseEmitter.event().name("user_message").data(savedUserMessage));
            log.info("{} [SSE] sent user_message sessionId={}", now(), sessionId);

            log.info("{} [EMBED] start sessionId={}", now(), sessionId);
            stage.set("EMBED/VECTOR");
            log.info("{} [VECTOR] search start sessionId={}", now(), sessionId);
            CompletableFuture<RetrievedContext> retrieval = CompletableFuture.supplyAsync(
                    () -> codeContextRetriever.retrieve(repositoryId, question));
            RetrievedContext context;
            try {
                context = retrieval.get(EMBEDDING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception error) {
                retrieval.cancel(true);
                throw error;
            }
            log.info("{} [EMBED] done sessionId={} dims={}", now(), sessionId,
                    codeContextRetriever.embeddingDimensions());
            log.info("{} [VECTOR] received {} citations sessionId={}", now(), context.citations().size(), sessionId);

            String prompt = context.citations().isEmpty()
                    ? "No relevant code found, answering from general context.\n\n" + question
                    : chatPromptBuilder.userPrompt(context.contextText(), question);

            stage.set("GEMINI");
            log.info("{} [GEMINI] start sessionId={}", now(), sessionId);
            List<String> tokens = ChatClient.builder(chatModel).build().prompt()
                    .system(chatPromptBuilder.systemPrompt(repositoryName))
                    .user(prompt)
                    .stream()
                    .content()
                    .collectList()
                    .block(Duration.ofSeconds(GEMINI_TIMEOUT_SECONDS));
            log.info("{} [GEMINI] done sessionId={} tokenCount={}", now(), sessionId,
                    tokens == null ? 0 : tokens.size());

            stage.set("SSE");
            StringBuilder fullReply = new StringBuilder();
            if (context.citations().isEmpty()) {
                String noContextNotice = "No relevant code found, answering from general context.\n\n";
                fullReply.append(noContextNotice);
                emitter.send(SseEmitter.event().name("token")
                        .data(noContextNotice, MediaType.APPLICATION_JSON));
            }
            if (tokens != null) {
                for (String token : tokens) {
                    fullReply.append(token);
                    emitter.send(SseEmitter.event().name("token")
                            .data(token, MediaType.APPLICATION_JSON));
                }
            }
            if (fullReply.isEmpty()) {
                fullReply.append("I couldn't generate a reply. Please try again.");
                emitter.send(SseEmitter.event().name("token")
                        .data(fullReply.toString(), MediaType.APPLICATION_JSON));
            }

            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(context.citations()))
                    .build());
            emitter.send(SseEmitter.event().name("assistant_message").data(toMessageResponse(assistant)));
            if (terminalSent.compareAndSet(false, true)) {
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                log.info("{} [SSE] sent assistant done sessionId={}", now(), sessionId);
                emitter.complete();
            }
        } catch (Exception error) {
            Throwable cause = error instanceof ExecutionException && error.getCause() != null
                    ? error.getCause() : error;
            log.error("{} [{}] error sessionId={} type={}", now(), stage.get(), sessionId,
                    cause.getClass().getSimpleName(), cause);
            sendError(emitter, terminalSent, errorMessage(cause));
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

    private String errorMessage(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) {
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
