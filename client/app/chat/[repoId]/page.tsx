"use client";

import { use } from "react";

import {ChatView}  from "@/components/chat/chat-view";

export default function ChatPage({
  params,
}: {
  params: Promise<{ repoId: string }>;
}) {
  const { repoId } = use(params);

  return (
    <ChatView repoId={repoId} />
  );
}
