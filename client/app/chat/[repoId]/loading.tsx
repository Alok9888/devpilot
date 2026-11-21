export default function ChatLoading() {
  return (
    <main
      role="status"
      aria-live="polite"
      className="flex min-h-svh items-center justify-center bg-background text-foreground"
    >
      <div className="flex items-center gap-3 rounded-xl border bg-card px-5 py-4 shadow-sm">
        <span className="size-2 animate-pulse rounded-full bg-primary" />
        <span className="text-sm font-medium">Opening chat...</span>
      </div>
    </main>
  );
}
