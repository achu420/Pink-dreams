# Admin Console Implementation Guide

**Complete specification package for the AMIA Admin Console**  
**9 Independent Modules | Phase-based implementation | Integrated testing**

---

## Overview

This guide contains complete, modular specifications for building the AMIA Admin Console. The design is modular, allowing each of the 9 modules to be implemented and tested independently with integrated backend wiring.

**Key Principles:**
- Each module is self-contained with complete HTML, CSS, JavaScript, and backend requirements
- Modules can be built in sequence with integrated testing at each step
- No user input needed between modules; each spec is complete enough for autonomous implementation
- Testing includes functionality checks, edge cases, and integration validation
- All modules follow the same AMIA design system (colors, typography, layout patterns)

---

## Module List & Implementation Order

### **Phase 1: Foundation & Dashboard** (Priority: HIGH)
Complete these first; they establish the core layout and design system.

1. **Module 01: Dashboard** (`/admin-modules/module-01-dashboard.md`)
   - 8 KPI stat cards + AI Runtime Summary + SLA Snapshot
   - All mock data (no backend calls)
   - Establishes sidebar navigation, AMIA design system
   - **Dependencies:** None
   - **Testing:** Visual verification, responsive layout, AMIA styling

2. **Module 02: Personas** (`/admin-modules/module-02-personas.md`)
   - Create persona form + personas list table
   - Production/Testing version badges with auto-versioning
   - **Dependencies:** Dashboard complete (layout, AMIA styling known)
   - **Testing:** Create, edit, publish, activate workflows; search/filter; version control

### **Phase 2: Core Admin Features** (Priority: HIGH)
Implement the core AI configuration and management modules.

3. **Module 03: Engines** (`/admin-modules/module-03-engines.md`)
   - Production (active) + Testing (latest published) sections
   - Create draft → Publish → Activate workflow
   - Version history table
   - **Dependencies:** Personas complete (version workflow pattern established)
   - **Testing:** Draft creation, publish/activate flows, version history accuracy

4. **Module 04: Skills** (`/admin-modules/module-04-skills.md`)
   - Create skill form + skills table
   - Same auto-versioning pattern as Personas/Engines
   - Usage stats display (v1: dummy data OK)
   - **Dependencies:** Engines complete (version workflow pattern reinforced)
   - **Testing:** Create, edit, publish, activate; filtering; version management

5. **Module 05: System Configuration** (`/admin-modules/module-05-system-config.md`)
   - Current settings table (shows DB override vs env var vs code default)
   - Live editing of AI runtime settings (no restart needed)
   - Primary Generation + Intent Discovery + Provider Routing sections
   - **Dependencies:** Skills complete (form patterns established)
   - **Testing:** Load current values, update each section independently, verify persistence

### **Phase 3: Operational Modules** (Priority: MEDIUM)
Implement user management and monitoring features.

6. **Module 06: Users & Test Users** (`/admin-modules/module-06-users.md`)
   - Two modules (separate tabs for Users and Test Users)
   - Users module: Search/filter, view list, delete user
   - Test Users module: Create test user form + test users list
   - **Dependencies:** System Config complete (tables/forms patterns established)
   - **Testing:** Load users, filter by gender/interest/city, create test user, delete

7. **Module 07: Conversations** (`/admin-modules/module-07-conversations.md`)
   - 3-pane layout: conversation list | chat transcript | API inspector
   - Click conversation → load transcript
   - Click message → load LLM metadata (model, tokens, latency, request/response)
   - **Dependencies:** Users complete (list/table patterns)
   - **Testing:** Load conversations, select one, click message, verify metadata displays

8. **Module 08: Test Chat** (`/admin-modules/module-08-test-chat.md`)
   - Persona selector (dropdown)
   - Version toggle (Production | Testing)
   - Chat interface with message send/receive
   - **Dependencies:** All prior modules (uses personas, respects versions)
   - **Testing:** Select persona, toggle version, send messages, verify responses

### **Phase 4: Future/Placeholder** (Priority: LOW)

9. **Module 09: Revenue & Transactions** (`/admin-modules/module-09-revenue.md`)
   - v1: Coming-soon placeholder with feature wireframes
   - v2+: Full revenue analytics (Phase 4, requires billing backend)
   - **Dependencies:** None (independent; v1 has no data loading)
   - **Testing:** Verify placeholder displays, responsive layout

---

## Architecture & Design System

### AMIA Color Palette
```css
--near-black: #0a0a0a
--deep-plum: #1a1219
--burgundy: #e85a88
--rose: #f07899
--soft-rose: #e8a8ba
--blush: #f5d5e0
--warm-white: #fffbf8
--muted-mauve: #9a8f9a
--border: #2a2229
--surface: #1a1219
--surface-2: #2a2229
```

### Typography
- **Headers (h1-h3):** Cormorant Garamond (serif), 600 weight
- **Body text:** System sans-serif (font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif)
- **Code/monospace:** 'Courier New' or 'Monaco'

### Layout Patterns
- **Main grid:** Sidebar (fixed) + Content area (flex)
- **Module sections:** Max-width 1200px, padding 20px, responsive grid
- **Forms:** Label + input stacked, form-group class for spacing
- **Tables:** Horizontal scroll on mobile, striped rows with hover states
- **Cards:** Rounded corners (14px), subtle borders, consistent padding (16px)

### Standard Component Patterns
- **Error display:** `<div class="error" id="moduleError" style="display:none;"></div>`
- **Success display:** `<div class="success" id="moduleSuccess" style="display:none;"></div>`
- **Form group:** `<div class="form-group"><label>...</label><input>...</div>`
- **Badge:** `<span class="badge success|warning|muted">Label</span>`
- **Button styles:** `<button>Primary</button>` and `<button class="secondary">Secondary</button>`

