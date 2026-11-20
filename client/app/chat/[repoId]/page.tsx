"use client";

import { useParams, useSearchParams } from "next/navigation";

import { ChatView } from "@/components/chat/chat-view";

export default function ChatPage() {
  const searchParams = useSearchParams();
  const params = useParams<{ repoId: string }>();
  const urlParam = searchParams.get('url') ?? undefined;
  const repoId = params.repoId;

  // Pass URL param for background analysis - ChatView will poll by URL
  return (
    <ChatView repoId={repoId} initialUrl={urlParam} />
  );
}
