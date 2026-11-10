"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

import { DevPilotIcon } from "@/components/icons/devpilot-icon";

import { ModeToggle } from "@/components/ui/mode-toggle";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarProvider,
  SidebarTrigger,
} from "@/components/ui/sidebar";
import {
  dashboardNavGroups,
  isDashboardNavActive,
} from "@/lib/dashboard-nav";
import { cn } from "@/lib/utils";

export function AppShell({
  children,
  title,
  description,
  actions,
  hideHeader = false,
}: {
  children: React.ReactNode;
  title?: string;
  description?: string;
  actions?: React.ReactNode;
  hideHeader?: boolean;
}) {
  const pathname = usePathname();

  return (
    <SidebarProvider>
      <Sidebar variant="inset" collapsible="icon">
        <SidebarHeader>
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton
                size="lg"
                render={<Link href="/dashboard" />}
                tooltip="DevPilot"
              >
                <DevPilotIcon className="size-8 rounded-[10px]" />
                <div className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate font-semibold">DevPilot</span>
                  <span className="truncate text-xs text-muted-foreground">
                    Chat with your code
                  </span>
                </div>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarHeader>

        <SidebarContent>
          {dashboardNavGroups.map((group) => (
            <SidebarGroup key={group.label}>
              <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
              <SidebarGroupContent>
                <SidebarMenu>
                  {group.items.map((item) => (
                    <SidebarMenuItem key={item.href}>
                      <SidebarMenuButton
                        isActive={isDashboardNavActive(
                          pathname,
                          item.href,
                          item.exact
                        )}
                        tooltip={item.title}
                        render={<Link href={item.href} />}
                      >
                        <item.icon />
                        <span>{item.title}</span>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  ))}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ))}
        </SidebarContent>

        <SidebarFooter>
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton render={<Link href="/" />}>
                <DevPilotIcon className="size-5 rounded" />
                <span>Analyze a repository</span>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarFooter>
      </Sidebar>

      <SidebarInset>
        {!hideHeader && (
          <header className="sticky top-0 z-20 flex h-14 shrink-0 items-center gap-2 border-b bg-background/80 px-4 backdrop-blur">
            <SidebarTrigger className="-ml-1" />
            <Separator orientation="vertical" className="mr-2 h-4" />
            <div className="flex min-w-0 flex-1 items-center justify-between gap-3">
              <div className="min-w-0">
                {title && (
                  <h1 className="truncate font-heading text-sm font-medium">
                    {title}
                  </h1>
                )}
                {description && (
                  <p className="truncate text-xs text-muted-foreground">
                    {description}
                  </p>
                )}
              </div>
              <div className="flex items-center gap-2">
                {actions}
                <ModeToggle />
              </div>
            </div>
          </header>
        )}
        <div className="flex flex-1 flex-col">{children}</div>
      </SidebarInset>
    </SidebarProvider>
  );
}

export function BrandMark({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "flex items-center gap-2.5 font-semibold tracking-tight",
        className
      )}
    >
      <DevPilotIcon className="size-8 rounded-[10px]" />
      <span className="font-heading text-[1.05rem] leading-none">DevPilot</span>
    </div>
  );
}

export function GhostButtonLink({
  href,
  children,
  className,
}: {
  href: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <Button variant="ghost" size="sm" className={className} render={<Link href={href} />}>
      {children}
    </Button>
  );
}
