'use strict';

const API_BASE = '/api/tasks';
const REQUEST_TIMEOUT_MS = 8000;
const STATUS_FLOW = ['TODO', 'DOING', 'DONE'];
const STATUS_LABELS = { TODO: '待办', DOING: '进行中', DONE: '已完成' };

let latestLoadId = 0;

const els = {
    form: document.getElementById('task-form'),
    input: document.getElementById('task-title'),
    hint: document.getElementById('composer-hint'),
    submit: document.querySelector('#task-form button[type="submit"]'),
    loading: document.getElementById('state-loading'),
    empty: document.getElementById('state-empty'),
    error: document.getElementById('state-error'),
    errorMessage: document.getElementById('state-error-message'),
    retry: document.getElementById('retry-button'),
    list: document.getElementById('task-list'),
    template: document.getElementById('task-item-template'),
};

function setHint(text, tone) {
    els.hint.textContent = text;
    if (tone) {
        els.hint.dataset.tone = tone;
    } else {
        delete els.hint.dataset.tone;
    }
}

function showState(name) {
    els.loading.hidden = name !== 'loading';
    els.empty.hidden = name !== 'empty';
    els.error.hidden = name !== 'error';
    els.list.hidden = name !== 'list';
}

function nextStatus(current) {
    const index = STATUS_FLOW.indexOf(current);
    return STATUS_FLOW[Math.min(index + 1, STATUS_FLOW.length - 1)];
}

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
        throw new Error(message);
    }
    return payload;
}

function renderTasks(tasks) {
    els.list.replaceChildren();

    if (tasks.length === 0) {
        showState('empty');
        return;
    }

    const fragment = document.createDocumentFragment();
    for (const task of tasks) {
        fragment.appendChild(renderTask(task));
    }
    els.list.appendChild(fragment);
    showState('list');
}

function renderTask(task) {
    const node = els.template.content.firstElementChild.cloneNode(true);

    node.dataset.id = String(task.id);
    node.querySelector('.task__title').textContent = task.title;
    node.classList.toggle('task--done', task.status === STATUS_FLOW[STATUS_FLOW.length - 1]);

    const badge = node.querySelector('.badge');
    badge.textContent = STATUS_LABELS[task.status] || task.status;
    badge.dataset.status = task.status;

    const time = node.querySelector('.task__time');
    time.dateTime = task.createdAt;
    time.textContent = formatTime(task.createdAt);

    const advanceButton = node.querySelector('[data-action="advance"]');
    const isLast = task.status === STATUS_FLOW[STATUS_FLOW.length - 1];
    advanceButton.disabled = isLast;
    advanceButton.title = isLast ? '已完成，无需推进' : `推进到 ${STATUS_LABELS[nextStatus(task.status)]}`;

    return node;
}

function formatTime(iso) {
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    return date.toLocaleString('zh-CN', { hour12: false });
}

async function loadTasks() {
    const requestId = ++latestLoadId;
    showState('loading');
    try {
        const tasks = await request(API_BASE, { headers: { Accept: 'application/json' } });
        if (requestId !== latestLoadId) {
            return;
        }
        renderTasks(Array.isArray(tasks) ? tasks : []);
    } catch (error) {
        if (requestId !== latestLoadId) {
            return;
        }
        els.errorMessage.textContent = `加载任务失败：${error.message}`;
        showState('error');
    }
}

async function createTask(title) {
    els.submit.disabled = true;
    els.submit.setAttribute('aria-busy', 'true');
    setHint('正在提交…');
    try {
        await request(API_BASE, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Accept: 'application/json',
            },
            body: JSON.stringify({ title }),
        });
        els.input.value = '';
        await loadTasks();
        setHint('已添加。');
    } catch (error) {
        setHint(`添加失败：${error.message}`, 'error');
    } finally {
        els.submit.disabled = false;
        els.submit.removeAttribute('aria-busy');
    }
}

async function advanceTask(item) {
    const badge = item.querySelector('.badge');
    const id = item.dataset.id;
    const actionButton = item.querySelector('[data-action="advance"]');

    // A double click lands here twice before the first await resolves; the flag is set
    // synchronously, so the second invocation bails out before sending another PATCH.
    if (item.dataset.busy === '1') {
        return;
    }
    item.dataset.busy = '1';

    const target = nextStatus(badge.dataset.status);
    const buttons = item.querySelectorAll('button');
    buttons.forEach((button) => {
        button.disabled = true;
        button.setAttribute('aria-busy', 'true');
    });

    try {
        await request(`${API_BASE}/${encodeURIComponent(id)}/status`, {
            method: 'PATCH',
            headers: {
                'Content-Type': 'application/json',
                Accept: 'application/json',
            },
            body: JSON.stringify({ status: target }),
        });
        await loadTasks();
        setHint('');
    } catch (error) {
        setHint(`更新状态失败：${error.message}`, 'error');
        delete item.dataset.busy;
        buttons.forEach((button) => {
            button.disabled = false;
            button.removeAttribute('aria-busy');
        });
        // Re-apply the DONE guard: a row that was already complete must stay non-clickable.
        if (badge.dataset.status === STATUS_FLOW[STATUS_FLOW.length - 1]) {
            actionButton.disabled = true;
        }
    }
}

async function deleteTask(item) {
    const id = item.dataset.id;
    if (item.dataset.busy === '1') {
        return;
    }
    if (!window.confirm('确定删除这条任务？')) {
        return;
    }
    item.dataset.busy = '1';

    item.querySelectorAll('button').forEach((button) => {
        button.disabled = true;
        button.setAttribute('aria-busy', 'true');
    });

    try {
        await request(`${API_BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' });
        await loadTasks();
        setHint('');
    } catch (error) {
        setHint(`删除失败：${error.message}`, 'error');
        delete item.dataset.busy;
        item.querySelectorAll('button').forEach((button) => {
            button.disabled = false;
            button.removeAttribute('aria-busy');
        });
        // Same guard as advanceTask: a row that is already DONE must keep "推进" disabled,
        // otherwise a failed delete hands the user a button that sends a pointless PATCH.
        const badge = item.querySelector('.badge');
        if (badge && badge.dataset.status === STATUS_FLOW[STATUS_FLOW.length - 1]) {
            item.querySelector('[data-action="advance"]').disabled = true;
        }
    }
}

els.form.addEventListener('submit', (event) => {
    event.preventDefault();
    const title = els.input.value.trim();
    if (title.length === 0) {
        setHint('先写下任务内容。', 'error');
        els.input.focus();
        return;
    }
    if (title.length > 80) {
        setHint('任务标题不能超过 80 个字符。', 'error');
        els.input.focus();
        return;
    }
    createTask(title);
});

els.retry.addEventListener('click', () => {
    loadTasks();
});

els.list.addEventListener('click', (event) => {
    const button = event.target.closest('button[data-action]');
    if (!button || button.disabled) {
        return;
    }

    const item = button.closest('.task');
    if (!item || !els.list.contains(item)) {
        return;
    }

    if (button.dataset.action === 'advance') {
        advanceTask(item);
    } else if (button.dataset.action === 'delete') {
        deleteTask(item);
    }
});

loadTasks();
