"use client";

import { useEffect, useRef, useState } from "react";
import { AlertCircle, Loader2, RotateCcw, CheckCircle } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from "@/components/ui/empty";
import { Progress } from "@/components/ui/progress";
import { getRepoProgress, useStartIndexing } from "@/hooks/use-repos";
import type { IndexStatusResponse, Repository } from "@/lib/api";

const STAGES = [
  { key: "creating", label: "Creating", icon: Loader2 },
  { key: "fetching", label: "Fetching", icon: Loader2 },
  { key: "chunking", label: "Chunking", icon: Loader2 },
  { key: "embedding", label: "Embedding", icon: Loader2 },
  { key: "saving", label: "Saving to DB", icon: Loader2 },
  { key: "done", label: "Done", icon: CheckCircle },
] as const;

const SUBTEXTS: Record<string, string[]> = {
  creating: [
    "Creating repo row...",
    "Fetching GitHub metadata...",
    "Validating repository...",
  ],
  fetching: [
    "Fetching repository files...",
    "Reading file list from GitHub...",
    "Downloading source files...",
  ],
  chunking: [
    "Splitting files into chunks...",
    "Analyzing code structure...",
    "Preparing chunks for embedding...",
  ],
  embedding: [
    "Generating embeddings...",
    "Processing batch...",
    "Computing vector representations...",
  ],
  saving: [
    "Saving vectors to database...",
    "Writing to vector store...",
    "Finalizing index...",
  ],
  done: [
    "Indexing complete!",
    "Ready to chat.",
    "All done.",
  ],
};

export function IndexingState({
  repo,
  status,
}: {
  repo: Repository;
  status?: IndexStatusResponse;
}) {
  const indexMutation = useStartIndexing();
  const filesProcessed = status?.filesProcessed ?? repo.filesProcessed;
  const filesTotal = status?.filesTotal ?? repo.filesTotal;
  const chunksTotal = status?.chunksTotal ?? repo.chunkCount;
  const chunksProcessed = status?.chunksProcessed ?? 0;
  const indexStatus = status?.indexStatus ?? repo.indexStatus;
  const errorMessage = status?.errorMessage ?? repo.errorMessage;
  const indexingStage = status?.indexingStage;
  const indexingDetail = status?.indexingDetail;

  // Current stage index for progress display
  const currentStageIndex = STAGES.findIndex((s) => s.key === indexingStage);
  const completedStages = currentStageIndex >= 0 ? currentStageIndex : 0;
  const stageProgress = STAGES.length > 0 ? ((completedStages + 1) / STAGES.length) * 100 : 0;

  // Subtext rotation state
  const [subtext, setSubtext] = useState("");
  const subtextIndexRef = useRef(0);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  // Rotate subtext every 500ms
  useEffect(() => {
    if (indexStatus === "READY" || indexStatus === "FAILED") return;

    const stageKey = indexingStage ?? "fetching";
    const details = SUBTEXTS[stageKey] ?? SUBTEXTS.fetching;

    // Include detail from backend if available
    const allDetails = indexingDetail
      ? [indexingDetail, ...details.filter(d => d !== indexingDetail)]
      : details;

    // Initial subtext
    setSubtext(allDetails[0]);
    subtextIndexRef.current = 0;

    intervalRef.current = setInterval(() => {
      subtextIndexRef.current = (subtextIndexRef.current + 1) % allDetails.length;
      setSubtext(allDetails[subtextIndexRef.current]);
    }, 500);

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [indexingStage, indexingDetail, indexStatus]);

  if (indexStatus === "FAILED") {
    return (
      <Empty className="h-full border-0">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <AlertCircle className="text-destructive" />
          </EmptyMedia>
          <EmptyTitle>Indexing failed</EmptyTitle>
          <EmptyDescription>
            {errorMessage || "Something went wrong while indexing this repository."}
          </EmptyDescription>
        </EmptyHeader>
        <Button
          onClick={() => indexMutation.mutate(repo.id)}
          disabled={indexMutation.isPending}
        >
          <RotateCcw data-icon="inline-start" />
          Retry indexing
        </Button>
      </Empty>
    );
  }

  if (indexStatus === "READY") {
    return (
      <Empty className="h-full border-0">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <CheckCircle className="text-green-500" />
          </EmptyMedia>
          <EmptyTitle>Indexing complete</EmptyTitle>
          <EmptyDescription>
            {repo.fullName} is ready to chat.
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );
  }

  // Calculate progress based on current stage
  let progress = 0;
  switch (indexingStage) {
    case "creating":
      progress = 5;
      break;
    case "fetching":
      progress = 5 + (filesTotal > 0 ? Math.round((filesProcessed / filesTotal) * 20) : 10);
      break;
    case "chunking":
      progress = 25 + (chunksTotal > 0 ? Math.round((chunksProcessed / chunksTotal) * 20) : 10);
      break;
    case "embedding":
      progress = 45 + (chunksTotal > 0 ? Math.round((chunksProcessed / chunksTotal) * 30) : 15);
      break;
    case "saving":
      progress = 75;
      break;
    case "done":
      progress = 100;
      break;
    default:
      progress = 5;
  }

  return (
    <Empty className="h-full border-0">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <Loader2 className="animate-spin" />
        </EmptyMedia>
        <EmptyTitle>Indexing {repo.fullName}</EmptyTitle>
        <EmptyDescription>
          <div className="text-sm">
            <div className="flex items-center gap-2 mb-2">
              {STAGES.map((stage, idx) => (
                <span
                  key={stage.key}
                  className={`flex items-center gap-1 px-2 py-1 rounded text-xs ${
                    idx < completedStages
                      ? "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300"
                      : idx === completedStages
                      ? "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300 animate-pulse"
                      : "bg-muted text-muted-foreground"
                  }`}
                >
                  <stage.icon className="h-3 w-3" />
                  {stage.label}
                </span>
              ))}
            </div>
            <p className="text-muted-foreground text-sm min-h-[1.25rem] transition-all duration-300">
              {subtext}
            </p>
            {filesTotal > 0 && (
              <p className="text-xs text-muted-foreground mt-1">
                {indexingStage === "fetching" && (
                  <>Files: {filesProcessed} / {filesTotal}</>
                )}
                {indexingStage === "chunking" && (
                  <>Chunks: {chunksProcessed} / {chunksTotal}</>
                )}
                {indexingStage === "embedding" && (
                  <>Embedding: {chunksProcessed} / {chunksTotal}</>
                )}
                {indexingStage === "saving" && (
                  <>Saving {chunksTotal} vectors...</>
                )}
              </p>
            )}
          </div>
        </EmptyDescription>
      </EmptyHeader>
      <div className="w-full max-w-sm space-y-2">
        <Progress value={Math.min(100, Math.max(progress, 5))} />
        <p className="text-center text-xs text-muted-foreground">
          You can leave this page open — chat unlocks when indexing finishes.
        </p>
      </div>
    </Empty>
  );
}