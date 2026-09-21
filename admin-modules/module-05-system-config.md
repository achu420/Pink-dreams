# Admin Module 05: System Configuration

**Module Name:** AI Runtime Settings  
**Module ID:** `aisettings`  
**Sidebar Group:** AI Configuration  
**Backend Routes:** `AdminAiSettingsRoutes.kt`, `AdminIntentEngineRoutes.kt`  
**Priority:** Phase 2 (Core Admin)

---

## 1. Requirements

**Purpose:** Live-edit AI engine settings without restarting the server.

**Key Features:**
- **Primary Generation Settings:**
  - Model (e.g., "deepseek/deepseek-v4.1-flash")
  - Temperature (0–2)
  - Max Output Tokens
  - JSON Mode (true/false)
- **Intent Discovery Settings:**
  - Model (e.g., "openai/gpt-4o-mini")
  - JSON Mode toggle
  - Max Output Tokens
- **Provider Routing:**
  - Provider sort order (latency, no-preference, etc.)
- **Current Values Display:**
  - Table showing current setting values and their source (DB override vs env var vs code default)

---

## 2. HTML Structure

```html
<div id="aisettings" class="tab-content">
    <div class="section">
        <h2>AI Runtime Settings</h2>
        <div class="error" id="aisettingsError" style="display:none;"></div>
        <div class="success" id="aisettingsSuccess" style="display:none;"></div>
        
        <div style="font-size: 12px; color: var(--muted-mauve); margin-bottom: 16px;">
            <strong>Precedence:</strong> Database override → Environment variable → Application default
        </div>
        
        <!-- Current Values Table -->
        <h3>Current Settings</h3>
        <table id="aiSettingsCurrentTable">
            <thead>
                <tr>
                    <th>Setting</th>
                    <th>Value</th>
                    <th>Source</th>
                </tr>
            </thead>
            <tbody id="aiSettingsCurrentBody"></tbody>
        </table>
        
        <hr style="margin: 30px 0;">
        
        <!-- Primary Generation Settings -->
        <h3>Primary Generation</h3>
        <div class="form-group">
            <label>Model:</label>
            <input type="text" id="aiSettingsModel" placeholder="e.g., deepseek/deepseek-v4.1-flash">
            <div style="font-size: 11px; color: var(--muted-mauve); margin-top: 4px;">Leave blank to use environment variable</div>
        </div>
        <div class="form-group">
            <label>Temperature (0–2):</label>
            <input type="number" id="aiSettingsTemp" min="0" max="2" step="0.1" placeholder="blank">
            <div style="font-size: 11px; color: var(--muted-mauve); margin-top: 4px;">Leave blank for provider default</div>
        </div>
        <div class="form-group">
            <label>Max Output Tokens:</label>
            <input type="number" id="aiSettingsMaxTokens" min="1" max="32000" placeholder="blank">
            <div style="font-size: 11px; color: var(--muted-mauve); margin-top: 4px;">Leave blank to use environment</div>
        </div>
        <div class="form-group">
            <label>JSON Mode:</label>
            <select id="aiSettingsJsonMode">
                <option value="">(code default)</option>
                <option value="true">true</option>
                <option value="false">false</option>
            </select>
        </div>
        <button onclick="saveAiSettings('primary')">Apply Primary Settings</button>
        <button class="secondary" onclick="loadAiSettings()" style="margin-left: 8px;">Refresh</button>
        
        <hr style="margin: 30px 0;">
        
        <!-- Intent Discovery Settings -->
        <h3>Intent Discovery</h3>
        <div style="font-size: 12px; color: var(--muted-mauve); margin-bottom: 16px;">
            These settings apply to skill selection only, not primary generation.
        </div>
        <div class="form-group">
            <label>Intent Model:</label>
            <input type="text" id="intentSettingsModel" placeholder="e.g., openai/gpt-4o-mini">
            <div style="font-size: 11px; color: var(--muted-mauve); margin-top: 4px;">Leave blank for code default</div>
        </div>
        <div class="form-group">
            <label>JSON Mode:</label>
            <select id="intentSettingsJsonMode">
                <option value="">(code default)</option>
                <option value="true">true</option>
                <option value="false">false</option>
            </select>
        </div>
        <div class="form-group">
            <label>Max Output Tokens:</label>
            <input type="number" id="intentSettingsMaxTokens" min="1" max="32000" placeholder="blank">
        </div>
        <button onclick="saveAiSettings('intent')">Apply Intent Settings</button>
        
        <hr style="margin: 30px 0;">
        
        <!-- Provider Routing -->
        <h3>Provider Routing</h3>
        <div style="font-size: 12px; color: var(--muted-mauve); margin-bottom: 16px;">
            OpenRouter upstream provider preference.
        </div>
        <div class="form-group">
            <label>Provider Sort:</label>
            <select id="providerSort">
                <option value="">(no preference)</option>
                <option value="latency">latency</option>
            </select>
        </div>
        <button onclick="saveAiSettings('provider')">Apply Provider Routing</button>
    </div>
</div>
```

---

## 3. JavaScript

