# AMIA Admin Panel — Implementation Specification for Claude Code

**Date:** September 21, 2026  
**Status:** Ready for handoff  
**Reference Design:** `/Claude outputs/amia-admin-panel-prototype/build/amia-admin-panel.html` (1,404 lines, self-contained HTML/CSS/JS with complete AMIA styling)

---

## Executive Summary

The AMIA admin panel prototype has been designed and delivered as a clickable, fully-styled HTML reference. This spec guides Claude Code to integrate that design into the live backend, replacing the current four-tab layout (`Setup`, `Chat`, `Test Chat`, `Observability`) with a sidebar-navigation layout encompassing all nine admin modules.

**Current state:** Only the `Observability` tab has been styled with AMIA colors. Backend Admin routes exist and are wired; the UI modules are ready to be built.

**Deliverable:** Replace `src/main/resources/admin-ui.html` with sidebar-nav structure, apply AMIA styling globally, and wire each module to its corresponding backend endpoints.

---

## AMIA Brand System (Reference)

### Color Palette
- **Primary Dark:** `#190611` (near-black plum) — page background, text
- **Dark Accent:** `#2A0B1C` (deep plum) — sidebar, card borders, secondary backgrounds
- **Burgundy:** `#4A102C` — table headers, active states
- **Rose:** `#E85A88` — primary interactive elements, hover states
- **Soft Rose:** `#F3A0B7` — badges, lighter accents
- **Blush:** `#F7D8E1` — subtle backgrounds, lighter UI elements
- **Warm White:** `#FFF8FB` — card backgrounds, text backgrounds
- **Muted Mauve:** `#CDBBC4` — borders, dividers, disabled states

### Typography
- **Headers (H1–H3):** Cormorant Garamond, 600 weight, tracking 0.5px
- **Body Text & UI:** Inter, 400 weight, 14px baseline
- **Small Labels:** Inter, 500 weight, 12px

### Branding Assets
- `assets/amia-emblem.png` (circular badge) — navbar, headers
- `assets/amia-logo-lockup.png` (wordmark) — page title area

---

## Architecture: Sidebar Navigation Model

### Current State
- **Layout:** Four tab buttons at top of page (`Setup`, `Chat`, `Test Chat`, `Observability`)
- **Content:** Single switching view based on active tab
- **File:** `src/main/resources/admin-ui.html` (2,447 lines)

### Target State
- **Layout:** Left sidebar (200px fixed width) + main content area
- **Sidebar Items:** Nine module buttons + logo + logout (if applicable)
- **Navigation:** Click a sidebar item → load that module's view into main area
- **Persistent:** Sidebar visible across all modules; active module highlighted
- **Page Title:** Change from "Pink Dreams QA Console" to "AMIA Admin Console"

### Sidebar Structure (Top to Bottom)
1. **Logo Section** (60px height) — AMIA emblem + wordmark
2. **Module Navigation** (9 buttons, ~30px each):
   - Engines
   - Skills
   - Personas
   - System Configuration
   - Users
   - Test Users
   - Conversations
   - Test Chat
   - Revenue & Transactions
3. **Dashboard** (optional separate button, per design)
4. **Spacer/Footer** (if logout or settings button needed)

---

## Module-by-Module Implementation

### 1. Engines Module

**Backend Route:** `AdminEngineRoutes.kt` (~366 lines)

**UI Elements (from prototype):**
- Two sections: "Production" and "Testing"
- Production: Shows `is_active=true` row; one "Make Testing" button
- Testing: Shows newest `status="published"` row (not active); one "Make Production" button
- Each row displays: name, version tag, created date, last modified date
- Edit button → inline form or modal to update name/description
- Version history table showing past versions

**Auto-Versioning Behavior:**
- When user clicks "Edit" and saves changes, backend automatically calls `createNextVersion()` (already implemented)
- UI should display success message and refresh the version displays
- No manual "bump version" button needed; edit → save → auto-bumped

**Endpoints to Wire:**
- `GET /v1/admin/engines` → Populate both Production and Testing sections
- `PUT /v1/admin/engines/{id}` → Edit endpoint, triggers auto-versioning on backend
- `POST /v1/admin/engines/{id}/publish` → Activate Testing version as new Production
- `POST /v1/admin/engines/{id}/archive` → Optional: retire old versions

