"use client";

import { useSearchParams } from "next/navigation";

import { ChatView } from "@/components/chat/chat-view";

export default function ChatPage() {
  const searchParams = useSearchParams();
  const urlParam = searchParams.get('url') ?? undefined;
  const slug = ''; // We use url param, not repoId from URL

  // Pass URL param for background analysis - ChatView will poll by URL
  return (
    <ChatView repoId={slug} initialUrl={urlParam} />
  );
}