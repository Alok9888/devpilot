"use client";

import { AppShell } from "@/components/layout/app-shell";
import { SettingsDashboard } from "@/components/dashboard/settings-dashboard";

export default function SettingsPage() {
  return (
    <AppShell
      title="Settings"
      description="Appearance and repository access"
    >
      <SettingsDashboard />
    </AppShell>
  );
}
