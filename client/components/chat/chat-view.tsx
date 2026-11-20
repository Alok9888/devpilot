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
import { useIndexStatus, useIndexStatusByUrl, useRepository } from "@/hooks/use-repos";

export function ChatView({ repoId, initialUrl }: { repoId: string; initialUrl?: string }) {
  const [resolvedRepoId, setResolvedRepoId] = useState<string | null>(null);

  const routeRepoId = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(repoId)
    ? repoId
    : null;
  const effectiveRepoId = resolvedRepoId ?? routeRepoId ?? "";
  const shouldFetchRepo = Boolean(effectiveRepoId);

  const repoQuery = useRepository(effectiveRepoId, { enabled: shouldFetchRepo });

  // Use URL-based status polling when we have initialUrl but no repo ID yet
  const urlStatusQuery = useIndexStatusByUrl(initialUrl ?? "", Boolean(initialUrl && !effectiveRepoId));
  const repoStatusQuery = useIndexStatus(
    effectiveRepoId,
    Boolean(effectiveRepoId && (repoQuery.data?.indexStatus === "INDEXING" || repoQuery.data?.indexStatus === "PENDING"))
  );

  // Choose which status to use
  const statusQuery = initialUrl && !effectiveRepoId ? urlStatusQuery : repoStatusQuery;
  useEffect(() => {
    if (urlStatusQuery.data?.repositoryId) {
      setResolvedRepoId(urlStatusQuery.data.repositoryId);
    }
  }, [urlStatusQuery.data?.repositoryId]);

  const indexStatus =
    statusQuery.data?.indexStatus ?? repoQuery.data?.indexStatus;
  const ready = indexStatus === "READY";

  const sessionsQuery = useChatSessions(effectiveRepoId, ready);
  const createSession = useCreateChatSession(effectiveRepoId);
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

  // Calculate values based on stage for proper progress
  const urlStatus = statusQuery.data;

  // Calculate files/total for progress based on source
  const filesTotal = (initialUrl && !effectiveRepoId) ?
    (urlStatus?.filesTotal ?? 0) :
    (repoQuery.data?.filesTotal ?? 0);
  const filesProcessed = (initialUrl && !effectiveRepoId) ?
    (urlStatus?.filesProcessed ?? 0) :
    (repoQuery.data?.filesProcessed ?? 0);
  const chunksTotal = (initialUrl && !effectiveRepoId) ?
    (urlStatus?.chunksTotal ?? 0) :
    (repoQuery.data?.chunkCount ?? 0);
  const chunksProcessed = (initialUrl && !effectiveRepoId) ?
    (urlStatus?.chunksProcessed ?? 0) :
    0;
  const currentStage: 'creating' | 'fetching' | 'chunking' | 'embedding' | 'saving' | 'done' | null = (initialUrl && !effectiveRepoId) ?
    (urlStatus?.indexingStage ?? 'creating') :
    (repoQuery.data?.indexStatus === 'INDEXING' ? 'fetching' : null);
  const indexingDetail = (initialUrl && !effectiveRepoId) ?
    (urlStatus?.indexingDetail ?? 'Creating repo row...') :
    null;

  // CRITICAL: Show creating/fetching/chunking overlay IMMEDIATELY when URL exists but no repo ID yet
  // This MUST come before the loading check to prevent "Loading chat..." being shown
  if (initialUrl && !effectiveRepoId) {
    const tempStatus = {
      repositoryId: 'temp',
      indexStatus: 'INDEXING' as const,
      filesTotal,
      filesProcessed,
      chunkCount: chunksTotal,
      chunksTotal,
      chunksProcessed,
      indexingStage: currentStage,
      indexingDetail,
      indexedAt: null,
      errorMessage: urlStatus?.errorMessage ?? null,
    };
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
    return (
      <AppShell title="Indexing repository…" description={indexingDetail ?? 'Starting indexing...'}>
        <IndexingState repo={tempRepo} status={tempStatus} />
      </AppShell>
    );
  }

  // Show error only if we have a real repo query error
  if (repoQuery.isError) {
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

  // Only show "Loading chat..." when !repo && !isLoading (no initialUrl)
  if (!repo) {
    return (
      <AppShell title="Loading chat…">
        <div className="grid flex-1 gap-4 p-4 md:grid-cols-[18rem_1fr]">
          <Skeleton className="min-h-80 rounded-2xl" />
          <Skeleton className="min-h-80 rounded-2xl" />
        </div>
      </AppShell>
    );
  }

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