```javascript
async function loadAiSettings() {
    try {
        const res = await fetch('/v1/admin/settings/ai', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        
        // Populate current values table
        renderAiSettingsTable(data);
        
        // Populate form fields (could be empty if using defaults)
        document.getElementById('aiSettingsModel').value = data.model || '';
        document.getElementById('aiSettingsTemp').value = data.temperature || '';
        document.getElementById('aiSettingsMaxTokens').value = data.maxTokens || '';
        document.getElementById('aiSettingsJsonMode').value = data.jsonMode || '';
        
        document.getElementById('intentSettingsModel').value = data.intentModel || '';
        document.getElementById('intentSettingsJsonMode').value = data.intentJsonMode || '';
        document.getElementById('intentSettingsMaxTokens').value = data.intentMaxTokens || '';
        
        document.getElementById('providerSort').value = data.providerSort || '';
    } catch (e) {
        showError('aisettings', 'Failed to load settings: ' + e.message);
    }
}

function renderAiSettingsTable(data) {
    const tbody = document.getElementById('aiSettingsCurrentBody');
    tbody.innerHTML = `
        <tr>
            <td>Model</td>
            <td><code>${escapeHtml(data.modelValue || '—')}</code></td>
            <td>${data.modelSource || 'code default'}</td>
        </tr>
        <tr>
            <td>Temperature</td>
            <td>${data.temperatureValue || '—'}</td>
            <td>${data.temperatureSource || 'provider default'}</td>
        </tr>
        <tr>
            <td>Max Tokens</td>
            <td>${data.maxTokensValue || '—'}</td>
            <td>${data.maxTokensSource || 'env var'}</td>
        </tr>
        <tr>
            <td>JSON Mode</td>
            <td>${data.jsonModeValue || '—'}</td>
            <td>${data.jsonModeSource || 'code default'}</td>
        </tr>
        <tr>
            <td>Intent Model</td>
            <td><code>${escapeHtml(data.intentModelValue || '—')}</code></td>
            <td>${data.intentModelSource || 'code default'}</td>
        </tr>
        <tr>
            <td>Intent JSON Mode</td>
            <td>${data.intentJsonModeValue || '—'}</td>
            <td>${data.intentJsonModeSource || 'code default'}</td>
        </tr>
        <tr>
            <td>Intent Max Tokens</td>
            <td>${data.intentMaxTokensValue || '—'}</td>
            <td>${data.intentMaxTokensSource || 'code default'}</td>
        </tr>
        <tr>
            <td>Provider Sort</td>
            <td>${data.providerSortValue || '(none)'}</td>
            <td>${data.providerSortSource || 'code default'}</td>
        </tr>
    `;
}

async function saveAiSettings(settingsType) {
    try {
        const body = {};
        
        if (settingsType === 'primary' || settingsType === 'all') {
            body.model = document.getElementById('aiSettingsModel').value || null;
            body.temperature = document.getElementById('aiSettingsTemp').value ? 
                parseFloat(document.getElementById('aiSettingsTemp').value) : null;
            body.maxTokens = document.getElementById('aiSettingsMaxTokens').value ? 
                parseInt(document.getElementById('aiSettingsMaxTokens').value) : null;
            body.jsonMode = document.getElementById('aiSettingsJsonMode').value || null;
        }
        
        if (settingsType === 'intent' || settingsType === 'all') {
            body.intentModel = document.getElementById('intentSettingsModel').value || null;
            body.intentJsonMode = document.getElementById('intentSettingsJsonMode').value || null;
            body.intentMaxTokens = document.getElementById('intentSettingsMaxTokens').value ? 
                parseInt(document.getElementById('intentSettingsMaxTokens').value) : null;
        }
        
        if (settingsType === 'provider' || settingsType === 'all') {
            body.providerSort = document.getElementById('providerSort').value || null;
        }
        
        const res = await fetch('/v1/admin/settings/ai', {
            method: 'PUT',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(body)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('aisettings', 'Settings applied (live-reload active)');
        loadAiSettings();
    } catch (e) {
        showError('aisettings', 'Failed to save settings: ' + e.message);
    }
}

function navigateToAiSettings() {
    loadAiSettings();
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}
```

---

## 4. Backend Wiring Checklist

- [ ] `GET /v1/admin/settings/ai` → Get current AI settings with sources
  - Response: `{ modelValue, modelSource, temperature, tempSource, ... }`
- [ ] `PUT /v1/admin/settings/ai` → Update settings (live-reload)
  - Body: `{ model, temperature, maxTokens, jsonMode, intentModel, ... }`

---

## 5. Testing Checklist

- [ ] Load System Config; verify current settings table displays
- [ ] Change model field; click save; verify success message
- [ ] Verify settings are persisted (reload and check)
- [ ] Verify intent settings can be changed independently
- [ ] Verify provider sort dropdown works
- [ ] Verify error messages for invalid values

---

## 6. Success Criteria

✓ System Config module complete when:
- Current settings table displays with sources
- All form fields populate correctly
- Save buttons trigger correct API calls
- Settings persist after reload
- AMIA styling applied
- No console errors
