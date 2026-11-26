'use strict';

const API_BASE = '/api/tasks';
const REQUEST_TIMEOUT_MS = 8000;
const STATUS_LABELS = { TODO: '待办', DOING: '进行中', BLOCKED: '阻塞', DONE: '已完成' };
const PRIORITY_LABELS = { LOW: '低', NORMAL: '普通', HIGH: '高', URGENT: '紧急' };
// Mirrors the backend TaskActivity type enum (CREATED / UPDATED / STATUS_CHANGED / DELETED).
const ACTIVITY_TYPE_LABELS = { CREATED: '创建', UPDATED: '更新', STATUS_CHANGED: '状态', DELETED: '删除' };
// Mirrors the import row cap the server enforces; checked up front to fail fast without a round trip.
const IMPORT_MAX_ROWS = 1000;
// Mirrors TaskStatus.ALLOWED on the server. Written out rather than derived so a drift is
// visible in review. The server side is pinned by transitionTableMatchesTheDocumentedMatrix;
// this copy is not, so a change to the table must be made in both places.
const ALLOWED_TRANSITIONS = {
    TODO: ['DOING', 'BLOCKED'],
    DOING: ['TODO', 'BLOCKED', 'DONE'],
    BLOCKED: ['TODO', 'DOING'],
    DONE: [],
};
// Each status gets an explicit button target, because drag-and-drop is unavailable on touch and
// to keyboard users. DONE has none: it is terminal.
const BUTTON_TARGET = {
    TODO: { to: 'DOING', label: '推进' },
    DOING: { to: 'DONE', label: '推进' },
    BLOCKED: { to: 'DOING', label: '恢复进行中' },
    DONE: null,
};

function canMove(from, to) {
    if (from === to) {
        return true;
    }
    const allowed = ALLOWED_TRANSITIONS[from];
    return Array.isArray(allowed) && allowed.includes(to);
}

let latestLoadId = 0;
let tasks = [];
let filters = { q: '', status: '', priority: '', overdue: false, sort: 'createdAt', order: 'desc' };

// Batch selection survives re-renders (loadTasks rebuilds every card) and filters: hiding a task
// must not silently drop it from the selection, so the set is pruned only when tasks really go away.
let selectedIds = new Set();
let batchBusy = false;
let pendingImport = null;
let activityRequestId = 0;
let detailTaskId = null;

const els = {
    statTotal: document.getElementById('stat-total'),
    statTodo: document.getElementById('stat-todo'),
    statDoing: document.getElementById('stat-doing'),
    statBlocked: document.getElementById('stat-blocked'),
    statDone: document.getElementById('stat-done'),
    statOverdue: document.getElementById('stat-overdue'),

    quickForm: document.getElementById('quick-form'),
    quickInput: document.getElementById('quick-title'),
    quickSubmit: document.getElementById('quick-submit'),
    openDialog: document.getElementById('open-dialog-button'),

    hint: document.getElementById('hint'),
    filterCount: document.getElementById('filter-count'),

    filterQ: document.getElementById('filter-q'),
    filterStatus: document.getElementById('filter-status'),
    filterPriority: document.getElementById('filter-priority'),
    filterOverdue: document.getElementById('filter-overdue'),
    sortKey: document.getElementById('sort-key'),
    sortOrder: document.getElementById('sort-order'),
    filterReset: document.getElementById('filter-reset'),

    exportButton: document.getElementById('export-button'),
    importButton: document.getElementById('import-button'),
    importFile: document.getElementById('import-file'),

    batchBar: document.getElementById('batch-bar'),
    batchCount: document.getElementById('batch-count'),
    batchSelectAll: document.getElementById('batch-select-all'),
    batchClear: document.getElementById('batch-clear'),
    batchTarget: document.getElementById('batch-target'),
    batchApply: document.getElementById('batch-apply'),
    batchDelete: document.getElementById('batch-delete'),
    batchResults: document.getElementById('batch-results'),
    batchSummary: document.getElementById('batch-summary'),
    batchFailures: document.getElementById('batch-failures'),
    batchResultsClose: document.getElementById('batch-results-close'),

    detailDialog: document.getElementById('detail-dialog'),
    detailTitle: document.getElementById('detail-title'),
    detailMeta: document.getElementById('detail-meta'),
    activitiesState: document.getElementById('activities-state'),
    activitiesList: document.getElementById('activities-list'),
    activityRetry: document.getElementById('activity-retry'),
    detailClose: document.getElementById('detail-close'),

    importDialog: document.getElementById('import-dialog'),
    importForm: document.getElementById('import-form'),
    importFileName: document.getElementById('import-file-name'),
    importWarning: document.getElementById('import-warning'),
    importHint: document.getElementById('import-hint'),
    importCancel: document.getElementById('import-cancel'),
    importSubmit: document.getElementById('import-submit'),

    loading: document.getElementById('state-loading'),
    empty: document.getElementById('state-empty'),
    error: document.getElementById('state-error'),
    errorMessage: document.getElementById('state-error-message'),
    retry: document.getElementById('retry-button'),
    board: document.getElementById('board'),
    cardTemplate: document.getElementById('task-card-template'),

    dialog: document.getElementById('task-dialog'),
    dialogTitle: document.getElementById('dialog-title'),
    form: document.getElementById('task-form'),
    formId: document.getElementById('form-id'),
    formTitle: document.getElementById('form-title'),
    formPriority: document.getElementById('form-priority'),
    formAssignee: document.getElementById('form-assignee'),
    formDue: document.getElementById('form-due'),
    formTags: document.getElementById('form-tags'),
    formHint: document.getElementById('form-hint'),
    formSubmit: document.getElementById('form-submit'),
    formCancel: document.getElementById('form-cancel'),
};

