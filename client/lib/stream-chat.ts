import { getApiBaseUrl, ApiError, type ChatMessage } from "@/lib/api";

export type StreamChatHandlers = {
  onUserMessage?: (message: ChatMessage) => void;
  onToken?: (token: string) => void;
  onAssistantMessage?: (message: ChatMessage) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
  signal?: AbortSignal;
};

export async function streamChatMessage(
  sessionId: string,
  content: string,
  handlers: StreamChatHandlers = {}
): Promise<void> {
  const res = await fetch(
    `${getApiBaseUrl()}/api/chat/sessions/${sessionId}/messages`,
    {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content }),
      signal: handlers.signal,
    }
  );

  if (!res.ok) {
    let message = res.statusText;
    try {
      const data = await res.json();
      message = data.message ?? data.error ?? message;
    } catch {
      // ignore
    }
    throw new ApiError(res.status, message);
  }

  if (!res.body) {
    throw new Error("No response body for SSE stream");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let receivedReply = false;

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    // EventSource uses CRLF in some proxies and LF in others. Normalize before
    // splitting so a CRLF response cannot leave the whole stream buffered.
    buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, "\n");
    const parts = buffer.split("\n\n");
    buffer = parts.pop() ?? "";

    for (const part of parts) {
      if (!part.trim()) continue;

      const lines = part.split("\n");
      let event = "message";
      const dataLines: string[] = [];

      for (const line of lines) {
        if (line.startsWith("event:")) {
          event = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trimStart());
        }
      }

      const data = dataLines.join("\n");
      if (!data) continue;

      // Surface backend/model failures rather than treating a closed stream as success.
      if (event === "chat_error") {
        let message = "Chat generation failed. Check the backend logs for details.";
        try {
          const payload = JSON.parse(data) as { message?: string };
          if (payload.message) message = payload.message;
        } catch {
          // Keep the generic message if the event payload is not JSON.
        }
        throw new Error(message);
      }

      try {
        if (event === "token") {
          handlers.onToken?.(JSON.parse(data) as string);
        } else if (event === "user_message") {
          handlers.onUserMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "assistant_message") {
          receivedReply = true;
          handlers.onAssistantMessage?.(JSON.parse(data) as ChatMessage);
        } else if (event === "done") {
          receivedReply = true;
          handlers.onDone?.();
        }
      } catch (err) {
        handlers.onError?.(
          err instanceof Error ? err : new Error("Failed to parse SSE event")
        );
      }
    }
  }

  if (!receivedReply) {
    throw new Error("The chat stream ended before the assistant replied. Please try again.");
  }
  handlers.onDone?.();
}
