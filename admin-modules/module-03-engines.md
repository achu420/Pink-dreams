# Admin Module 03: Engines

**Module Name:** Conversation Engines  
**Module ID:** `engines`  
**Sidebar Group:** AI Configuration  
**Backend Routes:** `AdminEngineRoutes.kt`  
**Priority:** Phase 2 (Core Admin)

---

## 1. Requirements

**Purpose:** Manage conversation engine versions. Each engine has Production (active) and Testing (latest published) versions.

**Key Features:**
- **Two sections:** Production (currently active) and Testing (latest published, not active)
- **Production section:** Shows active engine version; "Make Testing" button to publish a new testing version
- **Testing section:** Shows latest published version; "Make Production" button to activate it
- **Version fields:** Name, content (large textarea), version number, created date, last modified
- **Version history:** Table showing all engine versions with status and action buttons

---

## 2. HTML Structure

```html
<div id="engines" class="tab-content">
    <div class="section">
        <h2>Conversation Engines</h2>
        <div class="error" id="enginesError" style="display:none;"></div>
        <div class="success" id="enginesSuccess" style="display:none;"></div>
        
        <div class="grid">
            <!-- Production -->
            <div class="section">
                <h3>Production</h3>
                <div id="engineProductionCard" class="engine-card">
                    <div class="engine-field">
                        <label>Version</label>
                        <div class="engine-value" id="engineProdVersion">—</div>
                    </div>
                    <div class="engine-field">
                        <label>Status</label>
                        <div><span class="badge success">Active</span></div>
                    </div>
                    <div class="engine-field">
                        <label>Content (Preview)</label>
                        <textarea readonly style="min-height: 150px;" id="engineProdContent"></textarea>
                    </div>
                    <div class="engine-field">
                        <label>Created</label>
                        <div class="engine-value" id="engineProdCreated">—</div>
                    </div>
                    <button onclick="makeEngineTestingVersion()">Make Testing</button>
                </div>
            </div>
            
            <!-- Testing -->
            <div class="section">
                <h3>Testing</h3>
                <div id="engineTestingCard" class="engine-card">
                    <div class="engine-field">
                        <label>Version</label>
                        <div class="engine-value" id="engineTestVersion">—</div>
                    </div>
                    <div class="engine-field">
                        <label>Status</label>
                        <div><span class="badge warning">Testing</span></div>
                    </div>
                    <div class="engine-field">
                        <label>Content (Preview)</label>
                        <textarea readonly style="min-height: 150px;" id="engineTestContent"></textarea>
                    </div>
                    <div class="engine-field">
                        <label>Created</label>
                        <div class="engine-value" id="engineTestCreated">—</div>
                    </div>
                    <button onclick="makeEngineProduction()">Make Production</button>
                </div>
            </div>
        </div>
        
        <!-- Edit/Create Form -->
        <hr style="margin: 30px 0;">
        <h3>Create or Edit Draft</h3>
        <div class="form-group">
            <label>Engine Content:</label>
            <textarea id="engineEditContent" placeholder="Conversation engine prompt..."></textarea>
        </div>
        <div class="form-group">
            <label>Changelog Note (optional):</label>
            <input type="text" id="engineEditChangelogNote" placeholder="e.g., updated boundary rules">
        </div>
        <button onclick="createEngineDraft()">Create Draft Version</button>
        <button class="secondary" onclick="loadEngines()" style="margin-left: 8px;">Refresh</button>
        
        <!-- Version History -->
        <hr style="margin: 30px 0;">
        <h3>Version History</h3>
        <div class="table-wrap">
            <table id="enginesTable">
                <thead>
                    <tr>
                        <th>Version</th>
                        <th>Status</th>
                        <th>Active</th>
                        <th>Created</th>
                        <th>Changelog</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="enginesTableBody"></tbody>
            </table>
        </div>
    </div>
</div>
```

**CSS to Add:**
```css
.engine-card {
    background: var(--surface-2);
    border: 1px solid var(--border);
    padding: 16px;
    border-radius: 14px;
    margin-bottom: 16px;
}
.engine-field {
    margin-bottom: 12px;
}
.engine-field label {
    display: block;
    font-size: 11px;
    color: var(--muted-mauve);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    margin-bottom: 4px;
}
.engine-value {
    color: var(--warm-white);
    font-size: 13px;
}
```

---

## 3. JavaScript

