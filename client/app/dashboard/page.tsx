"use client";

import { AppShell } from "@/components/layout/app-shell";
import { RepoDashboard } from "@/components/dashboard/repo-dashboard";

export default function DashboardPage() {
  return (
    <AppShell hideHeader>
      <RepoDashboard/>
    </AppShell>
  );
}
    

