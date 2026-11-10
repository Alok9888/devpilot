import { getApiBaseUrl, ApiError, type ChatMessage } from "@/lib/api";

const RESPONSE_TIMEOUT_MS = 20_000;
const STREAM_IDLE_TIMEOUT_MS = 110_000;

export type StreamChatHandlers = {
  onUserMessage?: (message: ChatMessage) => void;
  onToken?: (token: string) => void;
  onAssistantMessage?: (message: ChatMessage) => void;
  onDone?: () => void;
  onError?: (error: Error) => void;
  onModel?: (model: string, fallback: boolean, provider?: string) => void;
  signal?: AbortSignal;
};

function parseToken(data: string): string {
  // Accept both the current text/event-stream payload and JSON-string payloads
  // emitted by older deployments.
  try {
    const parsed: unknown = JSON.parse(data);
    if (typeof parsed === "string") return parsed;
  } catch {
    // SSE token data is plain text when it is not a JSON string.
  }
  return data;
}

export async function streamChatMessage(
  sessionId: string,
  content: string,
  handlers: StreamChatHandlers = {}
): Promise<void> {
  const controller = new AbortController();
  const externalSignal = handlers.signal;
  const forwardAbort = () => controller.abort(externalSignal?.reason);
  if (externalSignal?.aborted) {
    forwardAbort();
  } else {
    externalSignal?.addEventListener("abort", forwardAbort, { once: true });
  }

  let timedOut: "response" | "stream" | null = null;
  let responseTimer: ReturnType<typeof setTimeout> | undefined;
  let streamTimer: ReturnType<typeof setTimeout> | undefined;

  try {
    responseTimer = setTimeout(() => {
      timedOut = "response";
      controller.abort();
    }, RESPONSE_TIMEOUT_MS);
    const res = await fetch(
      `${getApiBaseUrl()}/api/chat/sessions/${sessionId}/messages`,
      {
        method: "POST",
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          Accept: "text/event-stream",
        },
        body: JSON.stringify({ content }),
        signal: controller.signal,
        cache: "no-store",
      }
    );
    clearTimeout(responseTimer);
    responseTimer = undefined;

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

    const contentType = res.headers.get("content-type")?.toLowerCase() ?? "";
    if (!contentType.includes("text/event-stream")) {
      throw new Error(`Expected an SSE response, received ${contentType || "no content type"}.`);
    }
    if (!res.body) {
      throw new Error("No response body for SSE stream");
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let receivedDone = false;
    let receivedOutcome = false;
    let streamError: (Error & { code?: string; model?: string; triedModels?: string[]; retryAfter?: string; retryAfterSeconds?: number }) | null = null;

    while (true) {
      streamTimer = setTimeout(() => {
        timedOut = "stream";
        controller.abort();
      }, STREAM_IDLE_TIMEOUT_MS);
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } finally {
        clearTimeout(streamTimer);
        streamTimer = undefined;
      }
      if (chunk.done) break;

      // Preserve incomplete frames across network chunks and normalize CRLF.
      buffer = (buffer + decoder.decode(chunk.value, { stream: true })).replace(/\r\n/g, "\n");
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

        if (event === "chat_error" || event === "error") {
          receivedOutcome = true;
          let message = "Chat generation failed. Check the backend logs for details.";
          let payload: { message?: string; type?: string; code?: string; model?: string; triedModels?: string[]; retryAfter?: string; retryAfterSeconds?: number } = {};
          try {
            payload = JSON.parse(data);
            if (payload.message) message = payload.message;
          } catch {
            // Keep the generic message if the event payload is not JSON.
          }
          streamError = Object.assign(new Error(message), payload);
          handlers.onError?.(streamError);
          continue;
        }

        try {
          if (event === "token") {
            handlers.onToken?.(parseToken(data));
          } else if (event === "user_message") {
            handlers.onUserMessage?.(JSON.parse(data) as ChatMessage);
          } else if (event === "assistant_message") {
            const message = JSON.parse(data) as ChatMessage;
            receivedOutcome = true;
            handlers.onAssistantMessage?.(message);
          } else if (event === "done") {
            receivedDone = true;
            handlers.onDone?.();
          } else if (event === "model") {
            const payload = JSON.parse(data) as { model?: string; fallback?: boolean; provider?: string };
            if (payload.model) handlers.onModel?.(payload.model, Boolean(payload.fallback), payload.provider);
          }
        } catch (err) {
          const parseError = err instanceof Error ? err : new Error("Failed to parse SSE event");
          handlers.onError?.(parseError);
          throw parseError;
        }
      }
      if (receivedDone) {
        await reader.cancel();
        break;
      }
    }

    if (streamError) throw streamError;
    if (!receivedOutcome || !receivedDone) {
      throw new Error("The chat stream ended without a reply or terminal event. Please try again.");
    }
    if (!receivedDone) handlers.onDone?.();
  } catch (err) {
    if (timedOut === "response") {
      throw new Error("Chat timed out before the server opened the stream. Please try again.");
    }
    if (timedOut === "stream") {
      throw new Error("Chat timed out while waiting for a response event. Please try again.");
    }
    throw err;
  } finally {
    if (responseTimer) clearTimeout(responseTimer);
    if (streamTimer) clearTimeout(streamTimer);
    externalSignal?.removeEventListener("abort", forwardAbort);
  }
}