/* ---------------------------------------------------------------- messaging */

function setHint(text, tone) {
    els.hint.textContent = text;
    if (tone) {
        els.hint.dataset.tone = tone;
    } else {
        delete els.hint.dataset.tone;
    }
}

function setFormHint(text, tone) {
    els.formHint.textContent = text;
    if (tone) {
        els.formHint.dataset.tone = tone;
    } else {
        delete els.formHint.dataset.tone;
    }
}

function setImportHint(text, tone) {
    els.importHint.textContent = text;
    if (tone) {
        els.importHint.dataset.tone = tone;
    } else {
        delete els.importHint.dataset.tone;
    }
}

/* ------------------------------------------------------------------ network */

async function request(url, options) {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

    let response;
    try {
        response = await fetch(url, Object.assign({ signal: controller.signal }, options));
    } catch (error) {
        if (error.name === 'AbortError') {
            throw new Error('请求超时，请稍后重试');
        }
        throw new Error('无法连接服务端');
    } finally {
        window.clearTimeout(timer);
    }

    if (response.status === 204) {
        return null;
    }

    // Blob mode is for file downloads (export): the JSON error body above still applies, a
    // success resolves to the raw bytes plus the Content-Disposition header so the caller can
    // keep the server-chosen filename.
    if (options && options.blob && response.ok) {
        const blob = await response.blob();
        return { blob, disposition: response.headers.get('Content-Disposition') };
    }

    let payload = null;
    try {
        payload = await response.json();
    } catch (error) {
        payload = null;
    }

    if (!response.ok) {
        const message = payload && typeof payload.message === 'string'
            ? payload.message
            : `请求失败（HTTP ${response.status}）`;
        const error = new Error(message);
        error.code = payload && payload.code ? payload.code : null;
        error.status = response.status;
        throw error;
    }
    return payload;
}

/* ------------------------------------------------------------------ filters */

function readFiltersFromHash() {
    const raw = window.location.hash.startsWith('#') ? window.location.hash.slice(1) : '';
    const params = new URLSearchParams(raw);
    filters = {
        q: params.get('q') || '',
        status: params.get('status') || '',
        priority: params.get('priority') || '',
        overdue: params.get('overdue') === '1',
        sort: params.get('sort') || 'createdAt',
        order: params.get('order') === 'asc' ? 'asc' : 'desc',
    };
    syncFilterControls();
}

function writeFiltersToHash() {
    const params = new URLSearchParams();
    if (filters.q) params.set('q', filters.q);
    if (filters.status) params.set('status', filters.status);
    if (filters.priority) params.set('priority', filters.priority);
    if (filters.overdue) params.set('overdue', '1');
    if (filters.sort !== 'createdAt') params.set('sort', filters.sort);
    if (filters.order !== 'desc') params.set('order', filters.order);

    const next = params.toString() ? `#${params}` : '';
    // replaceState, not a hash assignment: filtering should not bury a real "back" navigation
    // under a dozen history entries, but the URL must still be shareable.
    if (next !== window.location.hash) {
        window.history.replaceState(null, '', next === '' ? window.location.pathname : next);
    }
}

function syncFilterControls() {
    els.filterQ.value = filters.q;
    els.filterStatus.value = filters.status;
    els.filterPriority.value = filters.priority;
    els.filterOverdue.checked = filters.overdue;
    els.sortKey.value = filters.sort;
    els.sortOrder.textContent = filters.order === 'asc' ? '升序' : '降序';
    els.sortOrder.setAttribute('aria-pressed', String(filters.order === 'asc'));
}

function buildQueryString() {
    const params = new URLSearchParams();
    if (filters.q) params.set('q', filters.q);
    if (filters.status) params.set('status', filters.status);
    if (filters.priority) params.set('priority', filters.priority);
    if (filters.overdue) params.set('overdue', 'true');
    params.set('sort', filters.sort);
    params.set('order', filters.order);
    return params.toString();
}

/* -------------------------------------------------------------------- stats */

function renderStats() {
    const counts = { TODO: 0, DOING: 0, BLOCKED: 0, DONE: 0 };
    let overdue = 0;
    for (const task of tasks) {
        if (counts[task.status] !== undefined) {
            counts[task.status] += 1;
        }
        if (task.overdue) {
            overdue += 1;
        }
    }
    els.statTotal.textContent = String(tasks.length);
    els.statTodo.textContent = String(counts.TODO);
    els.statDoing.textContent = String(counts.DOING);
    els.statBlocked.textContent = String(counts.BLOCKED);
    els.statDone.textContent = String(counts.DONE);
    els.statOverdue.textContent = String(overdue);
}

/* --------------------------------------------------------------------- board */

function showState(name) {
    els.loading.hidden = name !== 'loading';
    els.empty.hidden = name !== 'empty';
    els.error.hidden = name !== 'error';
    els.board.hidden = name !== 'board';
}

