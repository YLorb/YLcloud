# YLCloud Frontend UI/UX Complete Redesign Plan

## Summary

Rebuild the complete `cloud-frontend` presentation and interaction layer on the `frontend-ui-ux` branch while preserving the existing backend API contracts and user-visible business capabilities. The redesign will establish a route-driven React application, a reusable accessible component system, explicit server-state management, responsive layouts, and automated UI validation.

## Type

Refactor + Enhancement

## Source Issue/Task

User request: create branch `frontend-ui-ux`, replace the existing frontend completely, use UI-UX Pro Max, and communicate the technology stack and implementation approach before design work begins.

## Original Requirements

| # | Requirement | Plan Step(s) |
|---|---|---|
| 1 | Create the `frontend-ui-ux` branch from the frontend state synchronized with `origin/dev`. | Completed prerequisite |
| 2 | Completely redesign the existing frontend instead of applying incremental cosmetic patches. | Steps 2-11; Removal Specification |
| 3 | Use UI-UX Pro Max as the design methodology and quality gate. | Steps 1, 2, 11 |
| 4 | Communicate the technology stack and approach before changing the design. | Current planning turn; Step 1 gate |
| 5 | Preserve all currently implemented business capabilities and backend integrations. | Steps 3-10, 12 |
| 6 | Fix structural UX weaknesses exposed by the current implementation: manual routing, oversized components, non-semantic interactions, inconsistent feedback, and weak responsive behavior. | Steps 3-11 |
| 7 | Produce an accessible, responsive, maintainable frontend suitable for files, Spaces, RAG knowledge management, AI chat, tasks, and administration. | Steps 2-12 |

**Coverage Check**: 7 of 7 requirements mapped.

## Status

Todo. Rename to `.done.md` only after implementation and validation are complete.

## Context

The project is a knowledge-asset cloud product combining personal file management, collaborative Spaces, RAG knowledge bases, knowledge profiling, AI conversations, background task operations, sharing, and administrative settings. Its UI must support both content-heavy operational work and clear status/error recovery.

## Current State

- React 18.3.1, TypeScript strict mode, and Vite are already in use.
- Navigation is implemented manually with `window.history`, pathname parsing, and a custom navigation guard in `src/App.tsx`.
- Server data is fetched directly from components through a 21 KB `src/api.ts` module.
- `src/styles.css` is approximately 125 KB and more than 7,000 lines.
- `DriveApp.tsx`, `SpacesView.tsx`, and `KnowledgeBaseView.tsx` are 35-55 KB page modules containing many unrelated responsibilities.
- There is no component test runner, browser E2E suite, accessibility test, lint command, or component catalogue.
- Reusable controls are mainly conventions expressed through global CSS class names rather than typed components.
- Existing domain types in `src/types.ts` cover the majority of backend contracts and should be retained.

## Desired State

- Route-based SPA with nested layouts, deep links, route errors, lazy loading, and predictable back behavior.
- A token-driven light/dark design system, responsive at 375, 768, 1024, and 1440 px.
- Accessible local UI primitives with semantic HTML, visible focus, correct dialogs, and reliable keyboard interaction.
- Server state separated from local UI state, with consistent caching, polling, mutation feedback, and recovery.
- Feature modules with small page, query, mutation, and component boundaries.
- Full capability parity with the existing frontend before old UI code is removed.
- Automated unit, integration, accessibility, and E2E validation.

## UI-UX Pro Max Direction

The preliminary UI-UX Pro Max query recommends a balanced modern, accessibility-focused Soft UI Evolution direction:

- Product: productivity SaaS combining cloud drive, knowledge operations, and AI assistance.
- Density: 7/10 for operational dashboards without visual crowding.
- Variance: 5/10 for a recognizable but professional interface.
- Motion: 4/10 with restrained 150-300 ms micro-interactions and reduced-motion support.
- Draft palette: calm indigo primary, semantic success green, neutral layered surfaces, explicit destructive red.
- Draft typography: Plus Jakarta Sans with Chinese/system-font fallbacks.

