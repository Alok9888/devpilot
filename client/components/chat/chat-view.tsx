"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ArrowLeft } from "lucide-react";

import { ChatComposer } from "@/components/chat/chat-composer";
import { ChatMessages } from "@/components/chat/chat-messages";
import { ChatSidebar } from "@/components/chat/chat-sidebar";
import { IndexingState } from "@/components/chat/indexing-state";
import { AppShell } from "@/components/layout/app-shell";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  useChatMessages,
  useChatSessions,
  useCreateChatSession,
  useStreamChat,
} from "@/hooks/use-chat";
import { useIndexStatus, useRepository } from "@/hooks/use-repos";
import { api, type Repository } from "@/lib/api";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/query-keys";
import { toast } from "@/components/ui/toast";

export function ChatView({ repoId, initialUrl }: { repoId: string; initialUrl?: string }) {
  const queryClient = useQueryClient();
  const [repoIdFromUrl, setRepoIdFromUrl] = useState<string | null>(null);
  const [isCreatingRepo, setIsCreatingRepo] = useState(false);

  // If we have an initial URL, we need to analyze it first
  const effectiveRepoId = repoIdFromUrl ?? repoId;
  const shouldFetchRepo = !initialUrl || !!repoIdFromUrl;

  const repoQuery = useRepository(effectiveRepoId, { enabled: shouldFetchRepo });
  const isIndexing = repoQuery.data?.indexStatus === "INDEXING";
  const statusQuery = useIndexStatus(
    effectiveRepoId,
    isIndexing || repoQuery.data?.indexStatus === "PENDING"
  );

  // Fire analyze request on mount if we have an initial URL
  useEffect(() => {
    if (initialUrl && !repoIdFromUrl) {
      setIsCreatingRepo(true);
      api.analyzeRepo(initialUrl)
        .then(repo => {
          // Update cache
          queryClient.setQueryData<Repository[]>(queryKeys.repos.list(), (old) => {
            if (!old) return [repo];
            const exists = old.find(r => r.id === repo.id);
            if (exists) return old;
            return [...old, repo];
          });
          queryClient.invalidateQueries({ queryKey: queryKeys.repos.detail(repo.id) });
          // Switch to use the real repo ID
          setRepoIdFromUrl(repo.id);
          toast.add({ title: "Repository found!", description: `Analyzing ${repo.fullName}...`, type: "success" });
        })
        .catch(error => {
          toast.add({ title: "Failed to analyze repository", description: error instanceof Error ? error.message : "Unknown error", type: "error" });
        })
        .finally(() => {
          setIsCreatingRepo(false);
        });
    }
  }, [initialUrl, repoIdFromUrl, queryClient]);

  const indexStatus =
    statusQuery.data?.indexStatus ?? repoQuery.data?.indexStatus;
  const ready = indexStatus === "READY";

  const sessionsQuery = useChatSessions(repoId, ready);
  const createSession = useCreateChatSession(repoId);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(
    null
  );
  const autoCreateRef = useRef(false);

  const sessionId =
    selectedSessionId ?? sessionsQuery.data?.[0]?.id ?? null;

  const messagesQuery = useChatMessages(sessionId);
  const { send, stop, retry, streaming, streamText, streamError } = useStreamChat(sessionId);

  // Track when streaming started for the 10s warning
  const [showLongWaitWarning, setShowLongWaitWarning] = useState(false);

  useEffect(() => {
    if (streaming) {
      const timer = setTimeout(() => {
        setShowLongWaitWarning(true);
      }, 10000);
      return () => clearTimeout(timer);
    }
    setShowLongWaitWarning(false);
  }, [streaming]);

  useEffect(() => {
    if (!ready || sessionsQuery.isLoading) return;
    if (sessionsQuery.data && sessionsQuery.data.length > 0) return;
    if (
      !sessionsQuery.isSuccess ||
      (sessionsQuery.data?.length ?? 0) > 0 ||
      autoCreateRef.current
    ) {
      return;
    }

    autoCreateRef.current = true;
    createSession.mutate(undefined, {
      onSuccess: (session) => setSelectedSessionId(session.id),
      onError: () => {
        autoCreateRef.current = false;
      },
    });
  }, [
    ready,
    sessionsQuery.isLoading,
    sessionsQuery.isSuccess,
    sessionsQuery.data,
    createSession,
  ]);

  if (repoQuery.isLoading) {
    return (
      <AppShell title="Loading chat…">
        <div className="grid flex-1 gap-4 p-4 md:grid-cols-[18rem_1fr]">
          <Skeleton className="min-h-80 rounded-2xl" />
          <Skeleton className="min-h-80 rounded-2xl" />
        </div>
      </AppShell>
    );
  }

  // Stage 0: Creating repo - show overlay while background POST /api/analyze runs
  if (isCreatingRepo || (initialUrl && !repoIdFromUrl)) {
    const tempRepo = {
      id: 'temp',
      githubRepoId: 0,
      owner: '',
      name: '',
      fullName: 'Repository',
      isPrivate: false,
      defaultBranch: 'main',
      language: null,
      htmlUrl: null,
      description: null,
      indexStatus: 'INDEXING' as const,
      indexedAt: null,
      chunkCount: 0,
      filesTotal: 0,
      filesProcessed: 0,
      errorMessage: null,
    };
    const tempStatus = {
      repositoryId: 'temp',
      indexStatus: 'INDEXING' as const,
      filesTotal: 0,
      filesProcessed: 0,
      chunkCount: 0,
      chunksTotal: 0,
      chunksProcessed: 0,
      indexingStage: 'creating' as const,
      indexingDetail: 'Creating repo row...',
      indexedAt: null,
      errorMessage: null,
    };
    return (
      <AppShell title="Setting up repository…" description="Creating repo row and fetching GitHub metadata">
        <IndexingState
          repo={tempRepo}
          status={tempStatus}
        />
      </AppShell>
    );
  }

  if (repoQuery.isError || !repoQuery.data) {
    return (
      <AppShell title="Repository unavailable">
        <div className="flex flex-1 flex-col items-center justify-center gap-3 p-8">
          <p className="text-sm text-muted-foreground">
            {(repoQuery.error as Error)?.message ?? "Repository not found"}
          </p>
          <Button render={<Link href="/dashboard" />}>Back to dashboard</Button>
        </div>
      </AppShell>
    );
  }

  const repo = repoQuery.data;

  return (
    <AppShell
      title={repo.fullName}
      description={
        ready
          ? "Ask questions grounded in this repository"
          : "Waiting for indexing to finish"
      }
      actions={
        <Button variant="outline" size="sm" render={<Link href="/dashboard" />}>
          <ArrowLeft data-icon="inline-start" />
          Repos
        </Button>
      }
    >
      <div className="flex min-h-0 flex-1 flex-col md:flex-row">
        <ChatSidebar
          repo={{
            ...repo,
            indexStatus: indexStatus ?? repo.indexStatus,
            filesProcessed:
              statusQuery.data?.filesProcessed ?? repo.filesProcessed,
            filesTotal: statusQuery.data?.filesTotal ?? repo.filesTotal,
            chunkCount: statusQuery.data?.chunkCount ?? repo.chunkCount,
            errorMessage: statusQuery.data?.errorMessage ?? repo.errorMessage,
          }}
          sessionId={sessionId}
          onSelectSession={setSelectedSessionId}
        />

        <section className="flex min-h-[70vh] min-w-0 flex-1 flex-col">
          {!ready ? (
            <IndexingState repo={repo} status={statusQuery.data} />
          ) : (
            <>
              <ChatMessages
                repo={repo}
                messages={messagesQuery.data ?? []}
                streamText={streamText}
                isLoading={messagesQuery.isLoading}
                streaming={streaming}
                showLongWaitWarning={showLongWaitWarning}
                streamError={streamError}
                onRetry={retry}
              />
              <ChatComposer
                disabled={!sessionId}
                streaming={streaming}
                onSend={send}
                onStop={stop}
              />
            </>
          )}
        </section>
      </div>
    </AppShell>
  );
}