```javascript
let currentEngineId = null;

async function loadEngines() {
    try {
        const res = await fetch('/v1/admin/engines', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        
        const prodVersion = data.versions?.find(v => v.isActive);
        const testVersion = data.versions?.find(v => v.status === 'published' && !v.isActive);
        currentEngineId = data.id;
        
        renderEngineSection('Prod', prodVersion);
        renderEngineSection('Test', testVersion);
        renderEngineVersionTable(data.versions || []);
    } catch (e) {
        showError('engines', 'Failed to load engines: ' + e.message);
    }
}

function renderEngineSection(type, version) {
    const prefix = type === 'Prod' ? 'Prod' : 'Test';
    document.getElementById(`engine${prefix}Version`).textContent = version ? `v${version.version}` : '—';
    document.getElementById(`engine${prefix}Content`).value = version?.content || '—';
    document.getElementById(`engine${prefix}Created`).textContent = version?.createdAt ? new Date(version.createdAt).toLocaleDateString() : '—';
}

function renderEngineVersionTable(versions) {
    const tbody = document.getElementById('enginesTableBody');
    tbody.innerHTML = versions.map(v => `
        <tr>
            <td>v${v.version}</td>
            <td><span class="badge ${v.isActive ? 'success' : v.status === 'draft' ? 'muted' : 'warning'}">${v.status}</span></td>
            <td>${v.isActive ? '✓' : '—'}</td>
            <td>${new Date(v.createdAt).toLocaleDateString()}</td>
            <td>${escapeHtml(v.changelogNote || '—')}</td>
            <td>
                ${v.status === 'draft' ? `<button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="publishEngineVersion('${v.id}')">Publish</button>` : ''}
                ${v.status === 'published' && !v.isActive ? `<button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="activateEngineVersion('${v.id}')">Activate</button>` : ''}
            </td>
        </tr>
    `).join('');
}

async function createEngineDraft() {
    const content = document.getElementById('engineEditContent').value.trim();
    const changelogNote = document.getElementById('engineEditChangelogNote').value.trim();
    
    if (!content) {
        showError('engines', 'Engine content is required');
        return;
    }
    
    try {
        const res = await fetch(`/v1/admin/engines`, {
            method: 'POST',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ content, changelogNote: changelogNote || null })
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('engines', 'Draft version created');
        document.getElementById('engineEditContent').value = '';
        document.getElementById('engineEditChangelogNote').value = '';
        loadEngines();
    } catch (e) {
        showError('engines', 'Failed to create draft: ' + e.message);
    }
}

async function publishEngineVersion(versionId) {
    try {
        const res = await fetch(`/v1/admin/engines/${currentEngineId}/${versionId}/publish`, {
            method: 'POST',
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        showSuccess('engines', 'Version published to Testing');
        loadEngines();
    } catch (e) {
        showError('engines', 'Failed to publish: ' + e.message);
    }
}

async function activateEngineVersion(versionId) {
    try {
        const res = await fetch(`/v1/admin/engines/${currentEngineId}/${versionId}/activate`, {
            method: 'POST',
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        showSuccess('engines', 'Version activated to Production');
        loadEngines();
    } catch (e) {
        showError('engines', 'Failed to activate: ' + e.message);
    }
}

async function makeEngineTestingVersion() {
    // Find latest published (non-active) version and activate it, or create new
    alert('Implement: Promote testing version');
}

async function makeEngineProduction() {
    // Activate the testing version
    alert('Implement: Make production');
}

// Load on navigation
function navigateToEngines() {
    loadEngines();
}
```

---

## 4. Backend Wiring Checklist

- [ ] `GET /v1/admin/engines` → Get engines with all versions
- [ ] `POST /v1/admin/engines` → Create new draft version
- [ ] `POST /v1/admin/engines/{engineId}/{versionId}/publish` → Publish to Testing
- [ ] `POST /v1/admin/engines/{engineId}/{versionId}/activate` → Activate to Production

---

## 5. Testing Checklist

- [ ] Load engines module; Production and Testing sections display
- [ ] Create a draft version; verify it appears in version history
- [ ] Publish a draft; verify it moves to Testing section
- [ ] Activate Testing version; verify it moves to Production section
- [ ] Verify version history table shows all versions with correct statuses
- [ ] Verify error handling for empty content
- [ ] Verify success messages display

---

## 6. Success Criteria

✓ Engines module complete when:
- Production/Testing sections display with correct versions
- Create draft, publish, and activate workflows all function
- Version history table shows all versions
- No console errors
- AMIA styling applied throughout