This direction is provisional until user approval. After approval, persist `design-system/MASTER.md` and page-specific overrides through UI-UX Pro Max before implementation.

## CLAUDE.md / AGENTS.md Requirements

No `CLAUDE.md` or `AGENTS.md` files exist in the repository at planning time. Apply repository conventions already visible in the codebase:

- TypeScript strict mode remains enabled.
- Feature directories use kebab-case names and React components use PascalCase.
- API payload and response contracts remain explicitly typed.
- Existing backend endpoint paths and authentication storage behavior remain compatible unless a verified contract defect requires a coordinated change.

## Proposed Technology Stack

### Retain

- React 18.3.1: avoid coupling the visual rewrite to a framework-major upgrade.
- TypeScript strict mode: domain contracts and component props remain explicit.
- Vite: preserve the existing SPA build and Docker deployment model.
- `hash-wasm` and Web Worker hashing: preserve large-file upload behavior.
- Lucide React: keep one consistent SVG icon family; do not mix emoji or multiple stroke systems.

### Add

- React Router in Data Mode: nested routes, layouts, lazy route modules, route errors, navigation blockers, and deep linking.
- TanStack Query: query caching, deduplication, background refresh, task polling, mutation invalidation, and server-state status.
- Tailwind CSS with CSS custom-property tokens: systematic responsive styling without returning to a monolithic global stylesheet.
- shadcn/ui-style local components backed by accessible primitives: Button, Dialog, Sheet, Dropdown, Tabs, Table, Tooltip, Select, Toast, Skeleton, Progress, and Sidebar.
- React Hook Form + Zod: controlled forms, reusable schemas, inline validation, and typed payload construction.
- TanStack Table and TanStack Virtual: accessible operational tables and virtualization for large file/task/document collections.
- Recharts only for knowledge/RAG analytics that materially benefit from visualization; always include a textual/table alternative.
- Vitest + React Testing Library + MSW: component, hook, query, and API-state tests.
- Playwright: real-browser critical-path and responsive E2E tests.
- axe integration: automated accessibility regression checks.

### Explicitly Avoid

- Redux for server data; TanStack Query handles server state and URL/local state handles most UI state.
- A second frontend framework or SSR migration during this work.
- Mixing multiple component libraries.
- Keeping the old and new UI active behind a feature flag.
- Page-specific hard-coded color systems.
- Decorative GSAP page wipes; motion should communicate state and remain interruptible.

## State Ownership

- URL: active feature, Space ID, knowledge section, folder, filters, sorting, pagination, and selected resource where deep linking is useful.
- TanStack Query: files, Spaces, members, RAG documents/config/tasks, profiles, analytics, settings, and sessions.
- Local component state: open menus, draft input, transient selection, dialog state.
- Small app context/store only where truly cross-cutting: authenticated user/session, upload queue, theme, notices, and unsaved-change guard.
- No duplicate copies of server data in global client stores.

## Existing Types

### Types to Reuse

- `User`, `PublicSiteSettings`, `SiteSetting` from `src/types.ts`.
- `FileItem`, `FilePreview`, `ChunkUploadInit`, `ChunkStatus`, `FileVersion`, `ShareFile`.
- `Space`, `SpaceFile`, `SpaceMember`.
- `RagConfig`, `RagDocument`, `SpaceDocumentSearch`, `RagTask`, `RagCitation`, `RagQuery`.
- `KnowledgePipelineTask`, `KnowledgePipelineEvent`, `KnowledgeDocument`, `KnowledgeDashboard`, `KnowledgeProfile`, version and diff types.
- `AsyncTask`, analytics types, chat message/session types, and `StorageQuota`.

### Types to Refine or Create

- Route parameter schemas for Space, folder, document, task, and chat identifiers.
- Query-key factory types per domain.
- Typed mutation variables for file, Space, RAG, profile, task, and settings actions.
- `AppNotice`, `UploadQueueItem`, `NavigationSection`, and design-token theme types.
- Discriminated task view model for RAG and knowledge-profile retry actions.

### Type Guidelines

