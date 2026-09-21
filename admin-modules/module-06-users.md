# Admin Module 06: Users & Test Users

**Module Name:** Users Management  
**Module IDs:** `users` (Users), `testusers` (Test Users)  
**Sidebar Group:** Users, Operations  
**Backend Routes:** `AdminUserRoutes.kt`  
**Priority:** Phase 3 (Operational)

---

## 1. Requirements

**Purpose:** Manage user accounts and create test users for QA.

**Key Features:**
- **Users module:** Search/filter users (by name, gender, interest, city, age range), view list, detail view
- **Test Users module:** Same as Users but filtered to `is_test=true` records only
- **Create Test User form:** Display name, gender, interest, city, age (visible in both modules)
- **Search/Filter:** Real-time filtering by text + optional facets
- **Detail view:** Click a user → show full profile, conversation count, memory count, delete button

---

## 2. HTML Structure (Users Module)

```html
<div id="users" class="tab-content">
    <div class="section">
        <h2>Users</h2>
        <div class="error" id="usersError" style="display:none;"></div>
        
        <!-- Search/Filter -->
        <div style="margin-bottom: 20px; display: flex; gap: 12px; flex-wrap: wrap;">
            <div style="flex: 1; min-width: 200px;">
                <label>Search by name:</label>
                <input type="text" id="userSearchText" placeholder="Name or ID..." onkeyup="filterUsers()">
            </div>
            <div style="flex: 0.5; min-width: 100px;">
                <label>Gender:</label>
                <select id="userFilterGender" onchange="filterUsers()">
                    <option value="">All</option>
                    <option value="male">Male</option>
                    <option value="female">Female</option>
                </select>
            </div>
            <div style="flex: 0.5; min-width: 100px;">
                <label>Interest:</label>
                <select id="userFilterInterest" onchange="filterUsers()">
                    <option value="">All</option>
                    <option value="male">Male</option>
                    <option value="female">Female</option>
                    <option value="both">Both</option>
                </select>
            </div>
            <div style="flex: 0.5; min-width: 100px; align-self: flex-end;">
                <button class="secondary" onclick="loadUsers()" style="width: 100%;">Reset</button>
            </div>
        </div>
        
        <!-- Users Table -->
        <div class="table-wrap">
            <table id="usersTable">
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Gender</th>
                        <th>Interest</th>
                        <th>City</th>
                        <th>Age</th>
                        <th>Joined</th>
                        <th>Conversations</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="usersTableBody"></tbody>
            </table>
        </div>
    </div>
</div>

<!-- Test Users Module (identical structure, filtered to is_test=true) -->
<div id="testusers" class="tab-content">
    <div class="section">
        <h2>Test Users</h2>
        <div class="error" id="testusersError" style="display:none;"></div>
        
        <!-- Create Test User Form -->
        <div style="margin-bottom: 30px;">
            <h3>Create Test User</h3>
            <div class="form-group">
                <label>Display Name: *</label>
                <input type="text" id="newUserName" placeholder="e.g., Test Tara">
            </div>
            <div class="form-group">
                <label>Gender:</label>
                <input type="text" id="newUserGender" placeholder="e.g., female">
            </div>
            <div class="form-group">
                <label>Interest:</label>
                <select id="newUserInterest">
                    <option value="">Not set</option>
                    <option value="male">male</option>
                    <option value="female">female</option>
                    <option value="both">both</option>
                </select>
            </div>
            <div class="form-group">
                <label>City:</label>
                <input type="text" id="newUserCity" placeholder="e.g., Delhi">
            </div>
            <div class="form-group">
                <label>Age:</label>
                <input type="number" id="newUserAge" min="18" max="120" placeholder="28">
            </div>
            <button onclick="createTestUser()">Create Test User</button>
            <button class="secondary" onclick="loadTestUsers()" style="margin-left: 8px;">Refresh</button>
        </div>
        
        <hr style="margin: 30px 0;">
        <h3>All Test Users</h3>
        
        <!-- Test Users Table (same structure as Users) -->
        <div class="table-wrap">
            <table id="testusersTable">
                <thead>
                    <tr>
                        <th>Name</th>
                        <th>Gender</th>
                        <th>Interest</th>
                        <th>City</th>
                        <th>Age</th>
                        <th>Created</th>
                        <th>Conversations</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody id="testusersTableBody"></tbody>
            </table>
        </div>
    </div>
</div>
```

---

## 3. JavaScript

