# Admin Module 08: Test Chat

**Module Name:** Test Chat Interface  
**Module ID:** `testchat`  
**Sidebar Group:** Testing  
**Backend Routes:** Chat testing endpoints  
**Priority:** Phase 3 (Operational)

---

## 1. Requirements

**Purpose:** Test persona conversation engine in Production/Testing modes without user login flow.

**Key Features:**
- **Persona Selector:** Dropdown list of all personas from `/v1/admin/personas`
- **Version Toggle:** Switch between Production (active) and Testing (latest published) versions
- **Chat Interface:** Message display area + input field
- **Real-time Chat:** Send messages and receive responses from the selected persona/version
- **Message History:** Show full conversation thread with timestamps and role badges
- **Clear Conversation:** Reset button to start new test conversation

---

## 2. HTML Structure

```html
<div id="testchat" class="tab-content">
    <div class="section">
        <h2>Test Chat</h2>
        <div class="error" id="testchatError" style="display:none;"></div>
        <div class="success" id="testchatSuccess" style="display:none;"></div>
        
        <!-- Setup Section -->
        <div style="background: var(--surface-2); border: 1px solid var(--border); border-radius: 14px; padding: 16px; margin-bottom: 20px;">
            <h3 style="margin-top: 0;">Test Configuration</h3>
            
            <div style="display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px;">
                <!-- Persona Selector -->
                <div class="form-group">
                    <label>Select Persona:</label>
                    <select id="testchatPersona" onchange="resetTestChat()">
                        <option value="">— Choose a persona —</option>
                    </select>
                </div>
                
                <!-- Version Toggle -->
                <div class="form-group">
                    <label>Engine Version:</label>
                    <div style="display: flex; gap: 8px; margin-top: 4px;">
                        <button id="testchatVersionProd" class="version-toggle active" onclick="setTestChatVersion('production')">Production</button>
                        <button id="testchatVersionTest" class="version-toggle" onclick="setTestChatVersion('testing')">Testing</button>
                    </div>
                </div>
                
                <!-- Action Buttons -->
                <div style="display: flex; gap: 8px; align-items: flex-end;">
                    <button onclick="clearTestChat()" class="secondary">Clear Chat</button>
                </div>
            </div>
        </div>
        
        <!-- Chat Display -->
        <div style="border: 1px solid var(--border); border-radius: 14px; background: var(--surface); overflow: hidden; display: flex; flex-direction: column; height: calc(100vh - 450px); min-height: 400px;">
            <!-- Messages Area -->
            <div id="testchatMessages" style="flex: 1; overflow-y: auto; padding: 16px; background: var(--near-black);">
                <div style="color: var(--muted-mauve); text-align: center; padding: 20px;">
                    Select a persona and start typing to begin test conversation
                </div>
            </div>
            
            <!-- Input Area -->
            <div style="border-top: 1px solid var(--border); padding: 12px; background: var(--surface-2);">
                <div style="display: flex; gap: 8px;">
                    <input type="text" id="testchatInput" placeholder="Type your message..." 
                           onkeypress="if(event.key==='Enter') sendTestChatMessage()" 
                           style="flex: 1;">
                    <button onclick="sendTestChatMessage()">Send</button>
                </div>
            </div>
        </div>
    </div>
</div>
```

**CSS to Add:**
```css
.version-toggle {
    flex: 1;
    padding: 8px 12px;
    border: 1px solid var(--border);
    background: var(--surface-2);
    color: var(--warm-white);
    border-radius: 6px;
    cursor: pointer;
    font-size: 12px;
    transition: all 0.2s ease;
}

.version-toggle.active {
    background: var(--burgundy);
    border-color: var(--burgundy);
    color: var(--warm-white);
}

.version-toggle:hover:not(.active) {
    background: var(--surface);
    border-color: var(--rose);
}

#testchatMessages {
    display: flex;
    flex-direction: column;
    gap: 12px;
}

.test-message {
    display: flex;
    gap: 8px;
    margin-bottom: 12px;
    animation: slideIn 0.3s ease;
}

.test-message.user {
    flex-direction: row-reverse;
}

.test-message-bubble {
    max-width: 70%;
    padding: 10px 12px;
    border-radius: 9px;
    font-size: 13px;
    line-height: 1.4;
    word-wrap: break-word;
}

.test-message.user .test-message-bubble {
    background: var(--burgundy);
    color: var(--warm-white);
}

.test-message.assistant .test-message-bubble {
    background: rgba(232, 90, 136, 0.15);
    border: 1px solid var(--rose);
    color: var(--warm-white);
}

.test-message-time {
    font-size: 11px;
    color: var(--muted-mauve);
    display: flex;
    align-items: center;
}

@keyframes slideIn {
    from {
        opacity: 0;
        transform: translateY(10px);
    }
    to {
        opacity: 1;
        transform: translateY(0);
    }
}
```