- Do not add `any`.
- Use `unknown` only at external JSON boundaries, immediately narrowed by a schema or type guard.
- Reuse backend DTO types where the shape is already correct.
- Do not maintain duplicate feature-local copies of the same API contract.

## Target Information Architecture

### Global Shell

- Desktop/tablet: collapsible primary sidebar, top context bar, route content, global upload/task tray.
- Mobile: compact top bar and no more than five top-level destinations; secondary destinations live in a More sheet.
- Global destinations: My Files, Spaces, Knowledge, AI Assistant, Tasks, Administration (admin only).
- Account, theme, storage quota, and logout are spatially separated from primary navigation.

### Route Groups

- `/login`, `/sign`
- `/share/:shareCode`
- `/files`, `/files/folder/:folderId`, `/files/recycle`
- `/spaces`, `/spaces/:spaceId/files`, `/spaces/:spaceId/members`, `/spaces/:spaceId/settings`
- `/knowledge/:spaceId/overview`, `/documents`, `/pipeline`, `/analytics`, `/settings`
- `/assistant`, `/assistant/:sessionId`
- `/tasks`
- `/admin/settings`

## Impact Analysis

### Files to Modify or Replace

- `cloud-frontend/package.json` and lockfile: dependencies and test/lint scripts.
- `cloud-frontend/vite.config.ts`, `tsconfig.json`: aliases, testing, and build configuration.
- `src/main.tsx`, `src/App.tsx`: provider and router entry.
- `src/api.ts`: split into transport plus domain clients, then remove the monolith.
- `src/types.ts`, `src/appTypes.ts`: retain domain contracts and remove obsolete view-routing types.
- Every feature page under `src/features`: replace page structure and interaction implementation.
- `src/styles.css`: remove after token, base, and component styles fully replace it.

### Files and Directories to Create

- `src/app/`: router, providers, layouts, error boundaries, session and theme.
- `src/components/ui/`: local accessible primitives.
- `src/components/patterns/`: page header, data state, status badge, task progress, file browser, detail drawer.
- `src/lib/api/`: typed transport and domain API modules.
- `src/lib/query/`: query client and query-key factories.
- `src/styles/`: tokens, base styles, theme mappings, motion and print/accessibility rules.
- Feature-local `api`, `queries`, `mutations`, `components`, and `routes` modules.
- `src/test/` and `e2e/`: fixtures, MSW handlers, accessibility tests, and Playwright scenarios.
- `design-system/MASTER.md` and page overrides after approval.

### Dependencies Affected

- Docker/Nginx must continue serving SPA route fallbacks.
- Backend endpoints remain unchanged; deviations discovered during contract tests must be documented and coordinated.
- Upload hashing, multipart resume, preview/download, streaming chat, and polling behavior must remain operational.

### Breaking Changes

- Internal component names, CSS classes, view-state types, and route implementation will be replaced.
- User-facing URLs will become canonical and deep-linkable. Existing supported paths will redirect to the new canonical routes where necessary.
- No backend API breaking change is planned.

## Implementation Steps

### Step 1: Approve the Stack and Design Direction

**Action**: Review this plan and the preliminary UI-UX Pro Max direction with the user.

**Acceptance**:

- Technology stack approved or adjusted.
- Visual direction, density, light/dark scope, and navigation model approved.
- No page implementation begins before approval.

### Step 2: Persist the Design System

**Action**: Run UI-UX Pro Max with `--design-system --persist` and create page overrides for auth, files, knowledge, assistant, tasks, and admin.

**Details**:

- Define semantic color, typography, spacing, radii, elevation, icon, motion, breakpoint, z-index, and density tokens.
- Verify WCAG AA contrast in light and dark themes.
- Define component states before page composition.

### Step 3: Establish Application Infrastructure

**Files**: `src/app/*`, `src/main.tsx`, Vite and TypeScript config.

**Action**: Install providers and route infrastructure, route-level lazy loading, error boundaries, auth guards, navigation blockers, and document titles.

**Why**: Removes manual pathname parsing and provides predictable navigation before pages are rebuilt.

