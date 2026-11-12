"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { GitBranch, Loader2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { api, type Repository } from "@/lib/api";
import { useQueryClient } from "@tanstack/react-query";
import { queryKeys } from "@/lib/query-keys";
import { toast } from "@/components/ui/toast";

export function AddRepoDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [repoUrl, setRepoUrl] = useState("");
  const [isAnalyzing, setIsAnalyzing] = useState(false);

  const extractGitHubOwnerName = (url: string): { owner: string; name: string } | null => {
    try {
      const match = url.match(/github\.com\/([^/]+)\/([^/]+)/);
      if (match) {
        return { owner: match[1], name: match[2].replace(/\.git$/, "") };
      }
      return null;
    } catch {
      return null;
    }
  };

  const handleAnalyze = async () => {
    const parsed = extractGitHubOwnerName(repoUrl);
    if (!parsed) {
      toast.add({
        title: "Invalid GitHub URL",
        description: "Please enter a valid GitHub repository URL (e.g., https://github.com/owner/repo)",
        type: "error",
      });
      return;
    }

    setIsAnalyzing(true);
    try {
      const repo = await api.analyzeRepo(repoUrl);

      // Update cache with the new repo
      queryClient.setQueryData<Repository[]>(queryKeys.repos.list(), (old) => {
        if (!old) return [repo];
        const exists = old.find(r => r.id === repo.id);
        if (exists) return old;
        return [...old, repo];
      });

      // Invalidate detail queries
      queryClient.invalidateQueries({
        queryKey: queryKeys.repos.detail(repo.id),
      });

      toast.add({
        title: "Repository added!",
        description: `Analyzing ${repo.fullName}...`,
        type: "loading",
      });

      // Navigate to chat page
      router.push(`/chat/${repo.id}`);
    } catch (error) {
      toast.add({
        title: "Failed to analyze repository",
        description: error instanceof Error ? error.message : "Unknown error",
        type: "error",
      });
    } finally {
      setIsAnalyzing(false);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (isAnalyzing) return;
    handleAnalyze();
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>Add New Repository</DialogTitle>
            <DialogDescription>
              Enter a GitHub repository URL to analyze and chat with its code.
            </DialogDescription>
          </DialogHeader>

          <div className="grid gap-4 py-4">
            <div className="grid gap-2">
              <Label htmlFor="repo-url">GitHub Repository URL</Label>
              <div className="relative">
                <GitBranch className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  id="repo-url"
                  type="url"
                  placeholder="https://github.com/owner/repo"
                  value={repoUrl}
                  onChange={(e) => setRepoUrl(e.target.value)}
                  className="pl-10"
                  required
                />
              </div>
            </div>
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button onClick={handleAnalyze} disabled={isAnalyzing || !repoUrl.trim()}>
              {isAnalyzing ? (
                <>
                  <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                  Analyzing...
                </>
              ) : (
                "Analyze"
              )}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}