function renderBoard() {
    document.querySelectorAll('.column__body').forEach((body) => body.replaceChildren());
    document.querySelectorAll('.column__count').forEach((node) => {
        node.textContent = '0';
    });

    for (const task of tasks) {
        const body = document.querySelector(`.column__body[data-drop="${task.status}"]`);
        if (body) {
            body.appendChild(renderCard(task));
        }
    }

    const counts = { TODO: 0, DOING: 0, BLOCKED: 0, DONE: 0 };
    document.querySelectorAll('.column__body').forEach((body) => {
        const count = body.querySelectorAll('.card').length;
        counts[body.dataset.drop] = count;
        const label = document.querySelector(`.column__count[data-count="${body.dataset.drop}"]`);
        if (label) {
            label.textContent = String(count);
        }
    });

    const totalVisible = Object.values(counts).reduce((sum, value) => sum + value, 0);
    els.filterCount.textContent = totalVisible === tasks.length
        ? `共 ${tasks.length} 条`
        : `筛出 ${tasks.length} 条`;

    showState(tasks.length === 0 ? 'empty' : 'board');
}

function renderCard(task) {
    const node = els.cardTemplate.content.firstElementChild.cloneNode(true);
    node.dataset.id = String(task.id);
    node.dataset.status = task.status;
    node.draggable = true;

    node.querySelector('.card__title').textContent = task.title;
    node.querySelector('.card__priority').dataset.priority = task.priority;
    node.classList.toggle('card--done', task.status === 'DONE');
    node.classList.toggle('card--overdue', Boolean(task.overdue));

    const assignee = node.querySelector('.card__assignee');
    if (task.assignee) {
        assignee.textContent = `@${task.assignee}`;
    } else {
        assignee.hidden = true;
    }

    const due = node.querySelector('.card__due');
    if (task.dueDate) {
        due.textContent = task.overdue
            ? `${task.dueDate} · 已逾期 ${task.daysOverdue} 天`
            : task.dueDate;
    } else {
        due.hidden = true;
    }

    const tagList = node.querySelector('.card__tags');
    if (Array.isArray(task.tags) && task.tags.length > 0) {
        for (const tag of task.tags) {
            const chip = document.createElement('li');
            chip.className = 'chip';
            chip.textContent = tag;
            tagList.appendChild(chip);
        }
    } else {
        tagList.hidden = true;
    }

    const advance = node.querySelector('[data-action="advance"]');
    const action = BUTTON_TARGET[task.status];
    if (!action) {
        advance.hidden = true;
    } else {
        advance.textContent = action.label;
        advance.title = `改为「${STATUS_LABELS[action.to]}」`;
    }

    const check = node.querySelector('.card__check');
    check.checked = selectedIds.has(task.id);
    check.setAttribute('aria-label', `选择「${task.title}」`);

    return node;
}

/* -------------------------------------------------------------------- loading */

async function loadTasks() {
    const requestId = ++latestLoadId;
    showState('loading');
    try {
        const query = buildQueryString();
        const data = await request(`${API_BASE}${query ? `?${query}` : ''}`, {
            headers: { Accept: 'application/json' },
        });
        if (requestId !== latestLoadId) {
            return;
        }
        tasks = Array.isArray(data) ? data : [];
        renderStats();
        renderBoard();
        updateBatchBar();
        writeFiltersToHash();
    } catch (error) {
        if (requestId !== latestLoadId) {
            return;
        }
        els.errorMessage.textContent = `加载任务失败：${error.message}`;
        showState('error');
    }
}

/* --------------------------------------------------------------------- cards */

function findCard(button) {
    const card = button.closest('.card');
    if (!card || !els.board.contains(card)) {
        return null;
    }
    return card;
}

function setCardBusy(card, busy) {
    if (busy) {
        card.dataset.busy = '1';
    } else {
        delete card.dataset.busy;
    }
    card.querySelectorAll('button').forEach((button) => {
        button.disabled = busy;
        if (busy) {
            button.setAttribute('aria-busy', 'true');
        } else {
            button.removeAttribute('aria-busy');
        }
    });
}

async function createTask(title) {
    els.quickSubmit.disabled = true;
    els.quickSubmit.setAttribute('aria-busy', 'true');
    setHint('正在提交…');
    try {
        await request(API_BASE, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ title }),
        });
        els.quickInput.value = '';
        await loadTasks();
        setHint('已添加。');
    } catch (error) {
        setHint(`添加失败：${error.message}`, 'error');
    } finally {
        els.quickSubmit.disabled = false;
        els.quickSubmit.removeAttribute('aria-busy');
    }
}

async function advanceTask(card, task) {
    const action = BUTTON_TARGET[task.status];
    if (!action) {
        return;
    }
    const next = action.to;
    // Set synchronously: a double click lands here twice before the first await resolves.
    if (card.dataset.busy === '1') {
        return;
    }
    setCardBusy(card, true);
    try {
        await request(`${API_BASE}/${encodeURIComponent(task.id)}/status`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ status: next }),
        });
        await loadTasks();
        setHint(`已推进到 ${STATUS_LABELS[next]}。`);
    } catch (error) {
        setHint(`更新状态失败：${error.message}`, 'error');
        setCardBusy(card, false);
    }
}

