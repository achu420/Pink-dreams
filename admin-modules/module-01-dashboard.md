# Admin Module 01: Dashboard

**Module Name:** System Dashboard  
**Module ID:** `dashboard`  
**Sidebar Group:** Main  
**Backend Dependencies:** None (dummy data OK for v1)  
**Priority:** Phase 1 (Foundation)

---

## 1. Requirements

**Purpose:** Overview of system health and key metrics.

**Key Features:**
- 6–8 KPI stat cards (top section): Active Personas, Active Skills, Total Users, Test Users, Conversations (24h), Avg Session Duration, Top Skill, Top Persona
- AI Runtime Summary card (left): Current model, temperature, max-tokens, JSON mode, Intent model
- SLA Snapshot card (right): Primary SLA %, Error rate %, Average latency

**Data Source:**
- For v1: **All dummy data.** Hardcode sample numbers in the render function.
- Cards display mock values; no backend calls yet.
- Future: When analytics endpoints exist, replace dummy values with real data.

---

## 2. HTML Structure

Replace the existing dashboard content. The sidebar already has a Dashboard nav item pointing to this module.

```html
<div id="dashboard" class="tab-content active">
    <div class="section">
        <h2>System Dashboard</h2>
        <div class="error" id="dashboardError" style="display:none;"></div>
        
        <!-- KPI Stat Row -->
        <div class="stat-row">
            <div class="stat-card">
                <div class="stat-label">Active Personas</div>
                <div class="stat-value" id="statActivePersonas">12</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Active Skills</div>
                <div class="stat-value" id="statActiveSkills">8</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Total Users</div>
                <div class="stat-value" id="statTotalUsers">2,847</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Test Users</div>
                <div class="stat-value" id="statTestUsers">42</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Conversations (24h)</div>
                <div class="stat-value" id="statConversations24h">1,205</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Avg Session Duration</div>
                <div class="stat-value" id="statAvgSession">12m 34s</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Top Skill</div>
                <div class="stat-value" id="statTopSkill">flirting</div>
            </div>
            <div class="stat-card">
                <div class="stat-label">Top Persona</div>
                <div class="stat-value" id="statTopPersona">Sanjana</div>
            </div>
        </div>
        
        <!-- Summary Cards -->
        <div class="grid">
            <div class="section">
                <h3>AI Runtime Summary</h3>
                <div class="stat-summary">
                    <div class="summary-row">
                        <div class="summary-label">Model</div>
                        <div class="summary-value" id="summaryModel">deepseek/deepseek-v4.1-flash</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Temperature</div>
                        <div class="summary-value" id="summaryTemp">0.7</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Max Tokens</div>
                        <div class="summary-value" id="summaryMaxTokens">2048</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">JSON Mode</div>
                        <div class="summary-value" id="summaryJsonMode">false</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Intent Model</div>
                        <div class="summary-value" id="summaryIntentModel">openai/gpt-4o-mini</div>
                    </div>
                </div>
            </div>
            
            <div class="section">
                <h3>SLA Snapshot</h3>
                <div class="stat-summary">
                    <div class="summary-row">
                        <div class="summary-label">Primary SLA</div>
                        <div class="summary-value" id="summarySla">99.2%</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Error Rate</div>
                        <div class="summary-value" id="summaryErrorRate">0.8%</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Avg Latency (p50)</div>
                        <div class="summary-value" id="summaryLatencyP50">245ms</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Avg Latency (p99)</div>
                        <div class="summary-value" id="summaryLatencyP99">1.2s</div>
                    </div>
                    <div class="summary-row">
                        <div class="summary-label">Uptime (30d)</div>
                        <div class="summary-value" id="summaryUptime">99.95%</div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>
```

**CSS to Add:**
```css
.stat-summary {
    display: flex;
    flex-direction: column;
    gap: 12px;
}
.summary-row {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 10px 0;
    border-bottom: 1px solid var(--border);
}
.summary-row:last-child {
    border-bottom: none;
}
.summary-label {
    color: var(--muted-mauve);
    font-weight: 600;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.03em;
}
.summary-value {
    font-weight: 700;
    font-size: 14px;
    color: var(--warm-white);
}
```