### Step 4: Establish the Component System

**Files**: `src/components/ui/*`, `src/components/patterns/*`, `src/styles/*`.

**Action**: Implement tokens and accessible primitives first; create no page-specific substitute controls.

**Required primitives**:

- Button, IconButton, Input, Textarea, Select, Checkbox, Switch.
- Dialog, AlertDialog, Sheet, DropdownMenu, Popover, Tooltip.
- Tabs, Breadcrumb, Sidebar/NavItem, Table, Pagination, Skeleton, EmptyState.
- Toast/Notice region, inline Alert, Progress, StatusBadge, TaskProgress.

### Step 5: Split API and Server-State Modules

**Files**: `src/lib/api/*`, `src/lib/query/*`, feature query and mutation modules.

**Action**: Keep the existing request/auth behavior but divide endpoints by domain. Define query keys, invalidation rules, retries, cancellation, and polling centrally.

**Why**: Pages should compose data states, not implement ad-hoc fetching and synchronization.

### Step 6: Rebuild Authentication and Public Sharing

**Features**: login, registration, public settings branding, share preview/download.

**Acceptance**:

- Field-level validation and accessible errors.
- Session redirect behavior and route guards verified.
- Public share route remains usable without authentication.

### Step 7: Rebuild My Files and Upload Workflows

**Features**: list/grid view, folder navigation, file tree, selection, context actions, upload queue, multipart pause/resume, preview, download, share, move/copy, recycle bin, and add-to-Space/knowledge workflow.

**Acceptance**:

- File tree expands and collapses correctly.
- URL preserves folder/filter/view state.
- Large collections virtualize without losing keyboard selection.
- Destructive actions confirm and recover with clear feedback.

### Step 8: Rebuild Spaces and Collaboration

**Features**: Space list, files, upload/import/link, versions, members, roles, settings, RAG entry points.

**Acceptance**:

- Role-sensitive actions are visible and explained.
- Add-document flow shows parse/index progress and terminal outcomes.
- Version operations and unsaved settings use reliable navigation guards.

### Step 9: Rebuild Knowledge and RAG Operations

**Features**: overview, documents, pipeline, profile review, categories/tags, RAG configuration, analytics, vector repair and retry.

**Acceptance**:

- RAG indexing and knowledge profiling are visually distinct stages.
- Failed documents are not presented as successfully added.
- Every failure state exposes the correct retry or recovery action.
- Task polling stops at terminal states and summarizes success/failure counts.

### Step 10: Rebuild AI Assistant and Task Operations

**Features**: session sidebar, multi-Space scope, streamed responses, citations, feedback, task list/detail, RAG and profile retries.

**Acceptance**:

- Streaming, cancellation, no-answer, and citation states remain clear.
- Task actions are semantic buttons, never nested interactive controls.
- RAG tasks call RAG retry; profile tasks call profile retry.
- Mobile composer and task details remain operable at 375 px.

### Step 11: Rebuild Administration

**Features**: grouped settings, validation, secret handling, dirty state, save summaries, deployment-relevant warnings.

**Acceptance**:

- Labels and helper text remain visible.
- Errors appear beside the affected setting and in an accessible summary.
- Unsaved changes block navigation with a real dialog and restore focus.

### Step 12: Remove the Old UI

**Action**: Delete obsolete components, route/view parsing, duplicated knowledge UI, unused CSS classes, and the monolithic stylesheet/API module only after replacement imports exist.

**Verification**: Search for obsolete symbols and class names; no fallback old UI remains.

### Step 13: Validate and Document

**Action**: Run TypeScript, lint, unit/integration tests, browser E2E, axe, production build, and responsive visual checks.

**Viewport matrix**: 375, 768, 1024, 1440 px; light/dark; reduced motion; keyboard-only navigation.

**Critical E2E flows**:

- Login and logout.
- File upload, multipart resume, folder navigation and preview.
- Add document to Space/knowledge and observe success/failure.
- RAG query with citations and no-answer.
- Retry RAG task and retry profile task.
- Collapse/expand file navigation.
- Space membership and settings permissions.
- Admin setting validation and unsaved-change protection.