async function deleteTask(card, task) {
    if (card.dataset.busy === '1') {
        return;
    }
    if (!window.confirm(`确定删除「${task.title}」？`)) {
        return;
    }
    setCardBusy(card, true);
    try {
        await request(`${API_BASE}/${encodeURIComponent(task.id)}`, { method: 'DELETE' });
        selectedIds.delete(task.id);
        updateBatchBar();
        await loadTasks();
        setHint('已删除。');
    } catch (error) {
        setHint(`删除失败：${error.message}`, 'error');
        setCardBusy(card, false);
    }
}

/* --------------------------------------------------------------- drag & drop */

let draggedTaskId = null;

els.board.addEventListener('dragstart', (event) => {
    const card = event.target.closest('.card');
    if (!card) {
        return;
    }
    const task = tasks.find((item) => String(item.id) === card.dataset.id);
    if (!task) {
        return;
    }
    if (!Array.isArray(ALLOWED_TRANSITIONS[task.status]) || ALLOWED_TRANSITIONS[task.status].length === 0) {
        // DONE is terminal on the server; refusing the drag up front avoids a pointless request
        // and explains why the card will not move instead of silently springing back.
        event.preventDefault();
        setHint('已完成的任务不能改状态。', 'error');
        return;
    }
    draggedTaskId = task.id;
    card.classList.add('card--dragging');
    if (event.dataTransfer) {
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', String(task.id));
    }
});

els.board.addEventListener('dragend', () => {
    document.querySelectorAll('.card--dragging').forEach((card) => card.classList.remove('card--dragging'));
    document.querySelectorAll('.column__body--over').forEach((body) => body.classList.remove('column__body--over'));
    draggedTaskId = null;
});

els.board.addEventListener('dragover', (event) => {
    const body = event.target.closest('.column__body');
    if (!body || draggedTaskId === null) {
        return;
    }
    const sourceTask = tasks.find((item) => item.id === draggedTaskId);
    if (!sourceTask || !canMove(sourceTask.status, body.dataset.drop)) {
        // Ask the same allow table the server asks. Rejecting by target column alone was wrong in
        // the other direction: "anything into TODO is fine" would have accepted DONE -> TODO.
        return;
    }
    event.preventDefault();
    if (event.dataTransfer) {
        event.dataTransfer.dropEffect = 'move';
    }
    if (!body.classList.contains('column__body--over')) {
        document.querySelectorAll('.column__body--over').forEach((node) => node.classList.remove('column__body--over'));
        body.classList.add('column__body--over');
    }
});

els.board.addEventListener('drop', async (event) => {
    const body = event.target.closest('.column__body');
    if (!body || draggedTaskId === null) {
        return;
    }
    event.preventDefault();
    const target = body.dataset.drop;
    const task = tasks.find((item) => item.id === draggedTaskId);
    draggedTaskId = null;
    body.classList.remove('column__body--over');
    if (!task) {
        return;
    }
    if (task.status === target) {
        return;
    }
    if (!canMove(task.status, target)) {
        // Say why instead of letting the card spring back with no explanation.
        setHint(`不能从「${STATUS_LABELS[task.status]}」直接移到「${STATUS_LABELS[target]}」。`, 'error');
        return;
    }
    await moveTask(task, target);
});

async function moveTask(task, target) {
    const card = els.board.querySelector(`.card[data-id="${task.id}"]`);
    if (card && card.dataset.busy === '1') {
        return;
    }
    if (card) {
        setCardBusy(card, true);
    }
    try {
        await request(`${API_BASE}/${encodeURIComponent(task.id)}/status`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ status: target }),
        });
        await loadTasks();
        setHint(`已移动到 ${STATUS_LABELS[target]}。`);
    } catch (error) {
        setHint(`移动失败：${error.message}`, 'error');
        if (card) {
            setCardBusy(card, false);
        }
    }
}

/* -------------------------------------------------------------------- dialog */

function openDialogForCreate() {
    els.dialogTitle.textContent = '新建任务';
    els.form.reset();
    els.formId.value = '';
    els.formPriority.value = 'NORMAL';
    setFormHint('');
    els.dialog.showModal();
    els.formTitle.focus();
}

function openDialogForEdit(task) {
    els.dialogTitle.textContent = '编辑任务';
    els.formId.value = String(task.id);
    els.formTitle.value = task.title;
    els.formPriority.value = task.priority;
    els.formAssignee.value = task.assignee || '';
    els.formDue.value = task.dueDate || '';
    els.formTags.value = Array.isArray(task.tags) ? task.tags.join(', ') : '';
    setFormHint('');
    els.dialog.showModal();
    els.formTitle.focus();
}

function collectFormPayload() {
    const tags = els.formTags.value
        .split(',')
        .map((tag) => tag.trim())
        .filter((tag) => tag.length > 0);

    const payload = {
        title: els.formTitle.value.trim(),
        priority: els.formPriority.value,
        assignee: els.formAssignee.value.trim(),
        dueDate: els.formDue.value || null,
        tags,
    };
    // No status field: every new task starts at TODO on the server, and existing tasks change
    // status only through PATCH. Sending one here would be silently dropped, which is exactly
    // the kind of "UI promises A, backend does B" gap this board must not have.
    return payload;
}

