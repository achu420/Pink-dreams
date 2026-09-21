# Admin Module 07: Conversations

**Module Name:** Conversations Inspector  
**Module ID:** `conversations`  
**Sidebar Group:** Operations  
**Backend Routes:** Chat/Conversation endpoints  
**Priority:** Phase 3 (Operational)

---

## 1. Requirements

**Purpose:** Review past conversations with full context (chat transcript, API logs, user memories).

**Key Features:**
- **3-pane layout:**
  1. Left pane: Conversation list (search, filter by date/persona)
  2. Center pane: Chat transcript (messages with timestamps)
  3. Right pane: API request/response inspector (when message is clicked)
- **Click a message** in center pane → load its LLM metadata in right pane
- **Memory view** (optional lower pane): Show user's extracted memories for this conversation

---

## 2. HTML Structure

```html
<div id="conversations" class="tab-content">
    <div class="section">
        <h2>Conversations</h2>
        <div class="error" id="conversationsError" style="display:none;"></div>
        
        <div style="display: grid; grid-template-columns: 250px 1fr 350px; gap: 16px; height: calc(100vh - 300px);">
            <!-- Left: Conversation List -->
            <div style="border: 1px solid var(--border); border-radius: 14px; background: var(--surface); overflow-y: auto; padding: 12px;">
                <h4 style="margin-top: 0;">Conversations</h4>
                <input type="text" id="convSearchText" placeholder="Search..." style="width: 100%; margin-bottom: 12px;" onkeyup="filterConversations()">
                <div id="conversationsList" style="font-size: 12px;"></div>
            </div>
            
            <!-- Center: Chat Transcript -->
            <div style="border: 1px solid var(--border); border-radius: 14px; background: var(--surface); overflow-y: auto; padding: 16px; display: flex; flex-direction: column;">
                <div id="chatTranscript" style="flex: 1; overflow-y: auto; margin-bottom: 16px;">
                    <div style="color: var(--muted-mauve); text-align: center;">Select a conversation to view</div>
                </div>
                <!-- Optional: message input (read-only display) -->
            </div>
            
            <!-- Right: API Inspector -->
            <div style="border: 1px solid var(--border); border-radius: 14px; background: var(--surface); overflow-y: auto; padding: 16px;">
                <h4 style="margin-top: 0;">API Details</h4>
                <div id="apiInspector" style="font-size: 12px; color: var(--muted-mauve);">
                    Click a message to view API request/response
                </div>
            </div>
        </div>
    </div>
</div>
```

---

## 3. JavaScript

