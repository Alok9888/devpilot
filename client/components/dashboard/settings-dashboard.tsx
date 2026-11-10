"use client";

import { Moon, Sun } from "lucide-react";
import { useTheme } from "next-themes";


import { ModeToggle } from "@/components/ui/mode-toggle";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Switch } from "@/components/ui/switch";

export function SettingsDashboard() {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const isDark = resolvedTheme === "dark";

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-6 p-4 md:p-6">
      <Card>
        <CardHeader>
          <CardTitle>Repository access</CardTitle>
          <CardDescription>
            DevPilot analyzes public repositories without a GitHub login.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-muted-foreground">
            Only public repositories are supported. Paste a repository URL on
            the home page to index it and start a chat.
          </p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Appearance</CardTitle>
          <CardDescription>
            Customize how DevPilot looks on your device.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <Label htmlFor="dark-mode">Dark mode</Label>
              <p className="text-sm text-muted-foreground">
                Switch between light and dark themes.
              </p>
            </div>
            <div className="flex items-center gap-3">
              <Sun className="size-4 text-muted-foreground" />
              <Switch
                id="dark-mode"
                checked={isDark}
                onCheckedChange={(checked) =>
                  setTheme(checked ? "dark" : "light")
                }
              />
              <Moon className="size-4 text-muted-foreground" />
            </div>
          </div>

          <Separator />

          <div className="flex items-center justify-between gap-4">
            <div className="space-y-1">
              <Label>Theme selector</Label>
              <p className="text-sm text-muted-foreground">
                Current theme: {theme ?? "system"}
              </p>
            </div>
            <ModeToggle />
          </div>
        </CardContent>
      </Card>

    </div>
  );
}