---

## Implementation Workflow for Claude Code

### Per-Module Steps

For each module, Claude Code should:

1. **Read the specification** (e.g., `admin-modules/module-02-personas.md`)

2. **Implement the HTML**
   - Copy the HTML structure from section 2 into admin-ui.html
   - Add any CSS from section 2 to the stylesheet
   - Ensure HTML IDs match the JavaScript function names

3. **Implement the JavaScript**
   - Copy JavaScript code from section 3 into admin-ui.html or external script
   - Ensure functions match module navigation triggers
   - Add helper functions (escapeHtml, authHeader(), showError, showSuccess, etc.)

4. **Wire backend endpoints**
   - Use the Backend Wiring Checklist (section 4) to identify all endpoints
   - Ensure endpoints accept Authorization header
   - Implement response parsing and error handling

5. **Test the module**
   - Follow the Testing Checklist (section 5)
   - Verify all functionality in isolation
   - Check responsive design on mobile
   - Verify AMIA styling applied
   - Confirm no console errors

6. **Integration test**
   - Verify sidebar navigation to the module works
   - Verify success/error messages display correctly
   - Verify module interacts properly with shared utilities (authHeader, escapeHtml, etc.)

---

## Shared Utilities (Required in All Modules)

Every module depends on these functions being available globally:

```javascript
// Authorization
function authHeader() {
    // Return 'Bearer <token>' or 'Authorization: <token>'
    // Implementation depends on auth system
    return 'Bearer ' + localStorage.getItem('adminToken');
}

// Error/Success display
function showError(moduleId, message) {
    const elem = document.getElementById(moduleId + 'Error');
    if (elem) {
        elem.textContent = message;
        elem.style.display = 'block';
        setTimeout(() => { elem.style.display = 'none'; }, 5000);
    }
}

function showSuccess(moduleId, message) {
    const elem = document.getElementById(moduleId + 'Success');
    if (elem) {
        elem.textContent = message;
        elem.style.display = 'block';
        setTimeout(() => { elem.style.display = 'none'; }, 3000);
    }
}

// HTML escaping
function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}

// Sidebar navigation
function navigateTo(moduleId) {
    // Hide all tab-content divs
    document.querySelectorAll('.tab-content').forEach(el => {
        el.style.display = 'none';
    });
    // Show selected module
    document.getElementById(moduleId).style.display = 'block';
    // Call module-specific load function if exists
    if (typeof window['navigateTo' + moduleId.charAt(0).toUpperCase() + moduleId.slice(1)] === 'function') {
        window['navigateTo' + moduleId.charAt(0).toUpperCase() + moduleId.slice(1)]();
    }
}
```

---

## Testing Strategy

### Per-Module Integration Testing
After implementing each module:

1. **Functionality:** Test all CRUD operations (create, read, update, delete)
2. **Error Handling:** Verify error messages display on network failures
3. **Edge Cases:** Empty fields, duplicate values, special characters in text
4. **Responsive Design:** Test on mobile, tablet, desktop viewports
5. **AMIA Styling:** Verify colors, typography, spacing match design system
6. **Console:** Ensure no JavaScript errors or warnings

### Cross-Module Testing
After completing all modules:

1. **Navigation:** Verify sidebar links to all 9 modules
2. **Data Consistency:** Verify related data (e.g., personas used in Test Chat) loads correctly
3. **Authentication:** Verify Authorization headers on all API calls
4. **State Isolation:** Verify one module doesn't affect another's state
5. **Performance:** Verify page loads in reasonable time; no UI freezing

---

## File Locations

All module specifications are located in the project under `/admin-modules/`:

```
admin-modules/
├── module-01-dashboard.md          (Phase 1)
├── module-02-personas.md           (Phase 1)
├── module-03-engines.md            (Phase 2)
├── module-04-skills.md             (Phase 2)
├── module-05-system-config.md      (Phase 2)
├── module-06-users.md              (Phase 3)
├── module-07-conversations.md      (Phase 3)
├── module-08-test-chat.md          (Phase 3)
└── module-09-revenue.md            (Phase 4)
```

---

## Success Criteria

The admin console is complete when:

✓ **All 9 modules implemented** with no console errors
✓ **Dashboard** displays with mock data and AMIA styling
✓ **Version control workflow** (create draft → publish → activate) works across Personas, Engines, Skills
✓ **API endpoints wired** for all modules with Authorization headers
✓ **Search/filter functionality** works in Users, Conversations, Skills
✓ **Sidebar navigation** smoothly transitions between all 9 modules
✓ **Mobile responsive** design works on 320px–2560px viewports
✓ **AMIA design system** consistently applied (colors, typography, spacing)
✓ **Error/success messages** display correctly on all operations
✓ **Testing workflow** (Test Chat module) allows persona testing with Production/Testing version toggle
✓ **Conversation inspector** allows drilling into chat transcripts and API metadata

---

## Next Steps for Claude Code

1. Start with **Module 01: Dashboard** to establish the layout and AMIA design system
2. Progress through Phase 1 (Dashboard, Personas) to solidify patterns
3. Continue through Phases 2 and 3 in order, building on established patterns
4. Test each module independently before moving to the next
5. After all 9 modules, perform full integration testing
6. Deploy to production with proper authentication and error handling

---

**Generated:** 2026-09-21  
**Version:** 1.0  
**Maintained in:** Project documentation