function validatePayload(payload) {
    if (payload.title.length === 0) {
        return '标题不能为空。';
    }
    if (payload.title.length > 80) {
        return '标题不能超过 80 个字符。';
    }
    if (payload.assignee && payload.assignee.length > 40) {
        return '负责人不能超过 40 个字符。';
    }
    if (payload.tags.length > 5) {
        return '标签最多 5 个。';
    }
    if (payload.tags.some((tag) => tag.length > 20)) {
        return '单个标签不能超过 20 个字符。';
    }
    return null;
}

async function submitForm(event) {
    event.preventDefault();
    const payload = collectFormPayload();
    const problem = validatePayload(payload);
    if (problem) {
        setFormHint(problem, 'error');
        return;
    }

    const id = els.formId.value;
    els.formSubmit.disabled = true;
    els.formSubmit.setAttribute('aria-busy', 'true');
    setFormHint('正在保存…');
    try {
        if (id) {
            await request(`${API_BASE}/${encodeURIComponent(id)}`, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify(payload),
            });
        } else {
            await request(API_BASE, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify(payload),
            });
        }
        els.dialog.close();
        await loadTasks();
        setHint(id ? '已保存修改。' : '已创建任务。');
    } catch (error) {
        setFormHint(`保存失败：${error.message}`, 'error');
    } finally {
        els.formSubmit.disabled = false;
        els.formSubmit.removeAttribute('aria-busy');
    }
}

/* --------------------------------------------------------------------- batch */

// A batch response is 200 even when half the rows failed, so the HTTP status says nothing about
// the outcome: success/failure counts may only come from parsing `results` item by item.
function summarizeBatchResults(label, data) {
    if (!data || !Array.isArray(data.results)) {
        setHint(`${label}：响应缺少 results 数组，请刷新后重试。`, 'error');
        return;
    }
    const results = data.results;
    let okCount = 0;
    const failures = [];
    for (const item of results) {
        if (item && item.ok === true) {
            okCount += 1;
        } else {
            failures.push(item);
        }
    }

    const summary = `${label}：成功 ${okCount} 条 / 失败 ${failures.length} 条`;
    els.batchSummary.textContent = summary;
    if (failures.length > 0) {
        els.batchSummary.dataset.tone = 'error';
    } else {
        delete els.batchSummary.dataset.tone;
    }

    els.batchFailures.replaceChildren();
    for (const item of failures) {
        const li = document.createElement('li');
        li.className = 'batch-results__item';
        const id = item && item.id !== undefined ? `#${item.id}` : '#?';
        const code = item && item.code ? item.code : 'UNKNOWN';
        const message = item && typeof item.message === 'string' ? item.message : '无错误信息';
        li.textContent = `${id} · ${code} · ${message}`;
        els.batchFailures.appendChild(li);
    }
    els.batchResults.hidden = false;

    setHint(summary, failures.length > 0 ? 'error' : undefined);
    return failures.length;
}

function setBatchBusy(busy) {
    batchBusy = busy;
    if (busy) {
        els.batchBar.setAttribute('aria-busy', 'true');
    } else {
        els.batchBar.removeAttribute('aria-busy');
    }
    updateBatchBar();
}

function updateBatchBar() {
    const count = selectedIds.size;
    els.batchBar.hidden = count === 0;
    els.batchCount.textContent = `已选 ${count} / 共 ${tasks.length} 条`;

    els.batchSelectAll.disabled = batchBusy || tasks.length === 0;
    els.batchClear.disabled = batchBusy || count === 0;
    els.batchTarget.disabled = batchBusy || count === 0;
    els.batchApply.disabled = batchBusy || count === 0;
    els.batchDelete.disabled = batchBusy || count === 0;
    els.batchResultsClose.disabled = batchBusy;
}

async function runBatchStatus() {
    const ids = [...selectedIds];
    const status = els.batchTarget.value;
    if (ids.length === 0 || batchBusy || !STATUS_LABELS[status]) {
        return;
    }
    setBatchBusy(true);
    setHint('正在批量推进…');
    try {
        const data = await request(`${API_BASE}/batch-status`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ ids, status }),
        });
        summarizeBatchResults(`批量推进到「${STATUS_LABELS[status]}」`, data);
        selectedIds.clear();
        updateBatchBar();
        await loadTasks();
    } catch (error) {
        setHint(`批量推进失败：${error.message}`, 'error');
    } finally {
        setBatchBusy(false);
    }
}

async function runBatchDelete() {
    const ids = [...selectedIds];
    if (ids.length === 0 || batchBusy) {
        return;
    }
    if (!window.confirm(`确定删除所选的 ${ids.length} 个任务？此操作不可恢复。`)) {
        return;
    }
    setBatchBusy(true);
    setHint('正在批量删除…');
    try {
        const data = await request(`${API_BASE}/batch-delete`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify({ ids }),
        });
        summarizeBatchResults('批量删除', data);
        selectedIds.clear();
        updateBatchBar();
        await loadTasks();
    } catch (error) {
        setHint(`批量删除失败：${error.message}`, 'error');
    } finally {
        setBatchBusy(false);
    }
}

/* --------------------------------------------------------------------- detail */

function formatDateTime(iso) {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
        return '—';
    }
    return date.toLocaleString('zh-CN', { hour12: false });
}