**Styling:**
- Use AMIA palette: Dark plum sidebar for section headers (#2A0B1C), rose highlights for buttons (#E85A88), blush for inactive version cards (#F7D8E1)
- Version tag styling: Production = burgundy background (#4A102C), Testing = soft rose (#F3A0B7)

---

### 2. Skills Module

**Backend Route:** `AdminSkillRoutes.kt` (~366 lines)

**UI Elements (from prototype):**
- Identical layout to Engines: "Production" vs "Testing" sections
- Each skill row: name, trigger-pattern, description, version tag
- Edit button → inline form to update name/trigger-pattern/description
- Auto-versioning on save (same as Engines)
- Usage analytics: "Used in X% of chats in last 30 days" (currently dummy; flagged in prototype)

**Endpoints to Wire:**
- `GET /v1/admin/skills` → Populate both Production and Testing sections
- `PUT /v1/admin/skills/{id}` → Edit endpoint, auto-versioning on backend
- `POST /v1/admin/skills/{id}/publish` → Activate Testing version
- `POST /v1/admin/skills/{id}/archive` → Optional: retire versions

**Styling:** Same as Engines — Production/Testing badges with burgundy/soft rose, pale backgrounds

---

### 3. Personas Module (Multi-Tab)

**Backend Route:** `AdminPersonaRoutes.kt` (~366 lines)

**Sub-Tabs:**
1. **Core**
   - Read-only display of: display name, gender, orientation, apparent age, language profile
   - Edit button → modal form (fields exist; `PUT /v1/admin/personas/{id}` endpoint wired)
   - Auto-versioning on save

2. **Visual**
   - Profile image + 4-image generate-and-pick flow
   - Backend: `ImageJobs` + `GeneratedCandidates` tables (already implemented)
   - UI: Show current image, "Generate New" button → fetch 4 candidates → user picks one → save
   - No new backend work needed; flow exists

3. **Posts** (Schema Gap — Flagged)
   - Table: Cover photo, title, free/premium indicator, price (if premium)
   - Columns: title, status (draft/published), engagement (likes, comments, shares), created date
   - Edit/delete buttons
   - **Backend work needed:** `persona_posts` table + routes (see "Schema Gaps" section below)
   - **UI approach for now:** Display mockup with dummy data; add flag-note: "Waiting on persona_posts schema"

4. **Analytics**
   - Charts: Conversation frequency, skill usage, session duration
   - Filters: Date range, gender/orientation filters
   - **Currently dummy data — flagged in prototype as acceptable for v1**
   - UI stub with sample numbers; flag-note: "Analytics data to be wired to backend"

5. **Filters** (Sidebar or Header)
   - Gender filter (multi-select)
   - Orientation/Sexuality filter (multi-select)
   - Tags filter (multi-select, autocomplete)
   - **Backend work needed:** `tags`, `bio`, `city`, `occupation`, `interests` columns on `Personas` table (currently missing)

**Endpoints to Wire:**
- `GET /v1/admin/personas` → Populate personas list
- `GET /v1/admin/personas/{id}` → Load selected persona's core data
- `PUT /v1/admin/personas/{id}` → Edit core fields, auto-versioning
- `GET /v1/admin/personas/{id}/core-versions` → Version history
- `POST /v1/admin/personas/{id}/publish` → Activate Testing version
- `GET /v1/admin/personas/{id}/generated-images` → Fetch 4-candidate images
- `POST /v1/admin/personas/{id}/select-image` → Pin chosen image

**Styling:** Same AMIA palette; tabs styled as rose (#E85A88) for active, muted mauve (#CDBBC4) for inactive

---

### 4. System Configuration Module

**Backend Routes:**
- `AdminAiSettingsRoutes.kt` (wired, live-editable)
- `AdminIntentEngineRoutes.kt` (partially wired)

**UI Elements (from prototype):**
- Four sections:
  1. **AI Engine Settings** (LLM model, temperature, max-tokens, JSON mode)
  2. **Intent Discovery** (model, prompt, JSON mode toggle, max output tokens)
  3. **Generation Provider Sort Order** (drag-reorderable list)
  4. **Admin Allowlist** (list of admin user IDs; flagged as needs DB backing)

**Current Implementation Status:**
- Model, temperature, max-tokens are now DB-backed (commit `bfeff10`), live-editable without restart
- **Still needs:** Intent discovery model/JSON/max-output live-editing (check if commit `bfeff10` includes this; if not, it's next)
- **Schema gap:** Admin allowlist currently env-var; needs `AdminAuthorization` table if editable from UI

**Endpoints to Wire:**
- `GET /v1/admin/settings/ai` → Load current AI settings
- `PUT /v1/admin/settings/ai` → Update AI settings, live-reload
- `GET /v1/admin/settings/intent-engine` → Load intent discovery config
- `PUT /v1/admin/settings/intent-engine` → Update intent settings, live-reload
- `PUT /v1/admin/settings/provider-sort` → Reorder generation providers
- `GET /v1/admin/settings/admin-allowlist` (if DB-backed) → Load admin user IDs
- `PUT /v1/admin/settings/admin-allowlist` (if DB-backed) → Edit allowlist

**Styling:** Card-based layout; each setting section in a bordered card with rose accents on save-button hover

---

### 5. Users Module

**Backend Route:** `AdminUserRoutes.kt` (exists)

**UI Elements (from prototype):**
- Three-section layout:
  1. **User Search** (top bar with filters: gender, interest, city, age range)
  2. **User Table** (columns: name, city, age, gender, interest, joined date, last active)
  3. **Memory Search** (separate section: search across user memories by keyword)

**Features:**
- Click a user row → expand or navigate to detail view showing:
  - Profile info (name, gender, interest, city, age)
  - Conversation count, last active date
  - Export memory button (UI-only in prototype; export endpoint may or may not exist)
  - Delete user button

**Endpoints to Wire:**
- `GET /v1/admin/users?gender=X&interest=Y&city=Z&ageMin=A&ageMax=B` → Search users with filters
- `GET /v1/admin/users/{id}` → Load user detail
- `GET /v1/admin/users/{id}/memories?query=keyword` → Search user's memories
- `DELETE /v1/admin/users/{id}` → Delete user (if endpoint exists)
- `POST /v1/admin/users/{id}/export` → Export memory/chat (if endpoint exists; otherwise flag as "UI only")

**Styling:** Table with alternating row backgrounds (warm white #FFF8FB / blush #F7D8E1), rose hover states

---

### 6. Test Users Module

**Backend Routes:** Assumed to exist alongside `AdminUserRoutes`

**UI Elements (from prototype):**
- Similar to Users, but filtered to `is_test=true` records only
- Create Test User button → form with fields: displayName, gender, interest, city, age
- Wired to existing `POST /v1/admin/users` endpoint with `is_test=true` flag

**Schema Gap:**
- `Users` table currently has no `is_test` boolean
- Needed to distinguish test users from real users in all queries

**Endpoints to Wire:**
- `GET /v1/admin/users?is_test=true` → List test users only
- `POST /v1/admin/users` with `is_test=true` → Create test user
- `DELETE /v1/admin/users/{id}` → Delete test user

**Styling:** Same as Users module

---

### 7. Conversations Module (Triple-Pane Layout)

**Backend Routes:** Assumed to be chat-retrieval endpoints (may be existing `/chat` routes adapted for admin view)

**UI Elements (from prototype):**
Three panes side-by-side (or responsive tabs on mobile):
1. **Left Pane: Conversation List**
   - Search bar (by user name or date)
   - Filters: date range, skill used, persona
   - List of conversations: User name, timestamp, last message snippet, click to load into center pane

2. **Center Pane: Chat Transcript**
   - Full conversation flow, user message → persona response
   - Click any persona message → loads API request/response into right pane
   - Each message shows timestamp, confidence score (if applicable)

3. **Right Pane: API Request/Response Inspector**
   - Loads when user clicks a persona message in center pane
   - Shows:
     - Request JSON (what was sent to LLM)
     - Response JSON (what LLM returned)
     - LLM execution details (model, tokens used, latency)
   - Wired to `Messages.metadata` storing `LlmExecutionDiagnostics` / `ProviderExchange` (already implemented)

**Lower Pane (Optional): Chat Memories**
- Shows user's stored memories for this conversation
- Read-only display

**Endpoints to Wire:**
- `GET /v1/admin/conversations?user_id=X&date_range=Y` → List conversations
- `GET /v1/admin/conversations/{id}` → Load full chat transcript
- `GET /v1/admin/conversations/{id}/messages/{message_id}/metadata` → Load LLM execution details

**Styling:** Dark sidebar (#2A0B1C) for conversation list, warm-white (#FFF8FB) for chat pane, JSON in monospace font with syntax highlighting

---

### 8. Test Chat Module

**Backend Route:** `AdminTestChatRoutes.kt` (exists; snapshot-based config)

**UI Elements (from prototype):**
- Two-section layout:
  1. **Configuration** (top)
     - Dropdown: Select a persona from a list
     - Toggle: "Use Testing Versions" (checkbox)
     - Display current snapshot of: Engine version (Testing/Production), Persona core version (Testing/Production), Skill list (with versions)
     - Start Chat button
  
  2. **Chat Interface** (below)
     - Message input box + send button
     - Chat transcript showing your messages and persona responses
     - Each response shows: confidence, skill used (if applicable), execution time

**Testing Versions Flag (Architectural):**
- When "Use Testing Versions" is toggled ON, the Test Chat session should:
  - Load the latest `status="published"` (not `is_active`) version of the persona core, engine, and all skills
  - Current blocker: Pipeline currently always loads `is_active=true` rows; needs a flag threaded through `ChatRequest` → `RepositoryContextAssembler` → skill-selection to alternate to "testing" mode
  - This is the most structurally significant gap (item #9 in integration notes)

**Current Implementation:**
- Commit `da09264` ("Implement Phase D baseline config, ADMIN-2, and ADMIN-3 Test Chat") suggests Test Chat is already built
- Check whether the "Use Testing Versions" flag is wired to the pipeline

**Endpoints to Wire:**
- `GET /v1/admin/test-chat/config` → Load current snapshot
- `POST /v1/admin/test-chat/config` → Update snapshot (persona, testing-flag)
- `POST /v1/admin/test-chat/send?use_testing=true/false` → Send message, respecting testing-flag

**Styling:** Rose-accented chat bubbles for Test Chat (distinguish from regular Chat tab), toggle switch with AMIA colors

---

### 9. Revenue & Transactions Module

**Backend Routes:** Assumed to not exist yet (payments schema is entirely absent)

**UI Elements (from prototype):**
1. **Dashboard Summary** (top cards)
   - Total Revenue (MTD / YTD)
   - Transaction Count (MTD / YTD)
   - ARPU (Average Revenue Per User)
   - Churn Rate (%)

2. **Revenue Chart** (line chart over time)
   - X-axis: Date, Y-axis: Revenue
   - Aggregated by day/week/month (selector)

3. **Transactions Table**
   - Columns: Transaction ID, User ID, Amount, Type (subscription / micro), Date, Status
   - Filters: Date range, user, type, status
   - Click a row → detail view (payment method, recurring?, renewal date)

**Schema Gap:**
- No `Payments` or `Transactions` table exists
- No schema for subscriptions, pricing tiers, or revenue tracking
- **UI approach for now:** Display mockup with dummy data; add flag-note: "Waiting on payments schema + backend implementation"

**Endpoints to Wire (Future):**
- `GET /v1/admin/revenue/summary` → Dashboard metrics
- `GET /v1/admin/revenue/by-date?start=X&end=Y` → Revenue trend data
- `GET /v1/admin/transactions?user_id=X&type=Y&status=Z` → Transaction list
- `GET /v1/admin/transactions/{id}` → Transaction detail

**Styling:** Green accents for revenue positive indicators, card-based dashboard layout

---

### 10. Dashboard Module

**UI Elements (from prototype):**
- Top-level overview of admin panel health
- Cards:
  1. Active Personas
  2. Active Skills
  3. Total Users
  4. Test Users
  5. Conversations (24h)
  6. Average Session Duration
  7. Top Skill (by usage %)
  8. Top Persona (by conversation count)
- Mini charts: User growth, conversation volume, skill distribution

**Implementation Status:**
- All data currently dummy (flagged as acceptable in original request)
- Backend endpoints can be wired when available; for now, display mock numbers

**Endpoints to Wire (Future):**
- `GET /v1/admin/dashboard` → Aggregate metrics

**Styling:** Six-column grid of large stat cards, rose accent borders on top of each card

---

## Styling Implementation Guide

### Global CSS Structure
1. **CSS Custom Properties** (root scope) — define AMIA palette colors
   ```css
   :root {
     --amia-dark: #190611;
     --amia-dark-accent: #2A0B1C;
     --amia-burgundy: #4A102C;
     --amia-rose: #E85A88;
     --amia-soft-rose: #F3A0B7;
     --amia-blush: #F7D8E1;
     --amia-warm-white: #FFF8FB;
     --amia-muted-mauve: #CDBBC4;
   }
   ```

2. **Typography Defaults**
   - `body`: Inter, 14px, `--amia-dark` text, `--amia-warm-white` background
   - `h1, h2, h3`: Cormorant Garamond, 600 weight, tracking 0.5px
   - `.small-label`: Inter 500, 12px

3. **Component Styling**
   - **Sidebar:** `background: --amia-dark-accent`, text `--amia-warm-white`
   - **Active nav item:** Border-left `--amia-rose`, background slightly lighter
   - **Buttons (Primary):** Background `--amia-rose`, text `--amia-warm-white`, hover darken by 10%
   - **Buttons (Secondary):** Background `--amia-muted-mauve`, text `--amia-dark`
   - **Table headers:** Background `--amia-burgundy`, text `--amia-warm-white`
   - **Table rows:** Alternating `--amia-warm-white` / `--amia-blush`
   - **Cards:** Background `--amia-warm-white`, border 1px `--amia-muted-mauve`
   - **Badge (Production):** Background `--amia-burgundy`, text white, 8px padding, border-radius 4px
   - **Badge (Testing):** Background `--amia-soft-rose`, text white, 8px padding, border-radius 4px
   - **Inputs:** Background `--amia-blush`, border 1px `--amia-muted-mauve`, focus border `--amia-rose`

4. **Observability Tab**
   - Already styled with AMIA colors (commit `eb0f84c`)
   - Keep as-is OR unify with rest of panel (no changes needed either way)

### Mockup-to-Implementation Conversion

**Reference file:** `/Claude outputs/amia-admin-panel-prototype/build/amia-admin-panel.html`

The prototype includes:
- All HTML structure (sidebar nav, 9 module view divs)
- All CSS scoped correctly per module
- JavaScript event listeners for tab switching
- Mock `DB` object (in-file, not connected to backend)

**Conversion strategy:**
1. Copy the HTML structure (sidebar, module divs, navbar)
2. Copy all CSS custom properties and component styles
3. **Replace the mock `DB` object with real backend API calls:**
   - Instead of `DB.personas.filter(...)`, call `GET /v1/admin/personas` via fetch/axios
   - Instead of updating `DB.personas[0].name = ...`, call `PUT /v1/admin/personas/{id}` via fetch/axios
   - Each module's render function should fetch data on module load, not read from `DB`
4. Wire event listeners to backend endpoints
5. Add loading spinners and error messages for async operations
6. Test against live backend

---

## Schema Gaps & Flag-Notes

The prototype contains 26 inline `.flag-note` callouts and HTML comments marking:

### Can Ship As-Is (No Backend Changes Needed)
- ✓ Engines versioning (Production/Testing)
- ✓ Skills versioning
- ✓ Personas core versioning
- ✓ 4-image generate-and-pick flow
- ✓ Click message → API request/response
- ✓ Create test user

### Schema Gaps (Backend Work Required)

**Priority 1 (Required for full UI functionality):**
1. **`intent_engines` table** — if Intent Engine versioning should be admin-controlled (currently hardcoded in Kotlin)
2. **`Personas` columns:** `tags`, `bio`, `city`, `occupation`, `interests` — needed for Personas module filters
3. **`is_test` boolean on `Users`** — to distinguish test users from real users

**Priority 2 (Larger features):**
4. **`persona_posts` table** — entire Posts feature (cover photo, free/premium, engagement counters)
5. **Skill-usage tracking table** — record each turn's `SkillSelection` result for analytics

**Priority 3 (Future revenue features):**
6. **Payments / subscriptions schema** — `Transactions`, `Subscriptions`, pricing tiers, renewals
7. **Admin-editable settings store** — move `LlmConfig.kt` env vars to DB
8. **DB-backed admin allowlist** — move `ADMIN_USER_IDS` env var to DB table

**Priority 0 (Architectural; most complex):**
9. **Test Chat "use testing versions" pipeline flag** — thread a flag through `ChatRequest` → `RepositoryContextAssembler` to allow loading testing-version entities instead of production-version

### UI Strategy for Gaps

**For modules with complete backend support (Engines, Skills, Personas core, Users, Test Users, System Config, Test Chat):**
- Wire fully to backend; no dummy data needed

**For modules with partial backend support (Personas Posts/Analytics, Conversations API inspector):**
- Display mockup UI with dummy data; add flag-note in UI: "Data source: Mock (awaiting backend X)"
- Keep HTML structure in place so backend wiring can be dropped in later without UI restructuring

**For modules with no backend support yet (Revenue/Transactions, Dashboard analytics):**
- Per original request: "some data might not be available... that's ok that can be dummy right now"
- Display full UI with dummy numbers; flag-notes document which endpoints to wire when available

---

## Implementation Sequencing

### Phase A: UI Structure & Styling (Prerequisite)
- [ ] Convert `admin-ui.html` four-tab layout to sidebar-nav layout
- [ ] Apply AMIA palette globally (custom properties, component styling)
- [ ] Rewrite page title: "AMIA Admin Console"
- [ ] Commit: "refactor(admin): adopt AMIA sidebar-nav layout and branding"

### Phase B: Core Admin Modules (Backend-Ready)
- [ ] Engines module (wire to `AdminEngineRoutes`)
- [ ] Skills module (wire to `AdminSkillRoutes`)
- [ ] Personas Core tab (wire to `AdminPersonaRoutes` — only core fields for now)
- [ ] System Configuration (wire to `AdminAiSettingsRoutes` + `AdminIntentEngineRoutes`)
- [ ] Users module (wire to `AdminUserRoutes` — requires `is_test` schema column first)
- [ ] Test Users module (wire to `AdminUserRoutes` with `is_test=true` filter)
- [ ] Test Chat module (confirm "use testing versions" flag is wired; if not, skip until that's resolved)
- [ ] Commit: "feat(admin): wire core modules to backend endpoints"

### Phase C: Secondary Modules (Partial Backend Support)
- [ ] Personas Visual tab (wire `ImageJobs` + `GeneratedCandidates` flow)
- [ ] Personas Posts tab (stub with dummy data + flag-note; await `persona_posts` schema)
- [ ] Personas Analytics tab (stub with dummy data + flag-note; await skill-usage tracking)
- [ ] Personas Filters (stub with filter UI; await `tags`, `bio`, `city`, `occupation`, `interests` columns)
- [ ] Conversations module (wire to chat-retrieval endpoints + message-detail API logging)
- [ ] Commit: "feat(admin): add secondary modules with mock data where backend unavailable"

### Phase D: Dashboard & Revenue (Dummy Data OK)
- [ ] Dashboard module (layout complete, all dummy numbers)
- [ ] Revenue & Transactions module (layout complete, all dummy numbers)
- [ ] Commit: "feat(admin): add dashboard and revenue modules (mock data)"

### Phase E: Polish
- [ ] Test all modules end-to-end against live backend
- [ ] Add loading indicators and error messages for async operations
- [ ] Verify responsive design (sidebar collapse on small screens, if applicable)
- [ ] Commit: "polish(admin): error handling, loading states, responsive refinement"

---

## Key Implementation Notes

### Auto-Versioning Pattern (Engines, Skills, Personas Core)
When user clicks Save on an edit form:
1. Call `PUT /v1/admin/{entity}/{id}` with updated fields
2. Backend automatically calls `createNextVersion()` — no separate "Bump Version" button
3. UI receives updated record in response
4. **Display behavior:** Refresh the Production/Testing sections to show new versions

### Backend API Pattern (from existing `AdminPersonaRoutes`)
```
GET /v1/admin/{entity}  → List all records
GET /v1/admin/{entity}/{id}  → Get one record
PUT /v1/admin/{entity}/{id}  → Update and auto-version
POST /v1/admin/{entity}/{id}/publish  → Activate a testing version
POST /v1/admin/{entity}/{id}/archive  → Archive old versions
```

### Flag-Notes in HTML
The prototype includes 26 `.flag-note` UI callouts (yellow highlighted boxes with text like "DUMMY DATA", "NEEDS-BACKEND-WORK", "NEW-NOT-IN-SCHEMA"). When converting to live UI:
- Keep flag-notes visible during dev; they document gaps
- For production release, conditionally hide flag-notes (CSS class `.flag-note { display: none; }`) or remove them entirely
- Each flag-note HTML comment documents what backend work is needed

### Testing Versions vs Production
- **Production:** `is_active=true` row (currently active for users)
- **Testing:** Latest `status="published"` row that is not active (for QA)
- UI buttons:
  - Production section: "Make Testing" button → calls `POST /v1/admin/{entity}/{id}/publish`
  - Testing section: "Make Production" button → calls `POST /v1/admin/{entity}/{id}/activate` (or similar)
- No new backend concept; these are just UI labels for existing database states

### Mock Data Pattern in Prototype
The prototype uses an in-file `DB` object:
```javascript
const DB = {
  personas: [
    { id: 1, name: "Sanjana", gender: "female", ... },
    ...
  ],
  engines: [ ... ],
  skills: [ ... ],
  ...
}
```
**Do NOT copy this pattern into live code.** Replace all `DB` reads/writes with actual API calls:
```javascript
// ❌ Old (prototype):
const personas = DB.personas.filter(p => p.gender === 'female');

// ✅ New (live):
const response = await fetch('/v1/admin/personas?gender=female');
const personas = await response.json();
```

---

## Testing Checklist

- [ ] Sidebar navigation switches between all 9 modules
- [ ] Engines: Production/Testing sections populate; edit and save trigger auto-versioning
- [ ] Skills: Same as Engines
- [ ] Personas Core: Edit fields, save, version increments; Filters for gender/orientation/tags (stub OK if columns don't exist yet)
- [ ] Personas Visual: Generate 4 images, pick one, confirm save
- [ ] System Config: Update AI settings; confirm live-reload works (check backend logs)
- [ ] Users: Search with filters; click user detail; confirm memory search works
- [ ] Test Users: Create new test user; verify `is_test` flag is set (once schema added)
- [ ] Conversations: Load conversation list; click one; click a message to see API request/response in right pane
- [ ] Test Chat: Toggle "Use Testing Versions"; send message; confirm correct versions loaded (requires Phase 9 backend work)
- [ ] Revenue & Transactions: Dashboard displays; transactions table loads (dummy data OK)
- [ ] Dashboard: All stat cards render (dummy data OK)
- [ ] Styling: AMIA colors consistent across all modules; no clashing with old Pink Dreams palette
- [ ] Responsive: Sidebar collapses on mobile (if applicable); content panes adjust

---

## Reference Files

- **Prototype:** `/Claude outputs/amia-admin-panel-prototype/build/amia-admin-panel.html` (1,404 lines; use for HTML structure, CSS, and visual reference)
- **Brand Assets:** `assets/amia-emblem.png`, `assets/amia-logo-lockup.png`
- **Integration Notes:** `claude/admin-panel-integration-notes.md` (project doc; lists schema gaps and sequencing)
- **Existing Backend Routes:**
  - `AdminPersonaRoutes.kt`
  - `AdminEngineRoutes.kt`
  - `AdminSkillRoutes.kt`
  - `AdminUserRoutes.kt`
  - `AdminIntentEngineRoutes.kt`
  - `AdminAiSettingsRoutes.kt`
  - `AdminTestChatRoutes.kt`

---

## Success Criteria

**Handoff is complete when:**
1. ✓ Sidebar-nav layout replaces four-tab layout
2. ✓ AMIA branding (colors, typography, assets) applied globally
3. ✓ All 9 modules are visible as sidebar items
4. ✓ At least Engines, Skills, Personas Core, System Config, Users, Test Users modules are wired to backend and functional
5. ✓ Conversations module shows API request/response inspector
6. ✓ Test Chat module respects "Use Testing Versions" flag (or explicitly notes that Phase 9 backend work is still pending)
7. ✓ Revenue & Transactions, Dashboard modules display with dummy data and are clearly labeled
8. ✓ All 26 flag-notes are preserved in HTML comments for future backend wiring
9. ✓ Page title changed to "AMIA Admin Console"
10. ✓ Observability tab integrates into sidebar (or is removed/merged per product decision)

---

**End of Specification**
