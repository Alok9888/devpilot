"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, FolderGit2, MessageSquareCode, Sparkles } from "lucide-react";

import { DevPilotIcon } from "@/components/icons/devpilot-icon";
import { BrandMark } from "@/components/layout/app-shell";
import { ModeToggle } from "@/components/ui/mode-toggle";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

export default function HomePage() {
  const [repoUrl, setRepoUrl] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const router = useRouter();

  async function analyze() {
    setError("");
    setLoading(true);
    try {
      const repo = await api.analyzeRepo(repoUrl);
      router.push(`/chat/${repo.id}`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not analyze repository");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="relative min-h-svh overflow-hidden">
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_top,oklch(from_var(--primary)_l_c_h/0.12),transparent_55%)]" />
      <header className="relative z-10 mx-auto flex h-14 w-full max-w-5xl items-center justify-between px-4">
        <BrandMark />
        <ModeToggle />
      </header>

      <main className="relative z-10 mx-auto flex w-full max-w-5xl flex-col gap-16 px-4 py-16 md:py-24">
        <section className="mx-auto max-w-2xl space-y-6 text-center">
          <div className="mx-auto flex size-14 items-center justify-center rounded-2xl shadow-sm">
            <DevPilotIcon className="size-14 rounded-2xl" />
          </div>
          <div className="space-y-3">
            <h1 className="font-heading text-4xl font-semibold tracking-tight sm:text-5xl">
              DevPilot
            </h1>
            <p className="text-lg text-muted-foreground text-balance">
              Paste a public GitHub repository and start chatting with its code
              using retrieval-augmented answers and citations.
            </p>
          </div>
          <div className="mx-auto flex w-full max-w-xl flex-col gap-3 sm:flex-row">
            <input
              value={repoUrl}
              onChange={(event) => setRepoUrl(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") void analyze(); }}
              placeholder="https://github.com/facebook/react"
              aria-label="GitHub repository URL"
              className="h-11 min-w-0 flex-1 rounded-md border bg-background px-3 text-sm shadow-xs outline-none focus-visible:ring-2 focus-visible:ring-ring"
            />
            <Button size="lg" onClick={() => void analyze()} disabled={!repoUrl.trim() || loading}>
              <FolderGit2 className="size-4" />
              {loading ? "Starting…" : "Analyze"}
              <ArrowRight className="size-4" />
            </Button>
          </div>
          {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
        </section>

        <section className="grid gap-4 md:grid-cols-3">
          {[
            {
              title: "Connect GitHub",
              body: "Paste a public GitHub repository URL to get started.",
              icon: FolderGit2,
            },
            {
              title: "Index with RAG",
              body: "Chunk and embed your code into Postgres + pgvector.",
              icon: Sparkles,
            },
            {
              title: "Ask anything",
              body: "Get grounded answers with clickable source citations.",
              icon: MessageSquareCode,
            },
          ].map((item) => (
            <div
              key={item.title}
              className="rounded-2xl border bg-card/80 p-5 shadow-xs backdrop-blur"
            >
              <div className="mb-4 flex size-10 items-center justify-center rounded-xl bg-muted">
                <item.icon className="size-5 text-foreground" />
              </div>
              <h2 className="font-medium">{item.title}</h2>
              <p className="mt-1.5 text-sm text-muted-foreground">{item.body}</p>
            </div>
          ))}
        </section>
      </main>
    </div>
  );
}