function relativeTime(iso) {
    const time = new Date(iso).getTime();
    if (Number.isNaN(time)) {
        return '';
    }
    const diff = Date.now() - time;
    if (diff < 60000) {
        return '刚刚';
    }
    const minutes = Math.floor(diff / 60000);
    if (minutes < 60) {
        return `${minutes} 分钟前`;
    }
    const hours = Math.floor(minutes / 60);
    if (hours < 24) {
        return `${hours} 小时前`;
    }
    const days = Math.floor(hours / 24);
    if (days < 7) {
        return `${days} 天前`;
    }
    return formatDateTime(iso);
}

function renderDetailMeta(task) {
    const rows = [
        ['状态', STATUS_LABELS[task.status] || task.status],
        ['优先级', PRIORITY_LABELS[task.priority] || task.priority],
        ['负责人', task.assignee || '—'],
        ['截止日期', task.dueDate
            ? (task.overdue ? `${task.dueDate}（已逾期 ${task.daysOverdue} 天）` : task.dueDate)
            : '—'],
        ['标签', Array.isArray(task.tags) && task.tags.length > 0 ? task.tags.join('、') : '—'],
        ['创建时间', formatDateTime(task.createdAt)],
        ['更新时间', formatDateTime(task.updatedAt)],
    ];

    els.detailMeta.replaceChildren();
    for (const [label, value] of rows) {
        const dt = document.createElement('dt');
        dt.textContent = label;
        const dd = document.createElement('dd');
        dd.textContent = value;
        els.detailMeta.appendChild(dt);
        els.detailMeta.appendChild(dd);
    }
}

function showActivityState(text, tone) {
    els.activitiesState.textContent = text;
    if (tone) {
        els.activitiesState.dataset.tone = tone;
    } else {
        delete els.activitiesState.dataset.tone;
    }
    els.activitiesList.replaceChildren();
    els.activityRetry.hidden = tone !== 'error';
}

function renderActivities(activities) {
    if (activities.length === 0) {
        showActivityState('暂无活动记录。');
        return;
    }
    showActivityState('');
    for (const activity of activities) {
        const li = document.createElement('li');
        li.className = 'activity';
        if (activity.type === 'DELETED') {
            li.classList.add('activity--deleted');
        }

        const badge = document.createElement('span');
        badge.className = 'activity__type';
        badge.dataset.type = activity.type;
        badge.textContent = ACTIVITY_TYPE_LABELS[activity.type] || activity.type;

        const body = document.createElement('div');
        body.className = 'activity__body';
        const detail = document.createElement('p');
        detail.className = 'activity__detail';
        detail.textContent = typeof activity.detail === 'string' && activity.detail.length > 0
            ? activity.detail
            : '—';
        const time = document.createElement('time');
        time.className = 'activity__time';
        if (activity.createdAt) {
            time.setAttribute('datetime', activity.createdAt);
        }
        time.textContent = relativeTime(activity.createdAt);

        body.appendChild(detail);
        body.appendChild(time);
        li.appendChild(badge);
        li.appendChild(body);
        els.activitiesList.appendChild(li);
    }
}

async function loadActivities(taskId) {
    const requestId = ++activityRequestId;
    showActivityState('正在加载活动记录…');
    try {
        const data = await request(`${API_BASE}/${encodeURIComponent(taskId)}/activities?limit=50`, {
            headers: { Accept: 'application/json' },
        });
        if (requestId !== activityRequestId) {
            return;
        }
        renderActivities(Array.isArray(data) ? data : []);
    } catch (error) {
        if (requestId !== activityRequestId) {
            return;
        }
        showActivityState(`活动记录加载失败：${error.message}`, 'error');
    }
}

function openDetailDialog(task) {
    detailTaskId = task.id;
    els.detailTitle.textContent = task.title;
    renderDetailMeta(task);
    showActivityState('正在加载活动记录…');
    els.detailDialog.showModal();
    loadActivities(task.id);
}

/* --------------------------------------------------------------- import/export */

function exportFilenameFallback() {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, '0');
    const date = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`;
    const time = `${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
    return `taskboard-export-${date}-${time}.json`;
}

function filenameFromDisposition(header) {
    if (typeof header !== 'string') {
        return null;
    }
    const match = header.match(/filename="?([^";]+)"?/i);
    return match ? match[1].trim() : null;
}