## REMOVAL SPECIFICATION

### Code to Remove

#### `src/App.tsx`

- Manual `window.history`, `pathRef`, pathname matching, and custom route selection.
- Replacement: React Router app routes, loaders/guards, and blockers from Steps 3 and 6.

#### `src/features/files/DriveApp.tsx`

- Monolithic global shell, sidebar, file page, dialogs, upload state, and route parsing.
- Replacement: app layout, file route modules, reusable dialogs, upload store, and query/mutation modules.

#### `src/features/spaces/SpacesView.tsx`

- Monolithic Space selection, file management, versions, membership, and modal implementations.
- Replacement: nested Space routes and feature components.

#### `src/features/knowledge-base/KnowledgeBaseView.tsx` and `src/features/knowledge/KnowledgeOpsView.tsx`

- Duplicated status mapping, tiles, profile drawers, task handling, and overlapping knowledge views.
- Replacement: one canonical knowledge feature with route-specific modules.

#### `src/features/async/AsyncTasksView.tsx`

- Generic normalization that erases the RAG source and the nested pseudo-button retry interaction.
- Replacement: discriminated task model and semantic action components.

#### `src/api.ts`

- Monolithic endpoint namespace after all consumers move to domain clients.
- Replacement: typed transport plus auth/files/spaces/rag/knowledge/tasks/admin modules.

#### `src/styles.css`

- All legacy global page styling after token/base/component/feature styles are active.
- Replacement: Tailwind utilities, semantic CSS variables, and narrowly scoped base rules.

### Removal Checklist

- [ ] Manual routing and view parsing removed.
- [ ] Old shell/sidebar removed.
- [ ] Duplicate knowledge operations view removed.
- [ ] Legacy dialogs and pseudo-buttons removed.
- [ ] Monolithic API module removed.
- [ ] Monolithic stylesheet and unused classes removed.
- [ ] Old imports and dead types removed.
- [ ] No legacy/new parallel UI path remains.

## Anti-Patterns to Avoid

- Do not create `*-v2`, `*-new`, `*-modern`, or temporary migration components.
- Do not keep old pages behind a feature flag.
- Do not copy server state into a generic global store.
- Do not implement clickable `div`/`span` controls.
- Do not mix Lucide with emoji or another icon family at the same hierarchy.
- Do not use color as the only status indicator.
- Do not hard-code page-specific colors outside semantic tokens.
- Do not introduce animations that block input or ignore reduced-motion.
- Do not silently swallow API errors.
- Do not redesign away existing business actions.

## Validation Criteria

### Pre-Implementation

- [x] Requirements extracted and mapped.
- [x] Current frontend architecture audited.
- [x] Existing types and endpoint surface identified.
- [x] CLAUDE.md/AGENTS.md presence checked.
- [ ] Stack and visual direction approved by user.
- [ ] UI-UX Pro Max design system persisted.

### Post-Implementation

- [ ] All feature routes and capability-parity matrix complete.
- [ ] Old UI removed per Removal Specification.
- [ ] TypeScript strict compilation passes.
- [ ] ESLint passes with no ignored feature files.
- [ ] Vitest/Testing Library/MSW suite passes.
- [ ] Playwright critical E2E suite passes.
- [ ] Automated axe checks pass with no serious/critical violations.
- [ ] Production build passes.
- [ ] No new `any`; justified/narrowed `unknown` only at external boundaries.
- [ ] Keyboard-only flows and focus restoration verified.
- [ ] Light/dark contrast verified.
- [ ] 375/768/1024/1440 responsive matrix verified.
- [ ] Reduced-motion behavior verified.
- [ ] No horizontal overflow or content hidden behind fixed navigation.

## Final Audit Recommendation

Before implementation, capture a capability-parity checklist from every current screen and backend endpoint. During implementation, review each completed route against UI-UX Pro Max accessibility, interaction, performance, navigation, and error-recovery rules rather than waiting for a final cosmetic audit.