---

## 3. JavaScript

```javascript
let testChatPersonaId = null;
let testChatVersion = 'production';
let testChatConversationId = null;
let allTestPersonas = [];

async function loadTestPersonas() {
    try {
        const res = await fetch('/v1/admin/personas', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        allTestPersonas = await res.json();
        
        const select = document.getElementById('testchatPersona');
        select.innerHTML = '<option value="">— Choose a persona —</option>' + 
            allTestPersonas.map(p => `<option value="${p.id}">${escapeHtml(p.displayName)}</option>`).join('');
    } catch (e) {
        showError('testchat', 'Failed to load personas: ' + e.message);
    }
}

function setTestChatPersona() {
    testChatPersonaId = document.getElementById('testchatPersona').value;
    if (!testChatPersonaId) {
        showError('testchat', 'Please select a persona');
        return;
    }
    clearTestChat();
}

function setTestChatVersion(version) {
    testChatVersion = version;
    document.getElementById('testchatVersionProd').classList.toggle('active', version === 'production');
    document.getElementById('testchatVersionTest').classList.toggle('active', version === 'testing');
    
    // Show indication that version changed
    showSuccess('testchat', `Switched to ${version === 'production' ? 'Production' : 'Testing'} version`);
}

function resetTestChat() {
    setTestChatPersona();
}

function clearTestChat() {
    const select = document.getElementById('testchatPersona');
    testChatPersonaId = select.value;
    
    if (!testChatPersonaId) {
        showError('testchat', 'Please select a persona first');
        return;
    }
    
    document.getElementById('testchatMessages').innerHTML = '';
    testChatConversationId = null;
    document.getElementById('testchatInput').value = '';
    document.getElementById('testchatInput').focus();
}

async function sendTestChatMessage() {
    const input = document.getElementById('testchatInput');
    const message = input.value.trim();
    
    if (!message) return;
    if (!testChatPersonaId) {
        showError('testchat', 'Please select a persona first');
        return;
    }
    
    try {
        // Add user message to display
        appendTestMessage('user', message);
        input.value = '';
        input.disabled = true;
        
        // Send to backend
        const res = await fetch('/v1/admin/test/chat', {
            method: 'POST',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                personaId: testChatPersonaId,
                version: testChatVersion,
                conversationId: testChatConversationId,
                message: message
            })
        });
        
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        
        // Store conversation ID for continuity
        testChatConversationId = data.conversationId;
        
        // Add assistant response
        appendTestMessage('assistant', data.response);
        
    } catch (e) {
        showError('testchat', 'Failed to send message: ' + e.message);
        // Still allow editing input on error
    } finally {
        input.disabled = false;
        input.focus();
    }
}

function appendTestMessage(role, text) {
    const messagesDiv = document.getElementById('testchatMessages');
    
    // Clear placeholder if first message
    if (messagesDiv.children.length === 1 && messagesDiv.children[0].textContent.includes('Select a persona')) {
        messagesDiv.innerHTML = '';
    }
    
    const messageEl = document.createElement('div');
    messageEl.className = `test-message ${role}`;
    messageEl.innerHTML = `
        <div class="test-message-bubble">${escapeHtml(text)}</div>
        <div class="test-message-time">${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
    `;
    
    messagesDiv.appendChild(messageEl);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function navigateToTestChat() {
    loadTestPersonas();
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}
```

---

## 4. Backend Wiring Checklist

- [ ] `POST /v1/admin/test/chat` → Send test message
  - Body: `{ personaId, version ("production"|"testing"), conversationId?, message }`
  - Response: `{ conversationId, response, timestamp }`

---

## 5. Testing Checklist

- [ ] Load Test Chat; verify personas dropdown populates
- [ ] Select a persona; verify it's stored
- [ ] Type and send a message; verify message appears as "user" role
- [ ] Verify assistant response appears with correct styling
- [ ] Toggle between Production and Testing; verify indication shows
- [ ] Send multiple messages in sequence; verify conversation continues
- [ ] Click Clear Chat; verify messages cleared and can start new conversation
- [ ] Test Enter key in input field; verify sends message
- [ ] Test with different personas; verify persona context applies

---

## 6. Success Criteria

✓ Test Chat module complete when:
- Personas dropdown loads and displays all personas
- Version toggle switches between Production/Testing
- User messages send and display correctly
- Assistant responses display with proper styling
- Chat history maintains within conversation
- Clear Chat resets conversation
- AMIA styling applied
- No console errors
