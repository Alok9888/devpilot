# Graph Report - DevPilot  (2026-11-18)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 1061 nodes · 2829 edges · 61 communities (43 shown, 18 thin omitted)
- Extraction: 98% EXTRACTED · 2% INFERRED · 0% AMBIGUOUS · INFERRED: 50 edges (avg confidence: 0.85)
- Token cost: 38,104 input · 819 output

## Graph Freshness
- Built from commit: `0f3861f0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- App Navigation
- Chat Processing
- UI Components
- Code Indexing
- Workspace Management
- App Layout
- Dropdown Menus
- UI Primitives
- Application Config
- Data Models
- Dashboard UI
- Search Components
- Chat Page
- Component Config
- Repo Commands
- Chat Composer
- Message UI
- Spring Config
- Chat Service
- Error Handling
- TypeScript Config
- GitHub API
- Code Retrieval
- Dependencies
- Docker Config
- Overview Dashboard
- Context Menus
- Models
- Drawer Components
- Carousel Components
- API Client
- Repo Status
- Error Alerts
- Alert Dialogs
- Charts
- Form Fields
- Markers
- Toasts
- Dev Dependencies
- Attachments
- Package Config
- Repositories
- Navigation Menus
- Select Components
- Popover Components
- Tabs
- ESLint Config
- Spring Boot App
- App Tests
- Next Config
- App Scripts
- cmdk
- date-fns
- embla-carousel
- react-day-picker
- react-icons
- shadcn
- @shadcn/ui
- PostCSS Config
- Backend

## God Nodes (most connected - your core abstractions)
1. `cn()` - 368 edges
2. `react` - 55 edges
3. `Button()` - 51 edges
4. `lucide-react` - 41 edges
5. `@base-ui/react` - 40 edges
6. `IndexingService` - 29 edges
7. `AppShell()` - 27 edges
8. `ChatStreamHandler` - 25 edges
9. `Repository` - 25 edges
10. `ChatService` - 19 edges

## Surprising Connections (you probably didn't know these)
- `DevPilotLogo()` --calls--> `cn()`  [EXTRACTED]
  client/components/icons/devpilot-icon.tsx → client/lib/utils.ts
- `GhostButtonLink()` --calls--> `Button()`  [EXTRACTED]
  client/components/layout/app-shell.tsx → client/components/ui/button.tsx
- `SheetFooter()` --calls--> `cn()`  [EXTRACTED]
  client/components/ui/sheet.tsx → client/lib/utils.ts
- `SidebarGroupAction()` --calls--> `cn()`  [EXTRACTED]
  client/components/ui/sidebar.tsx → client/lib/utils.ts
- `SidebarMenuAction()` --calls--> `cn()`  [EXTRACTED]
  client/components/ui/sidebar.tsx → client/lib/utils.ts

## Import Cycles
- None detected.

## Communities (61 total, 18 thin omitted)

### Community 0 - "App Navigation"
Cohesion: 0.06
Nodes (52): DashboardPage(), SettingsPage(), HomePage(), DevPilotIcon(), DevPilotIconProps, DevPilotLogo(), AppShell(), BrandMark() (+44 more)

### Community 1 - "Chat Processing"
Cohesion: 0.07
Nodes (9): WorkspaceFilter, CitationDto, ChatPromptBuilder, ChatStreamHandler, ProviderStreamException, CitationMapper, TypeReference<>, RetrievedContext (+1 more)

### Community 2 - "UI Components"
Cohesion: 0.07
Nodes (36): Accordion(), AccordionContent(), AccordionItem(), AccordionTrigger(), AspectRatio(), Breadcrumb(), BreadcrumbEllipsis(), BreadcrumbItem() (+28 more)

### Community 3 - "Code Indexing"
Cohesion: 0.08
Nodes (8): RagSettings, GitHubRateLimiter, CodeChunker, CodeFileFilter, IndexingService, FileResult, IndexingResult, TypeReference<Map<String, String>>

### Community 4 - "Workspace Management"
Cohesion: 0.11
Nodes (9): WorkspaceContext, ChatController, RepoController, AnalyzeRepoRequest, ChatMessageRequest, IndexStatusResponse, RepositoryResponse, HealthController (+1 more)

### Community 5 - "App Layout"
Cohesion: 0.09
Nodes (29): metadata, RootLayout(), ChatMarkdown(), streamdownPlugins, ChatMessages(), LOADING_MESSAGES, QueryProvider(), ThemeProvider() (+21 more)

### Community 6 - "Dropdown Menus"
Cohesion: 0.12
Nodes (32): DropdownMenu(), DropdownMenuCheckboxItem(), DropdownMenuContent(), DropdownMenuGroup(), DropdownMenuItem(), DropdownMenuLabel(), DropdownMenuPortal(), DropdownMenuRadioGroup() (+24 more)

### Community 7 - "UI Primitives"
Cohesion: 0.08
Nodes (23): ButtonGroup(), ButtonGroupSeparator(), ButtonGroupText(), buttonGroupVariants, Checkbox(), FieldSeparator(), HoverCardContent(), Item() (+15 more)

### Community 9 - "Data Models"
Cohesion: 0.19
Nodes (3): ChatMessage, ChatSession, Repository

### Community 10 - "Dashboard UI"
Cohesion: 0.17
Nodes (24): IndexingState(), DashboardHeader(), DashboardHeaderProps, FilterPill(), FilterStatus, statusFilters, visibilityFilters, RepoCard() (+16 more)

### Community 11 - "Search Components"
Cohesion: 0.11
Nodes (21): ComboboxChip(), ComboboxChips(), ComboboxChipsInput(), ComboboxClear(), ComboboxContent(), ComboboxEmpty(), ComboboxGroup(), ComboboxInput() (+13 more)

### Community 12 - "Chat Page"
Cohesion: 0.20
Nodes (18): ChatPage(), ChatSidebar(), ChatView(), Skeleton(), toast, useChatMessages(), useChatSessions(), useCreateChatSession() (+10 more)

### Community 13 - "Component Config"
Cohesion: 0.09
Nodes (21): aliases, components, hooks, lib, ui, utils, iconLibrary, menuAccent (+13 more)

### Community 14 - "Repo Commands"
Cohesion: 0.19
Nodes (17): AddRepoDialog(), Command(), CommandDialog(), CommandEmpty(), CommandGroup(), CommandItem(), CommandList(), CommandSeparator() (+9 more)

### Community 15 - "Chat Composer"
Cohesion: 0.17
Nodes (14): ChatComposer(), LanguageBadge(), FALLBACK, getLanguageConfig(), getLanguageLabel(), LANGUAGE_MAP, LanguageConfig, LanguageIcon() (+6 more)

### Community 16 - "Message UI"
Cohesion: 0.13
Nodes (15): buttonVariants, Calendar(), CalendarDayButton(), MessageScroller(), MessageScrollerButton(), MessageScrollerContent(), MessageScrollerItem(), MessageScrollerViewport() (+7 more)

### Community 18 - "Chat Service"
Cohesion: 0.19
Nodes (5): ChatSessionResponse, CreateChatSessionRequest, BadRequestException, ChatSessionRepository, ChatService

### Community 20 - "TypeScript Config"
Cohesion: 0.11
Nodes (18): compilerOptions, allowJs, esModuleInterop, incremental, isolatedModules, jsx, lib, module (+10 more)

### Community 23 - "Dependencies"
Cohesion: 0.11
Nodes (18): dependencies, @base-ui/react@^1.6.0, class-variance-authority@^0.7.1, clsx@^2.1.1, input-otp@^1.4.2, lucide-react@^1.27.0, react@19.2.4, react-dom@19.2.4 (+10 more)

### Community 24 - "Docker Config"
Cohesion: 0.11
Nodes (17): POSTGRES_DB, POSTGRES_PASSWORD, POSTGRES_USER, interval, retries, test, timeout, container_name (+9 more)

### Community 25 - "Overview Dashboard"
Cohesion: 0.34
Nodes (12): OverviewPage(), OverviewDashboard(), StatCard(), SettingsDashboard(), Card(), CardAction(), CardContent(), CardDescription() (+4 more)

### Community 26 - "Context Menus"
Cohesion: 0.13
Nodes (10): ContextMenuCheckboxItem(), ContextMenuContent(), ContextMenuItem(), ContextMenuLabel(), ContextMenuRadioItem(), ContextMenuSeparator(), ContextMenuShortcut(), ContextMenuSubContent() (+2 more)

### Community 27 - "Models"
Cohesion: 0.19
Nodes (10): ChatMessageResponse, IndexStatus, FAILED, INDEXING, PENDING, READY, MessageRole, ASSISTANT (+2 more)

### Community 28 - "Drawer Components"
Cohesion: 0.17
Nodes (11): DrawerContent(), DrawerContext, DrawerContextProps, DrawerDescription(), DrawerFooter(), DrawerHeader(), DrawerOverlay(), DrawerPortal() (+3 more)

### Community 29 - "Carousel Components"
Cohesion: 0.19
Nodes (13): Carousel(), CarouselApi, CarouselContent(), CarouselContext, CarouselContextProps, CarouselItem(), CarouselNext(), CarouselOptions (+5 more)

### Community 30 - "API Client"
Cohesion: 0.27
Nodes (11): ApiError, apiFetch(), ChatMessage, ChatSession, getApiBaseUrl(), getWorkspaceId(), IndexStatusResponse, parseError() (+3 more)

### Community 31 - "Repo Status"
Cohesion: 0.27
Nodes (10): CitationChips(), citationHref(), IndexStatusBadge(), indexStatusLabel(), languageColor(), RepoMeta(), Badge(), badgeVariants (+2 more)

### Community 32 - "Error Alerts"
Cohesion: 0.33
Nodes (10): getErrorSummary(), IndexErrorAlert(), Alert(), AlertAction(), AlertDescription(), AlertTitle(), alertVariants, Collapsible() (+2 more)

### Community 33 - "Alert Dialogs"
Cohesion: 0.18
Nodes (10): AlertDialogAction(), AlertDialogCancel(), AlertDialogContent(), AlertDialogDescription(), AlertDialogFooter(), AlertDialogHeader(), AlertDialogMedia(), AlertDialogOverlay() (+2 more)

### Community 34 - "Charts"
Cohesion: 0.22
Nodes (12): ChartConfig, ChartContainer(), ChartContext, ChartContextProps, ChartLegendContent(), ChartStyle(), ChartTooltipContent(), getPayloadConfigFromPayload() (+4 more)

### Community 35 - "Form Fields"
Cohesion: 0.19
Nodes (11): Field(), FieldContent(), FieldDescription(), FieldError(), FieldGroup(), FieldLabel(), FieldLegend(), FieldSet() (+3 more)

### Community 36 - "Markers"
Cohesion: 0.22
Nodes (10): Marker(), MarkerContent(), MarkerIcon(), markerVariants, ToggleGroup(), ToggleGroupContext, ToggleGroupItem(), Toggle() (+2 more)

### Community 37 - "Toasts"
Cohesion: 0.29
Nodes (12): Toast(), ToastAction(), ToastClose(), ToastContent(), ToastDescription(), Toaster(), ToastIcon(), ToastList() (+4 more)

### Community 38 - "Dev Dependencies"
Cohesion: 0.15
Nodes (13): devDependencies, tailwindcss@^4, @tailwindcss/postcss@^4, @types/node@^20, @types/react@^19, @types/react-dom@^19, typescript@^5, tailwindcss (+5 more)

### Community 39 - "Attachments"
Cohesion: 0.20
Nodes (11): Attachment(), AttachmentAction(), AttachmentActions(), AttachmentContent(), AttachmentDescription(), AttachmentGroup(), AttachmentMedia(), attachmentMediaVariants (+3 more)

### Community 40 - "Package Config"
Cohesion: 0.17
Nodes (11): next-themes@^0.4.6, react-resizable-panels@^4.12.2, streamdown@^2.5.0, @streamdown/code@^1.1.1, name, private, version, next-themes (+3 more)

### Community 43 - "Navigation Menus"
Cohesion: 0.24
Nodes (9): NavigationMenu(), NavigationMenuContent(), NavigationMenuIndicator(), NavigationMenuItem(), NavigationMenuLink(), NavigationMenuList(), NavigationMenuPositioner(), NavigationMenuTrigger() (+1 more)

### Community 44 - "Select Components"
Cohesion: 0.24
Nodes (9): SelectContent(), SelectGroup(), SelectItem(), SelectLabel(), SelectScrollDownButton(), SelectScrollUpButton(), SelectSeparator(), SelectTrigger() (+1 more)

### Community 45 - "Popover Components"
Cohesion: 0.29
Nodes (4): PopoverContent(), PopoverDescription(), PopoverHeader(), PopoverTitle()

### Community 46 - "Tabs"
Cohesion: 0.40
Nodes (5): Tabs(), TabsContent(), TabsList(), tabsListVariants, TabsTrigger()

### Community 47 - "ESLint Config"
Cohesion: 0.33
Nodes (5): eslintConfig, eslint@^9, eslint-config-next@16.2.12, eslint, eslint-config-next

### Community 50 - "Next Config"
Cohesion: 0.40
Nodes (4): backendOrigin, nextConfig, next@16.2.12, next

### Community 51 - "App Scripts"
Cohesion: 0.40
Nodes (5): scripts, build, dev, lint, start

## Knowledge Gaps
- **7 isolated node(s):** `FAILED`, `INDEXING`, `PENDING`, `READY`, `ASSISTANT` (+2 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 185 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `cn()` connect `UI Components` to `App Navigation`, `App Layout`, `Dropdown Menus`, `UI Primitives`, `Dashboard UI`, `Search Components`, `Chat Page`, `Repo Commands`, `Chat Composer`, `Message UI`, `Overview Dashboard`, `Context Menus`, `Drawer Components`, `Carousel Components`, `Repo Status`, `Error Alerts`, `Alert Dialogs`, `Charts`, `Form Fields`, `Markers`, `Toasts`, `Attachments`, `Navigation Menus`, `Select Components`, `Popover Components`, `Tabs`?**
  _High betweenness centrality (0.263) - this node is a cross-community bridge._
- **What connects `FAILED`, `INDEXING`, `PENDING` to the rest of the system?**
  _7 weakly-connected nodes found - possible missing edges._
- **Should `App Navigation` be split into smaller, more focused modules?**
  _Cohesion score 0.06153846153846154 - nodes in this community are weakly interconnected._
- **Why does `react` connect `App Layout` to `App Navigation`, `UI Components`, `Dropdown Menus`, `UI Primitives`, `Dashboard UI`, `Search Components`, `Chat Page`, `Repo Commands`, `Chat Composer`, `Message UI`, `Dependencies`, `Overview Dashboard`, `Context Menus`, `Drawer Components`, `Carousel Components`, `Error Alerts`, `Alert Dialogs`, `Charts`, `Form Fields`, `Markers`, `Toasts`, `Attachments`, `Package Config`, `Select Components`, `Popover Components`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Should `Chat Processing` be split into smaller, more focused modules?**
  _Cohesion score 0.06688311688311688 - nodes in this community are weakly interconnected._
- **Why does `lucide-react` connect `Chat Composer` to `App Navigation`, `UI Components`, `App Layout`, `Dropdown Menus`, `UI Primitives`, `Dashboard UI`, `Search Components`, `Chat Page`, `Repo Commands`, `Message UI`, `Dependencies`, `Overview Dashboard`, `Context Menus`, `Carousel Components`, `Repo Status`, `Error Alerts`, `Toasts`, `Package Config`, `Navigation Menus`, `Select Components`?**
  _High betweenness centrality (0.020) - this node is a cross-community bridge._
- **Should `UI Components` be split into smaller, more focused modules?**
  _Cohesion score 0.07400555041628122 - nodes in this community are weakly interconnected._