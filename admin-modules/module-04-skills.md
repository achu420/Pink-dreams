# Admin Module 04: Skills

**Module Name:** Skills Management  
**Module ID:** `skills`  
**Sidebar Group:** AI Configuration  
**Backend Routes:** `AdminSkillRoutes.kt`  
**Priority:** Phase 2 (Core Admin)

---

## 1. Requirements

**Purpose:** Manage AI skills (sub-behaviors). Each skill key has Production and Testing versions.

**Key Features:**
- **Create skill:** Form with skill key, description/content, changelog note
- **List skills:** Table showing all skills with their active and testing versions
- **Version control:** Each skill key can have multiple versions; one active (Production), one latest published (Testing)
- **Publish/Activate:** Draft → Publish (Testing) → Activate (Production)
- **Usage stats:** Show "Used in X% of recent chats" (or dummy data for v1)

---

## 2. HTML Structure

```html
<div id="skills" class="tab-content">
    <div class="section">
        <h2>Skills</h2>
        <div class="error" id="skillsError" style="display:none;"></div>
        <div class="success" id="skillsSuccess" style="display:none;"></div>
        
        <!-- Create Skill Form -->
        <div style="margin-bottom: 30px;">
            <h3>Create New Skill</h3>
            <div class="form-group">
                <label>Skill Key (lowercase, unique):</label>
                <input type="text" id="skillCreateKey" placeholder="e.g., flirting">
            </div>
            <div class="form-group">
                <label>Content/Description:</label>
                <textarea id="skillCreateContent" placeholder="PURPOSE:&#10;BEHAVIOR:&#10;DO NOT:"></textarea>
            </div>
            <div class="form-group">
                <label>Changelog Note (optional):</label>
                <input type="text" id="skillCreateChangelogNote" placeholder="e.g., initial version">
            </div>
            <button onclick="createSkill()">Create Draft</button>
            <button class="secondary" onclick="loadSkills()" style="margin-left: 8px;">Refresh</button>
        </div>
        
        <!-- Skills Table -->
        <hr style="margin: 30px 0;">
        <h3>All Skills</h3>
        <div class="table-wrap">
            <table id="skillsTable">
                <thead>
                    <tr>
                        <th>Key</th>
                        <th>Production Ver</th>
                        <th>Testing Ver</th>
                        <th>Usage (v1)</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="skillsTableBody"></tbody>
            </table>
        </div>
        
        <!-- Edit Skill Modal -->
        <div id="editSkillModal" style="display:none; margin-top: 30px; padding: 20px; background: var(--surface); border: 1px solid var(--border); border-radius: 14px;">
            <h3>Edit Skill</h3>
            <div class="form-group">
                <label>Content:</label>
                <textarea id="editSkillContent"></textarea>
            </div>
            <div class="form-group">
                <label>Changelog Note:</label>
                <input type="text" id="editSkillChangelogNote">
            </div>
            <button onclick="saveSkillDraft()">Save Draft</button>
            <button class="secondary" onclick="closeEditSkillModal()">Cancel</button>
        </div>
    </div>
</div>
```

---

## 3. JavaScript

```javascript
let currentSkillKey = null;

async function loadSkills() {
    try {
        const res = await fetch('/v1/admin/skills', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const skills = await res.json();
        renderSkillsTable(skills);
    } catch (e) {
        showError('skills', 'Failed to load skills: ' + e.message);
    }
}

function renderSkillsTable(skills) {
    const tbody = document.getElementById('skillsTableBody');
    tbody.innerHTML = skills.map(skill => {
        const prodVersion = skill.versions?.find(v => v.isActive);
        const testVersion = skill.versions?.find(v => v.status === 'published' && !v.isActive);
        
        return `
            <tr>
                <td><strong>${escapeHtml(skill.key)}</strong></td>
                <td>${prodVersion ? `v${prodVersion.version}` : '—'}</td>
                <td>${testVersion ? `v${testVersion.version}` : '—'}</td>
                <td>42% <span style="font-size: 11px; color: var(--muted-mauve);">(v1 dummy)</span></td>
                <td>
                    <button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="openEditSkillModal('${escapeHtml(skill.key)}')">Edit</button>
                    <button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="viewSkillVersions('${escapeHtml(skill.key)}')">Versions</button>
                </td>
            </tr>
        `;
    }).join('');
}

async function createSkill() {
    const key = document.getElementById('skillCreateKey').value.trim().toLowerCase();
    const content = document.getElementById('skillCreateContent').value.trim();
    const changelogNote = document.getElementById('skillCreateChangelogNote').value.trim();
    
    if (!key || !content) {
        showError('skills', 'Skill key and content are required');
        return;
    }
    
    try {
        const res = await fetch('/v1/admin/skills', {
            method: 'POST',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ key, content, changelogNote: changelogNote || null })
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('skills', 'Skill draft created');
        document.getElementById('skillCreateKey').value = '';
        document.getElementById('skillCreateContent').value = '';
        document.getElementById('skillCreateChangelogNote').value = '';
        loadSkills();
    } catch (e) {
        showError('skills', 'Failed to create skill: ' + e.message);
    }
}

function openEditSkillModal(key) {
    currentSkillKey = key;
    // In real implementation, fetch the skill and populate form
    document.getElementById('editSkillModal').style.display = 'block';
}

function closeEditSkillModal() {
    document.getElementById('editSkillModal').style.display = 'none';
    currentSkillKey = null;
}

async function saveSkillDraft() {
    if (!currentSkillKey) return;
    
    const content = document.getElementById('editSkillContent').value.trim();
    const changelogNote = document.getElementById('editSkillChangelogNote').value.trim();
    
    try {
        const res = await fetch(`/v1/admin/skills/${currentSkillKey}`, {
            method: 'PUT',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({ content, changelogNote: changelogNote || null })
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('skills', 'Skill draft updated');
        closeEditSkillModal();
        loadSkills();
    } catch (e) {
        showError('skills', 'Failed to save skill: ' + e.message);
    }
}

function viewSkillVersions(key) {
    alert('Version history for skill: ' + key + ' (not yet implemented)');
}

function navigateToSkills() {
    loadSkills();
}
```

---

## 4. Backend Wiring Checklist

- [ ] `GET /v1/admin/skills` → List all skills with versions
- [ ] `POST /v1/admin/skills` → Create draft skill
- [ ] `PUT /v1/admin/skills/{key}` → Update skill (auto-versions)
- [ ] `GET /v1/admin/skills/{key}/versions` → Get all versions of a skill
- [ ] `POST /v1/admin/skills/{key}/{versionId}/publish` → Publish to Testing
- [ ] `POST /v1/admin/skills/{key}/{versionId}/activate` → Activate to Production

---

## 5. Testing Checklist

- [ ] Create a new skill; verify it appears in table
- [ ] Edit a skill; verify new version created
- [ ] Verify Production/Testing version tags display
- [ ] Verify error handling for missing key or content
- [ ] Verify usage stats display (v1 dummy OK)

---

## 6. Success Criteria

✓ Skills module complete when:
- Create skill form works
- Skills table displays with versions
- Edit modal opens/closes correctly
- All AMIA styling applied
- No console errors