```javascript
let allConversations = [];
let currentConversationId = null;

async function loadConversations() {
    try {
        const res = await fetch('/v1/admin/conversations', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        allConversations = await res.json();
        renderConversationsList(allConversations);
    } catch (e) {
        showError('conversations', 'Failed to load conversations: ' + e.message);
    }
}

function renderConversationsList(convs) {
    const list = document.getElementById('conversationsList');
    list.innerHTML = convs.map(c => `
        <div style="padding: 8px; margin-bottom: 8px; background: var(--surface-2); border-radius: 9px; cursor: pointer; border: 1px solid var(--border);" 
             onclick="loadConversationDetail('${c.id}')">
            <div style="font-weight: 600; color: var(--warm-white);">${escapeHtml(c.userDisplayName || 'User')}</div>
            <div style="font-size: 11px; color: var(--muted-mauve);">${new Date(c.createdAt).toLocaleString()}</div>
            <div style="font-size: 11px; margin-top: 4px;">Persona: ${escapeHtml(c.personaSlug || '—')}</div>
        </div>
    `).join('');
}

async function loadConversationDetail(convId) {
    currentConversationId = convId;
    try {
        const res = await fetch(`/v1/admin/conversations/${convId}`, {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const conv = await res.json();
        renderChatTranscript(conv.messages || []);
    } catch (e) {
        showError('conversations', 'Failed to load conversation: ' + e.message);
    }
}

function renderChatTranscript(messages) {
    const transcript = document.getElementById('chatTranscript');
    transcript.innerHTML = messages.map((msg, idx) => `
        <div style="margin-bottom: 12px; padding: 12px; background: ${msg.role === 'user' ? 'var(--surface-2)' : 'rgba(232, 90, 136, 0.1)'}; border-radius: 9px; border-left: 3px solid ${msg.role === 'user' ? 'var(--rose)' : 'var(--soft-rose)'}; cursor: pointer;" 
             onclick="loadMessageDetails('${msg.id}')">
            <div style="font-weight: 600; color: var(--warm-white); font-size: 12px;">${msg.role === 'user' ? 'User' : 'Assistant'}</div>
            <div style="font-size: 12px; color: var(--warm-white); margin-top: 6px;">${escapeHtml(msg.content.substring(0, 100))}${msg.content.length > 100 ? '...' : ''}</div>
            <div style="font-size: 11px; color: var(--muted-mauve); margin-top: 4px;">${new Date(msg.createdAt).toLocaleTimeString()}</div>
        </div>
    `).join('');
}

async function loadMessageDetails(messageId) {
    try {
        const res = await fetch(`/v1/admin/conversations/${currentConversationId}/messages/${messageId}/metadata`, {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const metadata = await res.json();
        renderApiInspector(metadata);
    } catch (e) {
        showError('conversations', 'Failed to load message details: ' + e.message);
    }
}

function renderApiInspector(metadata) {
    const inspector = document.getElementById('apiInspector');
    inspector.innerHTML = `
        <h5>LLM Execution</h5>
        <div style="background: var(--surface-2); border-radius: 9px; padding: 10px; margin-bottom: 12px; font-family: monospace; font-size: 11px; overflow-x: auto;">
            <strong>Model:</strong> ${escapeHtml(metadata.model)}<br>
            <strong>Tokens (prompt/completion):</strong> ${metadata.promptTokens} / ${metadata.completionTokens}<br>
            <strong>Latency:</strong> ${metadata.latencyMs}ms<br>
            <strong>Provider:</strong> ${escapeHtml(metadata.provider || '—')}<br>
        </div>
        
        <h5>Request</h5>
        <textarea readonly style="width: 100%; height: 120px; font-family: monospace; font-size: 11px; border: 1px solid var(--border); border-radius: 9px; padding: 8px; background: var(--surface-2); color: var(--soft-rose);">${JSON.stringify(metadata.request, null, 2)}</textarea>
        
        <h5 style="margin-top: 12px;">Response</h5>
        <textarea readonly style="width: 100%; height: 120px; font-family: monospace; font-size: 11px; border: 1px solid var(--border); border-radius: 9px; padding: 8px; background: var(--surface-2); color: var(--soft-rose);">${JSON.stringify(metadata.response, null, 2)}</textarea>
    `;
}

function filterConversations() {
    const text = document.getElementById('convSearchText').value.toLowerCase();
    const filtered = allConversations.filter(c => 
        c.userDisplayName?.toLowerCase().includes(text) || 
        c.personaSlug?.toLowerCase().includes(text) ||
        c.id.includes(text)
    );
    renderConversationsList(filtered);
}

function navigateToConversations() {
    loadConversations();
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}
```

---

## 4. Backend Wiring Checklist

- [ ] `GET /v1/admin/conversations` → List conversations
- [ ] `GET /v1/admin/conversations/{id}` → Get conversation with all messages
- [ ] `GET /v1/admin/conversations/{convId}/messages/{messageId}/metadata` → Get LLM execution details

---

## 5. Testing Checklist

- [ ] Load conversations; verify list populates
- [ ] Click a conversation; verify transcript loads in center pane
- [ ] Click a message; verify API details load in right pane
- [ ] Search conversations; verify filtering works
- [ ] Verify 3-pane layout is responsive

---

## 6. Success Criteria

✓ Conversations module complete when:
- 3-pane layout displays correctly
- Conversations list loads
- Clicking conversation loads transcript
- Clicking message loads API details
- Search/filter works
- AMIA styling applied