async function exportTasks() {
    if (els.exportButton.disabled) {
        return;
    }
    els.exportButton.disabled = true;
    els.exportButton.setAttribute('aria-busy', 'true');
    setHint('正在导出…');
    try {
        const { blob, disposition } = await request(`${API_BASE}/export`, { blob: true });
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = filenameFromDisposition(disposition) || exportFilenameFallback();
        document.body.appendChild(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
        setHint(`已导出 ${link.download}。`);
    } catch (error) {
        setHint(`导出失败：${error.message}`, 'error');
    } finally {
        els.exportButton.disabled = false;
        els.exportButton.removeAttribute('aria-busy');
    }
}

function openImportDialog(fileName, rows) {
    pendingImport = { fileName, rows };
    els.importFileName.textContent = `文件：${fileName} · ${rows.length} 条任务`;
    els.importForm.reset();
    els.importWarning.hidden = true;
    setImportHint('');
    els.importDialog.showModal();
}

async function handleImportFileChosen() {
    const file = els.importFile.files && els.importFile.files[0];
    els.importFile.value = '';
    if (!file) {
        return;
    }
    let parsed;
    try {
        parsed = JSON.parse(await file.text());
    } catch (error) {
        setHint('导入失败：文件不是有效的 JSON。', 'error');
        return;
    }
    const rows = Array.isArray(parsed)
        ? parsed
        : (parsed && Array.isArray(parsed.tasks) ? parsed.tasks : null);
    if (!rows) {
        setHint('导入失败：文件缺少 tasks 数组，不是本看板的导出格式。', 'error');
        return;
    }
    if (rows.length > IMPORT_MAX_ROWS) {
        setHint(`导入失败：单次最多 ${IMPORT_MAX_ROWS} 条，文件中有 ${rows.length} 条。`, 'error');
        return;
    }
    openImportDialog(file.name, rows);
}

async function submitImport(event) {
    event.preventDefault();
    if (!pendingImport || els.importSubmit.disabled) {
        return;
    }
    const mode = els.importForm.elements['import-mode'].value;
    if (mode !== 'merge' && mode !== 'replace') {
        return;
    }
    if (mode === 'replace' && !window.confirm('替换模式将清空现有全部任务且不可恢复，确定继续？')) {
        return;
    }

    const body = { mode, tasks: pendingImport.rows };
    if (mode === 'replace') {
        body.confirm = 'replace';
    }

    els.importSubmit.disabled = true;
    els.importSubmit.setAttribute('aria-busy', 'true');
    setImportHint('正在导入…');
    try {
        const data = await request(`${API_BASE}/import`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
            body: JSON.stringify(body),
        });
        els.importDialog.close();
        pendingImport = null;
        const imported = data && typeof data.imported === 'number' ? data.imported : 0;
        const skipped = data && typeof data.skipped === 'number' ? data.skipped : 0;
        const total = data && typeof data.total === 'number' ? data.total : null;
        let message = `导入完成：导入 ${imported} 条、跳过 ${skipped} 条`;
        if (total !== null) {
            message += `，库内现共 ${total} 条任务`;
        }
        message += '。';
        if (skipped > 0) {
            message += '部分行未通过校验被跳过。';
        }
        setHint(message, skipped > 0 ? 'error' : undefined);
        await loadTasks();
    } catch (error) {
        setImportHint(`导入失败：${error.message}`, 'error');
    } finally {
        els.importSubmit.disabled = false;
        els.importSubmit.removeAttribute('aria-busy');
    }
}

/* -------------------------------------------------------------------- seed */

// Sample data so an empty instance still shows what the board can do. Only used when the server
// reports zero tasks: it never overwrites real work, and memory storage means it is gone on restart.
const SEED_TASKS = [
    { title: '梳理客户演示脚本', priority: 'URGENT', assignee: '鲁浩然', dueInDays: 1, tags: ['演示', '优先级高'] },
    { title: '补齐接口错误码文档', priority: 'HIGH', assignee: '后端', dueInDays: 3, tags: ['文档'] },
    { title: '看板拖拽在触屏上的降级方案', priority: 'NORMAL', assignee: '前端', dueInDays: 7, tags: ['前端', '体验'] },
    { title: '核对老数据字段映射', priority: 'LOW', assignee: '', dueInDays: -2, tags: ['数据'] },
    { title: '确认演示环境网络可用', priority: 'HIGH', assignee: '运维', dueInDays: 0, tags: ['环境'], status: 'DOING' },
    { title: '整理上一轮审查发现清单', priority: 'NORMAL', assignee: '队长', dueInDays: -5, tags: ['复盘'], status: 'DOING' },
    { title: '申请客户演示排期', priority: 'URGENT', assignee: '鲁浩然', dueInDays: 2, tags: ['沟通'], status: 'BLOCKED' },
    { title: '主流程冒烟通过', priority: 'HIGH', assignee: '队长', dueInDays: -1, tags: ['验收'], status: 'DONE' }
];

// Legal walks from TODO to each seeded status, using only transitions the server allows.
const SEED_PATHS = {
    TODO: [],
    DOING: ['DOING'],
    BLOCKED: ['BLOCKED'],
    DONE: ['DOING', 'DONE'],
};

function isoDate(offsetDays) {
    const date = new Date();
    date.setDate(date.getDate() + offsetDays);
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
}

async function seedIfEmpty() {
    // Ask the server for the unfiltered truth. `tasks` holds whatever the current filter
    // returned, so an empty filtered view on a non-empty board would otherwise seed 8 sample
    // tasks straight into real data.
    let existing;
    try {
        existing = await request(API_BASE, { headers: { Accept: 'application/json' } });
    } catch (error) {
        return;
    }
    if (Array.isArray(existing) && existing.length > 0) {
        return;
    }
    setHint('正在准备示例任务…');
    for (const seed of SEED_TASKS) {
        const payload = {
            title: seed.title,
            priority: seed.priority,
            assignee: seed.assignee,
            dueDate: isoDate(seed.dueInDays),
            tags: seed.tags,
        };
        try {
            const created = await request(API_BASE, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                body: JSON.stringify(payload),
            });
            // POST always starts a task at TODO, so the seed walks it to its target status
            // through the same endpoint a user would use. No back door, no special seed path.
            for (const step of SEED_PATHS[seed.status || 'TODO'] || []) {
                await request(`${API_BASE}/${encodeURIComponent(created.id)}/status`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
                    body: JSON.stringify({ status: step }),
                });
            }
        } catch (error) {
            setHint(`示例数据准备失败：${error.message}`, 'error');
            return;
        }
    }
    setHint('已准备示例任务，可直接演示。');
    await loadTasks();
}

