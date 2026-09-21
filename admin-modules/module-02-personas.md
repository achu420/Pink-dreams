# Admin Module 02: Personas

**Module Name:** Personas Management  
**Module ID:** `personas`  
**Sidebar Group:** AI Configuration  
**Backend Routes:** `AdminPersonaRoutes.kt`  
**Priority:** Phase 2 (Core Admin)

---

## 1. Requirements

**Purpose:** Create, view, and manage AI personas. Each persona has versioned core content (Production/Testing).

**Key Features:**
- **List personas:** Table with name, slug, status badge, action buttons
- **Create persona:** Form with slug, display name, gender, orientation, apparent age
- **Edit persona:** Modal form to update display name, gender, orientation, age
- **Versioning:** Show Production (active) and Testing (latest published, not active) version tags on persona rows
- **Version history:** Table showing all versions per persona with publish/activate/archive buttons
- **Sub-tabs (future):** Core, Visual, Posts, Analytics, Filters (for now, just Core in main view)

**Auto-Versioning:** When user saves changes to a persona, backend automatically creates a new draft version (no separate "bump version" button needed).

---

## 2. HTML Structure

```html
<div id="personas" class="tab-content">
    <div class="section">
        <h2>Personas</h2>
        <div class="error" id="personasError" style="display:none;"></div>
        <div class="success" id="personasSuccess" style="display:none;"></div>
        
        <!-- Create Persona Form -->
        <div style="margin-bottom: 30px;">
            <h3>Create Persona</h3>
            <div class="form-group">
                <label>Slug (lowercase, no spaces):</label>
                <input type="text" id="personaCreateSlug" placeholder="e.g., alice-tech-founder">
            </div>
            <div class="form-group">
                <label>Display Name:</label>
                <input type="text" id="personaCreateName" placeholder="e.g., Alice">
            </div>
            <div class="form-group">
                <label>Gender:</label>
                <input type="text" id="personaCreateGender" placeholder="e.g., female">
            </div>
            <div class="form-group">
                <label>Orientation:</label>
                <input type="text" id="personaCreateOrientation" placeholder="e.g., straight">
            </div>
            <div class="form-group">
                <label>Apparent Age:</label>
                <input type="number" id="personaCreateAge" min="18" max="100" placeholder="25">
            </div>
            <button onclick="createPersona()">Create Persona</button>
            <button class="secondary" onclick="loadPersonas()" style="margin-left: 8px;">Refresh List</button>
        </div>
        
        <!-- Personas Table -->
        <hr style="margin: 30px 0;">
        <h3>All Personas</h3>
        <div class="table-wrap">
            <table id="personasTable" style="margin-top: 12px;">
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Slug</th>
                        <th>Status</th>
                        <th>Production Version</th>
                        <th>Testing Version</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="personasTableBody"></tbody>
            </table>
        </div>
        
        <!-- Edit Persona Modal -->
        <div id="editPersonaModal" style="display:none; margin-top: 30px; padding: 20px; background: var(--surface); border: 1px solid var(--border); border-radius: 14px;">
            <h3>Edit Persona</h3>
            <div class="form-group">
                <label>Display Name:</label>
                <input type="text" id="editPersonaName">
            </div>
            <div class="form-group">
                <label>Gender:</label>
                <input type="text" id="editPersonaGender">
            </div>
            <div class="form-group">
                <label>Orientation:</label>
                <input type="text" id="editPersonaOrientation">
            </div>
            <div class="form-group">
                <label>Apparent Age:</label>
                <input type="number" id="editPersonaAge" min="18" max="100">
            </div>
            <button onclick="savePersona()">Save Changes</button>
            <button class="secondary" onclick="closeEditModal()">Cancel</button>
        </div>
    </div>
</div>
```

---

## 3. CSS

Global styles already cover tables and forms. Add if needed:

```css
#personas .table-wrap {
    overflow-x: auto;
}
#personas table td {
    word-break: break-word;
}
.badge {
    display: inline-block;
    padding: 3px 10px;
    border-radius: 20px;
    font-size: 10.5px;
    font-weight: 700;
}
.badge.success {
    background: rgba(62, 213, 152, 0.14);
    color: var(--success);
}
.badge.warning {
    background: rgba(242, 184, 75, 0.16);
    color: var(--warning);
}
```

---

## 4. JavaScript