---

## 3. JavaScript

Add to the page's main `<script>` section:

```javascript
// Dashboard initialization
async function initDashboard() {
    // For v1: load dummy data
    loadDashboardData();
}

function loadDashboardData() {
    // Dummy data — replace these with real API calls when analytics endpoints exist
    const data = {
        activePersonas: 12,
        activeSkills: 8,
        totalUsers: 2847,
        testUsers: 42,
        conversations24h: 1205,
        avgSessionDuration: '12m 34s',
        topSkill: 'flirting',
        topPersona: 'Sanjana',
        model: 'deepseek/deepseek-v4.1-flash',
        temperature: 0.7,
        maxTokens: 2048,
        jsonMode: false,
        intentModel: 'openai/gpt-4o-mini',
        primarySla: '99.2%',
        errorRate: '0.8%',
        latencyP50: '245ms',
        latencyP99: '1.2s',
        uptime30d: '99.95%'
    };
    
    renderDashboard(data);
}

function renderDashboard(data) {
    // KPI cards
    document.getElementById('statActivePersonas').textContent = data.activePersonas;
    document.getElementById('statActiveSkills').textContent = data.activeSkills;
    document.getElementById('statTotalUsers').textContent = data.totalUsers.toLocaleString();
    document.getElementById('statTestUsers').textContent = data.testUsers;
    document.getElementById('statConversations24h').textContent = data.conversations24h.toLocaleString();
    document.getElementById('statAvgSession').textContent = data.avgSessionDuration;
    document.getElementById('statTopSkill').textContent = data.topSkill;
    document.getElementById('statTopPersona').textContent = data.topPersona;
    
    // AI Runtime Summary
    document.getElementById('summaryModel').textContent = data.model;
    document.getElementById('summaryTemp').textContent = data.temperature;
    document.getElementById('summaryMaxTokens').textContent = data.maxTokens;
    document.getElementById('summaryJsonMode').textContent = data.jsonMode ? 'true' : 'false';
    document.getElementById('summaryIntentModel').textContent = data.intentModel;
    
    // SLA Snapshot
    document.getElementById('summarySla').textContent = data.primarySla;
    document.getElementById('summaryErrorRate').textContent = data.errorRate;
    document.getElementById('summaryLatencyP50').textContent = data.latencyP50;
    document.getElementById('summaryLatencyP99').textContent = data.latencyP99;
    document.getElementById('summaryUptime').textContent = data.uptime30d;
}

// Call on page load (add to existing init function)
document.addEventListener('DOMContentLoaded', () => {
    // ... existing code ...
    initDashboard();
});
```

---

## 4. Backend Wiring Checklist

**For v1:** None required. All dummy data.

**For future versions (when endpoints exist):**
- [ ] `GET /v1/admin/dashboard/metrics` → KPI stats
- [ ] `GET /v1/admin/dashboard/ai-config` → AI Runtime Summary
- [ ] `GET /v1/admin/dashboard/sla` → SLA Snapshot

---

## 5. Testing Checklist

- [ ] Dashboard loads when "Dashboard" nav item clicked
- [ ] Topbar title changes to "Dashboard"
- [ ] All 8 KPI stat cards display with dummy values
- [ ] AI Runtime Summary section renders all 5 fields
- [ ] SLA Snapshot section renders all 5 fields
- [ ] Cards are visually distinct (AMIA colors, borders, spacing)
- [ ] Grid layout is responsive (2 columns on desktop, 1 on mobile)
- [ ] No console errors

---

## 6. Success Criteria

✓ Dashboard module is complete when:
- All stat cards display with proper styling
- No backend calls (dummy data OK)
- Sidebar navigation correctly highlights Dashboard
- Topbar title is "Dashboard"
- Responsive on mobile (grid collapses to 1 column)
- No errors in browser console
