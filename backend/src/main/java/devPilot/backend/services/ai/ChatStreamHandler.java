package devPilot.backend.services.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Runs retrieval and Gemini generation in the background and streams tokens over SSE.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

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
        StringBuilder fullReply = new StringBuilder();

        chatExecutor.execute(() -> {
            try {
                log.info("[CHAT] received session={} repo={}", sessionId, repositoryId);
                log.info("[STREAM] opening SSE session={}", sessionId);
                emitter.send(SseEmitter.event().name("user_message").data(savedUserMessage));
                log.info("[EMBED] query embedding session={}", sessionId);
                var context = CompletableFuture
                        .supplyAsync(() -> codeContextRetriever.retrieve(repositoryId, question))
                        .get(30, TimeUnit.SECONDS);
                log.info("[VECTOR] found {} results session={}", context.citations().size(), sessionId);
                String prompt = context.citations().isEmpty()
                        ? "No relevant code found, answering from general context.\n\n" + question
                        : chatPromptBuilder.userPrompt(context.contextText(), question);
                log.info("[GEMINI] calling chat session={}", sessionId);
                ChatClient.builder(chatModel).build().prompt()
                        .system(chatPromptBuilder.systemPrompt(repositoryName))
                        .user(prompt).stream().content()
                        .timeout(Duration.ofSeconds(60))
                        .subscribe(token -> appendToken(emitter, fullReply, token),
                                err -> failStream(emitter, err),
                                () -> completeStream(emitter, sessionId, fullReply, context.citations()));
            } catch (Exception ex) {
                failStream(emitter, ex);
            }
        });

        return emitter;
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            log.debug("[STREAM] sending token");
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(token, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void failStream(SseEmitter emitter, Throwable error) {
        log.error("Chat stream error", error);

        if (error instanceof ExecutionException && error.getCause() != null) {
            error = error.getCause();
        }

        String message = error instanceof java.util.concurrent.TimeoutException
                ? "Code retrieval timed out. Please try again."
                : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        if (message.length() > 400) {
            message = message.substring(0, 400);
        }

        try {
            emitter.send(SseEmitter.event()
                    .name("chat_error")
                    .data(java.util.Map.of("message", "Chat generation failed: " + message),
                            MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception sendError) {
            log.warn("Could not send chat error event to client", sendError);
            emitter.completeWithError(error);
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<devPilot.backend.dto.CitationDto> citations) {
        try {
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            failStream(emitter, ex);
        }
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}
