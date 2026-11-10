package devPilot.backend.services.ai;

import java.util.UUID;

import devPilot.backend.dto.ChatMessageResponse;

public record ChatStreamContext(
        UUID sessionId,
        ChatMessageResponse userMessage,
        UUID repositoryId,
        String repositoryName) {
}