```javascript
let currentPersonaId = null; // Track which persona is being edited

async function loadPersonas() {
    try {
        const res = await fetch('/v1/admin/personas', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const personas = await res.json();
        renderPersonasTable(personas);
        populatePersonaSelect(personas);
        clearForm();
    } catch (e) {
        showError('personas', 'Failed to load personas: ' + e.message);
    }
}

function renderPersonasTable(personas) {
    const tbody = document.getElementById('personasTableBody');
    tbody.innerHTML = personas.map(p => {
        const prodVersion = p.coreVersions?.find(v => v.isActive);
        const testVersion = p.coreVersions?.find(v => v.status === 'published' && !v.isActive);
        
        return `
            <tr>
                <td>${escapeHtml(p.displayName || p.slug)}</td>
                <td>${escapeHtml(p.slug)}</td>
                <td><span class="badge success">Active</span></td>
                <td>${prodVersion ? `v${prodVersion.version}` : '—'}</td>
                <td>${testVersion ? `v${testVersion.version}` : '—'}</td>
                <td>
                    <button class="secondary" style="padding: 4px 8px; font-size: 12px;" onclick="openEditModal('${p.id}', '${escapeHtml(p.displayName)}', '${escapeHtml(p.gender || '')}', '${escapeHtml(p.orientation || '')}', ${p.apparentAge || ''})">Edit</button>
                    <button class="secondary" style="padding: 4px 8px; font-size: 12px;" onclick="viewVersions('${p.id}')">Versions</button>
                </td>
            </tr>
        `;
    }).join('');
}

function populatePersonaSelect(personas) {
    const select = document.getElementById('personaSelect');
    if (select) {
        select.innerHTML = '<option value="">-- Select Persona --</option>' + 
            personas.map(p => `<option value="${p.id}">${escapeHtml(p.displayName || p.slug)}</option>`).join('');
    }
}

function clearForm() {
    document.getElementById('personaCreateSlug').value = '';
    document.getElementById('personaCreateName').value = '';
    document.getElementById('personaCreateGender').value = '';
    document.getElementById('personaCreateOrientation').value = '';
    document.getElementById('personaCreateAge').value = '';
}

async function createPersona() {
    const body = {
        slug: document.getElementById('personaCreateSlug').value.trim(),
        displayName: document.getElementById('personaCreateName').value.trim(),
        gender: document.getElementById('personaCreateGender').value.trim(),
        orientation: document.getElementById('personaCreateOrientation').value.trim(),
        apparentAge: parseInt(document.getElementById('personaCreateAge').value) || null
    };
    
    if (!body.slug || !body.displayName) {
        showError('personas', 'Slug and Display Name are required');
        return;
    }
    
    try {
        const res = await fetch('/v1/admin/personas', {
            method: 'POST',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(body)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('personas', 'Persona created successfully');
        clearForm();
        loadPersonas();
    } catch (e) {
        showError('personas', 'Failed to create persona: ' + e.message);
    }
}

function openEditModal(personaId, name, gender, orientation, age) {
    currentPersonaId = personaId;
    document.getElementById('editPersonaName').value = name;
    document.getElementById('editPersonaGender').value = gender;
    document.getElementById('editPersonaOrientation').value = orientation;
    document.getElementById('editPersonaAge').value = age || '';
    document.getElementById('editPersonaModal').style.display = 'block';
}

function closeEditModal() {
    document.getElementById('editPersonaModal').style.display = 'none';
    currentPersonaId = null;
}

async function savePersona() {
    if (!currentPersonaId) return;
    
    const body = {
        displayName: document.getElementById('editPersonaName').value.trim(),
        gender: document.getElementById('editPersonaGender').value.trim(),
        orientation: document.getElementById('editPersonaOrientation').value.trim(),
        apparentAge: parseInt(document.getElementById('editPersonaAge').value) || null
    };
    
    try {
        const res = await fetch(`/v1/admin/personas/${currentPersonaId}`, {
            method: 'PUT',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(body)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('personas', 'Persona updated and new version created');
        closeEditModal();
        loadPersonas();
    } catch (e) {
        showError('personas', 'Failed to save persona: ' + e.message);
    }
}

function viewVersions(personaId) {
    // TODO: Implement version history modal/view
    alert('Version history view not yet implemented');
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

// Load personas when module is navigated to
function navigateToPersonas() {
    loadPersonas();
}
```

---

## 5. Backend Wiring Checklist

- [ ] `GET /v1/admin/personas` → List all personas with version info
  - Response: `[{ id, slug, displayName, gender, orientation, apparentAge, coreVersions: [{ version, status, isActive }] }]`
- [ ] `POST /v1/admin/personas` → Create new persona
  - Body: `{ slug, displayName, gender, orientation, apparentAge }`
  - Response: `{ id, ... }`
- [ ] `PUT /v1/admin/personas/{id}` → Update persona fields, auto-version on backend
  - Body: `{ displayName, gender, orientation, apparentAge }`
  - Response: `{ id, displayName, ... }`
- [ ] `GET /v1/admin/personas/{id}/core-versions` → List all core versions for a persona
  - Response: `[{ version, status, isActive, createdAt, changelogNote }]`
- [ ] `POST /v1/admin/personas/{id}/core-versions/{versionId}/publish` → Publish a draft version
- [ ] `POST /v1/admin/personas/{id}/core-versions/{versionId}/activate` → Activate (make Production)

---

## 6. Testing Checklist

- [ ] Load personas module; verify table populates (or shows "No personas" if empty)
- [ ] Create a new persona; verify success message and it appears in table
- [ ] Edit a persona's display name; verify new version is created
- [ ] Verify Production/Testing version badges display correctly
- [ ] Verify error handling when required fields are empty
- [ ] Verify form clears after successful creation
- [ ] Verify edit modal opens/closes correctly
- [ ] Verify topbar title is "Personas"
- [ ] Verify sidebar nav highlights "Personas" when loaded

---

## 7. Success Criteria

✓ Personas module is complete when:
- Create persona form works and persists to backend
- Personas list displays with all 6 columns
- Edit persona modal opens, saves, and auto-versions
- Production/Testing version badges display correctly
- No console errors
- Sidebar navigation is functional
- All AMIA styling is applied
