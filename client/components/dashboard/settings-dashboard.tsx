"use client";

import { Moon, Sun, Copy, Check } from "lucide-react";
import { useTheme } from "next-themes";
import { useEffect, useState } from "react";


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
import { Button } from "@/components/ui/button";
import { getWorkspaceId } from "@/lib/api";
import { toast } from "@/components/ui/toast";

export function SettingsDashboard() {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const isDark = resolvedTheme === "dark";
  const [copied, setCopied] = useState(false);
  const [workspaceId, setWorkspaceId] = useState("");

  useEffect(() => {
    setWorkspaceId(getWorkspaceId());
  }, []);

  const copyWorkspaceId = async () => {
    try {
      await navigator.clipboard.writeText(workspaceId || getWorkspaceId());
      setCopied(true);
      toast.add({
        title: "Copied!",
        description: "Workspace ID copied to clipboard",
        type: "success",
      });
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      toast.add({
        title: "Failed to copy",
        description: "Could not copy workspace ID",
        type: "error",
      });
    }
  };

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
          <CardTitle>Workspace</CardTitle>
          <CardDescription>
            Your unique workspace ID. Used for privacy across repositories.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="flex items-center gap-3">
            <div className="flex-1">
              <Label>Workspace ID</Label>
              <p className="text-sm text-muted-foreground break-all">
                {workspaceId || "Loading workspace ID…"}
              </p>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={copyWorkspaceId}
              className="gap-2"
            >
              {copied ? (
                <Check className="size-4" />
              ) : (
                <Copy className="size-4" />
              )}
              {copied ? "Copied!" : "Copy"}
            </Button>
          </div>
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
