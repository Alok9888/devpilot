"use client";

import { useParams, useSearchParams } from "next/navigation";

import {ChatView} from "@/components/chat/chat-view";

export default function ChatPage() {
  const params = useParams<{ repoId: string }>();
  const slug = params?.repoId || '';
  const searchParams = useSearchParams();
  const urlParam = searchParams.get('url') ?? undefined;

  // If we have a URL param, pass it to ChatView for background analysis
  return (
    <ChatView repoId={slug} initialUrl={urlParam} />
  );
}