```javascript
let allUsers = [];

async function loadUsers() {
    try {
        const res = await fetch('/v1/admin/users?is_test=false', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        allUsers = await res.json();
        renderUsersTable(allUsers);
    } catch (e) {
        showError('users', 'Failed to load users: ' + e.message);
    }
}

async function loadTestUsers() {
    try {
        const res = await fetch('/v1/admin/users?is_test=true', {
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const testUsers = await res.json();
        renderTestUsersTable(testUsers);
    } catch (e) {
        showError('testusers', 'Failed to load test users: ' + e.message);
    }
}

function renderUsersTable(users) {
    const tbody = document.getElementById('usersTableBody');
    tbody.innerHTML = users.map(u => `
        <tr>
            <td>${escapeHtml(u.displayName)}</td>
            <td>${escapeHtml(u.gender || '—')}</td>
            <td>${escapeHtml(u.interest || '—')}</td>
            <td>${escapeHtml(u.city || '—')}</td>
            <td>${u.age || '—'}</td>
            <td>${new Date(u.createdAt).toLocaleDateString()}</td>
            <td>${u.conversationCount || 0}</td>
            <td>
                <button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="viewUserDetail('${u.id}')">Detail</button>
                <button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="deleteUser('${u.id}')">Delete</button>
            </td>
        </tr>
    `).join('');
}

function renderTestUsersTable(users) {
    const tbody = document.getElementById('testusersTableBody');
    tbody.innerHTML = users.map(u => `
        <tr>
            <td>${escapeHtml(u.displayName)}</td>
            <td>${escapeHtml(u.gender || '—')}</td>
            <td>${escapeHtml(u.interest || '—')}</td>
            <td>${escapeHtml(u.city || '—')}</td>
            <td>${u.age || '—'}</td>
            <td>${new Date(u.createdAt).toLocaleDateString()}</td>
            <td>${u.conversationCount || 0}</td>
            <td>
                <button class="secondary" style="font-size: 11px; padding: 4px 8px;" onclick="deleteUser('${u.id}')">Delete</button>
            </td>
        </tr>
    `).join('');
}

function filterUsers() {
    const text = document.getElementById('userSearchText').value.toLowerCase();
    const gender = document.getElementById('userFilterGender').value;
    const interest = document.getElementById('userFilterInterest').value;
    
    const filtered = allUsers.filter(u => {
        const nameMatch = !text || u.displayName.toLowerCase().includes(text) || u.id.includes(text);
        const genderMatch = !gender || u.gender === gender;
        const interestMatch = !interest || u.interest === interest;
        return nameMatch && genderMatch && interestMatch;
    });
    
    renderUsersTable(filtered);
}

async function createTestUser() {
    const body = {
        displayName: document.getElementById('newUserName').value.trim(),
        gender: document.getElementById('newUserGender').value.trim(),
        interest: document.getElementById('newUserInterest').value || null,
        city: document.getElementById('newUserCity').value.trim(),
        age: parseInt(document.getElementById('newUserAge').value) || null,
        isTest: true
    };
    
    if (!body.displayName) {
        showError('testusers', 'Display name is required');
        return;
    }
    
    try {
        const res = await fetch('/v1/admin/users', {
            method: 'POST',
            headers: { 
                'Authorization': authHeader(),
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(body)
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('testusers', 'Test user created');
        document.getElementById('newUserName').value = '';
        document.getElementById('newUserGender').value = '';
        document.getElementById('newUserInterest').value = '';
        document.getElementById('newUserCity').value = '';
        document.getElementById('newUserAge').value = '';
        loadTestUsers();
    } catch (e) {
        showError('testusers', 'Failed to create test user: ' + e.message);
    }
}

async function viewUserDetail(userId) {
    alert('User detail view not yet implemented for: ' + userId);
}

async function deleteUser(userId) {
    if (!confirm('Are you sure you want to delete this user?')) return;
    
    try {
        const res = await fetch(`/v1/admin/users/${userId}`, {
            method: 'DELETE',
            headers: { 'Authorization': authHeader() }
        });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        
        showSuccess('users', 'User deleted');
        loadUsers();
    } catch (e) {
        showError('users', 'Failed to delete user: ' + e.message);
    }
}

function navigateToUsers() {
    loadUsers();
}

function navigateToTestUsers() {
    loadTestUsers();
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[c]));
}
```

---

## 4. Backend Wiring Checklist

- [ ] `GET /v1/admin/users?is_test=false` → List real users
- [ ] `GET /v1/admin/users?is_test=true` → List test users
- [ ] `GET /v1/admin/users?gender=X&interest=Y&city=Z` → Filter users
- [ ] `POST /v1/admin/users` with `is_test=true` → Create test user
- [ ] `DELETE /v1/admin/users/{id}` → Delete user
- [ ] `GET /v1/admin/users/{id}` → Get user detail

---

## 5. Testing Checklist

- [ ] Load Users; verify list populates
- [ ] Load Test Users; verify list populates
- [ ] Create a test user; verify it appears in test users list
- [ ] Filter users by gender/interest; verify filtering works
- [ ] Search by name; verify search works
- [ ] Delete a test user; verify deletion
- [ ] Verify success/error messages

---

## 6. Success Criteria

✓ Users module complete when:
- Users and Test Users both load and display
- Create test user form works
- Search/filter functionality works
- Delete user button works
- AMIA styling applied
- No console errors
