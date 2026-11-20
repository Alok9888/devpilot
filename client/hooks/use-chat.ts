"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback, useRef, useState } from "react";

import { api, type ChatMessage } from "@/lib/api";
import { queryKeys } from "@/lib/query-keys";
import { streamChatMessage } from "@/lib/stream-chat";
import { toast } from "@/components/ui/toast";

export function useChatSessions(repositoryId: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.chat.sessions(repositoryId),
    queryFn: () => api.listSessions(repositoryId),
    enabled: Boolean(repositoryId) && enabled,
  });
}

export function useChatMessages(sessionId: string | null) {
  return useQuery({
    queryKey: queryKeys.chat.messages(sessionId ?? ""),
    queryFn: () => api.getMessages(sessionId!),
    enabled: Boolean(sessionId),
  });
}

export function useCreateChatSession(repositoryId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (title?: string) => api.createSession(repositoryId, title),
    onSuccess: (session) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.chat.sessions(repositoryId),
      });
      queryClient.setQueryData(queryKeys.chat.messages(session.id), []);
    },
    onError: (error: Error) => {
      toast.add({
        title: "Could not create chat",
        description: error.message,
        type: "error",
      });
    },
  });
}

export function useStreamChat(sessionId: string | null) {
  const queryClient = useQueryClient();
  const [streaming, setStreaming] = useState(false);
  const [streamText, setStreamText] = useState("");
  const [streamError, setStreamError] = useState<{
    code?: string; message: string; triedModels?: string[]; retryAfter?: string;
  } | null>(null);
  const [lastContent, setLastContent] = useState("");
  const abortRef = useRef<AbortController | null>(null);

  // For variable display time per assistant message
  const messageStartTimeRef = useRef<number | null>(null);
  const displayTimeRef = useRef<number | null>(null);
  const displayTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Generate random display time between 2000ms and 6000ms
  const generateDisplayTime = () => {
    const MIN_DISPLAY_MS = 2000;
    const MAX_DISPLAY_MS = 6000;
    return Math.floor(Math.random() * (MAX_DISPLAY_MS - MIN_DISPLAY_MS + 1)) + MIN_DISPLAY_MS;
  };

  const send = useCallback(
    async (content: string) => {
      if (!sessionId || !content.trim() || streaming) return;

      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      const optimisticId = `temp-${Date.now()}`;
      const optimistic: ChatMessage = {
        id: optimisticId,
        role: "USER",
        content: content.trim(),
        citations: [],
        createdAt: new Date().toISOString(),
      };

      queryClient.setQueryData<ChatMessage[]>(
        queryKeys.chat.messages(sessionId),
        (prev) => [...(prev ?? []), optimistic]
      );

      setStreaming(true);
      setStreamText("");
      setStreamError(null);
      setLastContent(content.trim());
      let responseModel: { model: string; fallback: boolean; provider?: string } | null = null;
      let serverReportedError = false;
      // Reset per-message timing
      messageStartTimeRef.current = null;
      displayTimeRef.current = null;
      if (displayTimeoutRef.current) {
        clearTimeout(displayTimeoutRef.current);
        displayTimeoutRef.current = null;
      }

      try {
        await streamChatMessage(sessionId, content.trim(), {
          signal: controller.signal,
          onUserMessage: (message) => {
            queryClient.setQueryData<ChatMessage[]>(
              queryKeys.chat.messages(sessionId),
              (prev) => [
                ...(prev ?? []).filter((m) => m.id !== optimisticId),
                message,
              ]
            );
          },
          onToken: (token) => {
            // Record start time on first token
            if (messageStartTimeRef.current === null) {
              messageStartTimeRef.current = Date.now();
              displayTimeRef.current = generateDisplayTime();
            }
            setStreamText((prev) => prev + token);
          },
          onAssistantMessage: (message) => {
            const enriched = responseModel ? Object.assign({}, message, { responseModel }) : message;
            queryClient.setQueryData<ChatMessage[]>(
              queryKeys.chat.messages(sessionId),
              (prev) => [...(prev ?? []), enriched]
            );
            setStreamText("");
          },
          onModel: (model, fallback, provider) => { responseModel = { model, fallback, provider }; },
          onError: (error) => {
            serverReportedError = true;
            // Clear any pending display timeout
            if (displayTimeoutRef.current) {
              clearTimeout(displayTimeoutRef.current);
              displayTimeoutRef.current = null;
            }
            setStreaming(false);
            const structured = error as Error & { code?: string; triedModels?: string[]; retryAfter?: string };
            setStreamError({ code: structured.code, message: structured.message, triedModels: structured.triedModels, retryAfter: structured.retryAfter });
            setStreamText("");
          },
          onDone: () => {
            // Calculate remaining delay to meet minimum display time
            const messageStartTime = messageStartTimeRef.current;
            const displayTime = displayTimeRef.current;

            if (messageStartTime !== null && displayTime !== null) {
              const elapsed = Date.now() - messageStartTime;
              const remainingDelay = Math.max(0, displayTime - elapsed);

              if (remainingDelay > 0) {
                // Delay before clearing streaming flag
                displayTimeoutRef.current = setTimeout(() => {
                  setStreaming(false);
                  displayTimeoutRef.current = null;
                }, remainingDelay);
              } else {
                setStreaming(false);
              }
            } else {
              // No timing data, clear immediately
              setStreaming(false);
            }
          },
        });
      } catch (err) {
        if ((err as Error).name === "AbortError") return;
        if (!serverReportedError) {
          const message = err instanceof Error ? err.message : "Unknown error";
          setStreamError({ message });
          toast.add({ title: "Message failed", description: message, type: "error" });
        }
        setStreamText("");
      } finally {
        // Only clear streaming if not already handled by onDone/onError with delay
        // The finally block runs after try/catch but we don't want to override the setTimeout
        // We'll let onDone/onError handle the streaming state
      }
    },
    [sessionId, streaming, queryClient, streamError]
  );

  const retry = useCallback(() => { if (lastContent) void send(lastContent); }, [lastContent, send]);

  const stop = useCallback(() => {
    abortRef.current?.abort();
    // Clear any pending display timeout
    if (displayTimeoutRef.current) {
      clearTimeout(displayTimeoutRef.current);
      displayTimeoutRef.current = null;
    }
    setStreaming(false);
  }, []);

  return { send, stop, retry, streaming, streamText, streamError };
}