/* -------------------------------------------------------------------- events */

els.quickForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const title = els.quickInput.value.trim();
    if (title.length === 0) {
        setHint('先写下任务标题。', 'error');
        els.quickInput.focus();
        return;
    }
    if (title.length > 80) {
        setHint('标题不能超过 80 个字符。', 'error');
        els.quickInput.focus();
        return;
    }
    createTask(title);
});

els.openDialog.addEventListener('click', openDialogForCreate);
els.formCancel.addEventListener('click', () => els.dialog.close());
els.form.addEventListener('submit', submitForm);

els.board.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || button.disabled) {
        return;
    }
    const card = findCard(button);
    if (!card) {
        return;
    }
    const task = tasks.find((item) => String(item.id) === card.dataset.id);
    if (!task) {
        return;
    }

    const action = button.dataset.action;
    if (action === 'advance') {
        advanceTask(card, task);
    } else if (action === 'delete') {
        deleteTask(card, task);
    } else if (action === 'edit') {
        openDialogForEdit(task);
    } else if (action === 'detail') {
        openDetailDialog(task);
    }
});

els.board.addEventListener('change', (event) => {
    const input = event.target.closest('input[data-action="select"]');
    if (!input) {
        return;
    }
    const card = input.closest('.card');
    if (!card) {
        return;
    }
    const id = Number(card.dataset.id);
    if (!Number.isFinite(id)) {
        return;
    }
    if (input.checked) {
        selectedIds.add(id);
    } else {
        selectedIds.delete(id);
    }
    updateBatchBar();
});

els.batchSelectAll.addEventListener('click', () => {
    for (const task of tasks) {
        selectedIds.add(task.id);
    }
    document.querySelectorAll('.card').forEach((card) => {
        const check = card.querySelector('.card__check');
        if (check) {
            check.checked = selectedIds.has(Number(card.dataset.id));
        }
    });
    updateBatchBar();
});

els.batchClear.addEventListener('click', () => {
    selectedIds.clear();
    document.querySelectorAll('.card__check').forEach((check) => {
        check.checked = false;
    });
    updateBatchBar();
});

els.batchApply.addEventListener('click', runBatchStatus);
els.batchDelete.addEventListener('click', runBatchDelete);
els.batchResultsClose.addEventListener('click', () => {
    els.batchResults.hidden = true;
});

els.exportButton.addEventListener('click', exportTasks);
els.importButton.addEventListener('click', () => els.importFile.click());
els.importFile.addEventListener('change', () => handleImportFileChosen());
els.importForm.addEventListener('change', (event) => {
    if (event.target && event.target.name === 'import-mode') {
        els.importWarning.hidden = event.target.value !== 'replace';
    }
});
els.importForm.addEventListener('submit', submitImport);
els.importCancel.addEventListener('click', () => {
    pendingImport = null;
    els.importDialog.close();
});
els.detailClose.addEventListener('click', () => els.detailDialog.close());
els.detailDialog.addEventListener('close', () => {
    detailTaskId = null;
    // Invalidate in-flight activity loads so a late response never lands in a reopened dialog
    // for a different task.
    activityRequestId += 1;
});
els.activityRetry.addEventListener('click', () => {
    if (detailTaskId !== null) {
        loadActivities(detailTaskId);
    }
});

let filterTimer = null;
function scheduleFilterReload() {
    window.clearTimeout(filterTimer);
    filterTimer = window.setTimeout(loadTasks, 220);
}

els.filterQ.addEventListener('input', () => {
    filters.q = els.filterQ.value.trim();
    scheduleFilterReload();
});
els.filterStatus.addEventListener('change', () => {
    filters.status = els.filterStatus.value;
    loadTasks();
});
els.filterPriority.addEventListener('change', () => {
    filters.priority = els.filterPriority.value;
    loadTasks();
});
els.filterOverdue.addEventListener('change', () => {
    filters.overdue = els.filterOverdue.checked;
    loadTasks();
});
els.sortKey.addEventListener('change', () => {
    filters.sort = els.sortKey.value;
    loadTasks();
});
els.sortOrder.addEventListener('click', () => {
    filters.order = filters.order === 'asc' ? 'desc' : 'asc';
    syncFilterControls();
    loadTasks();
});
els.filterReset.addEventListener('click', () => {
    filters = { q: '', status: '', priority: '', overdue: false, sort: 'createdAt', order: 'desc' };
    syncFilterControls();
    loadTasks();
});
els.retry.addEventListener('click', () => loadTasks());

window.addEventListener('hashchange', () => {
    readFiltersFromHash();
    loadTasks();
});

readFiltersFromHash();

// The seed only runs against a genuinely empty server: seedIfEmpty re-checks the unfiltered
// count itself, so a filtered-empty view never triggers it.
loadTasks().then(() => {
    if (tasks.length === 0) {
        seedIfEmpty();
    }
});
