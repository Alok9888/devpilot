"use client";

import { AppShell } from "@/components/layout/app-shell";
import { OverviewDashboard } from "@/components/dashboard/overview-dashboard";

export default function OverviewPage() {
  return (
    <AppShell
      title="Overview"
      description="Workspace stats and recent repository activity"
    >
      <OverviewDashboard />
    </AppShell>
  );
}
