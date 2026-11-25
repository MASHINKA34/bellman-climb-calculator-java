'use strict';

/* ===========================================================================
   Админка ключей: первый вход (придумать пароль), вход, список ключей.

   Все данные - по адресам api/admin/*. Пароль и вход проверяет сервер, здесь только формы.
   Время показывается московское.
   =========================================================================== */

const TAB_NAMES = {flow: 'Поток', placement: 'Размещение', transport: 'Транспортная'};

const EVENT_NAMES = {
    created: 'создан, вкладки',
    bound: 'введён на устройстве',
    entered_again: 'введён снова на том же устройстве',
    refused_busy: 'отказ: ключ занят, пытались ввести на',
    refused_disabled: 'отказ: ключ отключён, пытались ввести на',
    disabled: 'отключён',
    enabled: 'включён',
    unbound: 'отвязан от устройства',
    tabs: 'вкладки изменены',
    label: 'пометка изменена'
};

const state = {
    keys: [],
    search: '',
    // ключ, который сейчас правится, и ключ с открытой историей
    editing: null,
    history: null,
    events: [],
    busy: false
};

/* ============================ логи ============================ */

// время с миллисекундами, уровень, модуль, сообщение; секретов в контексте не бывает
function write(level, message, context) {
    const line = new Date().toISOString() + ' ' + level.toUpperCase() + ' [admin] ' + message
        + (context ? ' ' + JSON.stringify(context) : '');
    console[level](line);
}

const log = {
    info: (message, context) => write('info', message, context),
    warn: (message, context) => write('warn', message, context),
    error: (message, error, context) => write('error', message, Object.assign({}, context, {
        name: error && error.name, message: error && error.message, stack: error && error.stack
    }))
};

window.addEventListener('error', event => log.error('uncaught error', event.error || {message: event.message}));
window.addEventListener('unhandledrejection', event => log.error('unhandled rejection', event.reason));

/* ============================ запросы ============================ */

class ApiError extends Error {
    constructor(status, detail) {
        super(detail);
        this.name = 'ApiError';
        this.status = status;
    }
}

async function api(method, path, body) {
    const startedAt = performance.now();
    const options = {method: method, headers: {'X-Admin': '1'}, cache: 'no-store'};
    if (body !== undefined) {
        options.headers['Content-Type'] = 'application/json';
        options.body = JSON.stringify(body);
    }
    let response;
    try {
        response = await fetch('/api/admin/' + path, options);
    } catch (error) {
        log.error('request failed', error, {method: method, path: path});
        throw new ApiError(0, 'Нет связи с сервером.');
    }
    const ms = Math.round(performance.now() - startedAt);
    let answer = null;
    try {
        answer = await response.json();
    } catch (error) {
        log.error('answer is not JSON', error, {method: method, path: path, status: response.status});
    }
    if (!response.ok) {
        log.warn('request refused', {method: method, path: path, status: response.status, ms: ms,
            detail: answer && answer.detail});
        if (response.status === 401) {
            showLogin();
        }
        throw new ApiError(response.status, (answer && answer.detail) || 'Сервер ответил ошибкой ' + response.status + '.');
    }
    log.info('request done', {method: method, path: path, status: response.status, ms: ms});
    return answer;
}

/* ============================ мелочи ============================ */

function $(selector) {
    return document.querySelector(selector);
}

function escapeHtml(text) {
    return String(text).replace(/[&<>"']/g, symbol => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', '\'': '&#39;'
    })[symbol]);
}

const TIME = new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'Europe/Moscow', day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit'
});

function time(ms) {
    return TIME.format(new Date(ms)).replace(',', '');
}

function showMessage(selector, text) {
    const box = $(selector);
    box.textContent = text || '';
    box.hidden = !text;
}

async function copy(text, button) {
    const before = button.textContent;
    try {
        await navigator.clipboard.writeText(text);
        button.textContent = 'Скопировано';
    } catch (error) {
        // без разрешения на буфер обмена ключ всё равно виден целиком - его можно выделить
        log.warn('clipboard is unavailable', {message: error && error.message});
        button.textContent = 'Выделите и скопируйте';
    }
    setTimeout(() => {
        button.textContent = before;
    }, 1800);
}

function checkedTabs(root) {
    return Array.from(root.querySelectorAll('.tabs-field input:checked')).map(input => input.value);
}

/* ============================ что показывать ============================ */

function show(part) {
    ['setup', 'login', 'keys'].forEach(id => {
        $('#' + id).hidden = id !== part;
    });
    $('#btn-logout').hidden = part !== 'keys';
}

function showLogin() {
    show('login');
    $('#login-password').value = '';
    $('#login-password').focus();
}

// одноразовый код первого входа - в ссылке после решётки: на сервер и в его журнал он сам не уходит
function setupCode() {
    const match = /(?:^#|&)setup=([A-Za-z0-9_-]+)/.exec(window.location.hash);
    return match ? match[1] : '';
}

async function start() {
    try {
        const answer = await api('GET', 'state');
        $('#sub').textContent = 'Многополюсный поток ' + answer.version;
        if (answer.setup) {
            show('setup');
            if (!setupCode()) {
                showMessage('#setup-message', 'Пароль ещё не задан. Откройте одноразовую ссылку целиком - '
                    + 'с ней страница знает, что это вы.');
            }
        } else if (answer.authenticated) {
            show('keys');
            await load();
        } else {
            showLogin();
        }
        log.info('page ready', {setup: answer.setup, authenticated: answer.authenticated});
    } catch (error) {
        showMessage('#page-message', error.message);
    }
}

/* ============================ вход ============================ */

async function submitForm(form, messageSelector, action) {
    if (state.busy) {
        return;
    }
    state.busy = true;
    const button = form.querySelector('button[type="submit"]');
    button.disabled = true;
    showMessage(messageSelector, '');
    try {
        await action();
    } catch (error) {
        showMessage(messageSelector, error.message);
    } finally {
        state.busy = false;
        button.disabled = false;
    }
}

$('#setup').addEventListener('submit', event => {
    event.preventDefault();
    submitForm(event.target, '#setup-message', async () => {
        const password = $('#setup-password').value;
        if (password !== $('#setup-repeat').value) {
            throw new ApiError(0, 'Пароли не совпадают.');
        }
        await api('POST', 'setup', {code: setupCode(), password: password});
        // одноразовая ссылка больше не нужна - убираем её из адреса
        window.history.replaceState(null, '', window.location.pathname);
        event.target.reset();
        show('keys');
        await load();
    });
});

$('#login').addEventListener('submit', event => {
    event.preventDefault();
    submitForm(event.target, '#login-message', async () => {
        await api('POST', 'login', {password: $('#login-password').value});
        event.target.reset();
        show('keys');
        await load();
    });
});

$('#btn-logout').addEventListener('click', async () => {
    try {
        await api('POST', 'logout');
    } catch (error) {
        showMessage('#page-message', error.message);
        return;
    }
    state.keys = [];
    render();
    showLogin();
});

/* ============================ ключи ============================ */

async function load() {
    const answer = await api('GET', 'keys');
    state.keys = answer.keys;
    if (state.history !== null) {
        // открытая история показывает и только что сделанное действие
        const exists = state.keys.some(key => key.id === state.history);
        state.events = exists ? (await api('GET', 'keys/' + state.history + '/events')).events : [];
        state.history = exists ? state.history : null;
    }
    showMessage('#page-message', '');
    render();
}

function matches(key) {
    const query = state.search.trim().toLowerCase();
    if (!query) {
        return true;
    }
    const device = key.device ? key.device.name + ' ' + key.device.os : '';
    const text = (key.label + ' ' + key.key + ' ' + key.key.replace(/-/g, '') + ' ' + device).toLowerCase();
    return text.indexOf(query) !== -1;
}

function render() {
    const shown = state.keys.filter(matches);
    const active = state.keys.filter(key => key.active).length;
    $('#count').textContent = state.keys.length
        ? '- ' + state.keys.length + ', действуют ' + active : '';
    $('#list').innerHTML = shown.length ? shown.map(keyCard).join('')
        : '<p class="empty">' + (state.keys.length ? 'Ничего не найдено.' : 'Ключей пока нет - создайте первый.') + '</p>';
}

function deviceLine(key) {
    const device = key.device;
    if (!device) {
        return 'Свободен: ещё не введён ни на одном устройстве';
    }
    const kind = device.kind === 'android' ? 'Телефон' : 'Браузер';
    const parts = [device.name, device.os].filter(Boolean);
    if (device.kind === 'android' && device.appVersion) {
        parts.push('приложение ' + device.appVersion);
    }
    return kind + ': ' + parts.join(', ') + '. Последний вход ' + time(device.lastSeen);
}

function keyCard(key) {
    const badges = Object.keys(TAB_NAMES).map(id =>
        '<span class="badge' + (key.tabs.indexOf(id) !== -1 ? ' on">' : '" hidden>') + TAB_NAMES[id] + '</span>').join('');
    const status = key.active ? '' : '<span class="badge off">отключён</span>';
    return '<article class="key-card' + (key.active ? '' : ' off') + '" data-id="' + key.id + '">'
        + '<div class="key-top"><span class="key-label">' + escapeHtml(key.label || 'без пометки') + '</span>'
        + '<span class="code">' + escapeHtml(key.key) + '</span>' + status + '</div>'
        + '<p class="key-line">' + badges + '</p>'
        + '<p class="key-line">' + escapeHtml(deviceLine(key)) + '</p>'
        + '<p class="key-line">Создан ' + time(key.createdAt) + '</p>'
        + '<div class="key-actions">'
        + action('copy', 'Скопировать')
        + action('edit', state.editing === key.id ? 'Не менять' : 'Изменить')
        + (key.active ? action('disable', 'Отключить', true) : action('enable', 'Включить'))
        + (key.device ? action('unbind', 'Отвязать устройство') : '')
        + action('history', state.history === key.id ? 'Скрыть историю' : 'История')
        + action('delete', 'Удалить', true)
        + '</div>'
        + (state.editing === key.id ? editForm(key) : '')
        + (state.history === key.id ? historyBlock() : '')
        + '</article>';
}

function action(name, title, danger) {
    return '<button type="button" class="btn-ghost btn-small' + (danger ? ' btn-danger' : '') + '" data-action="'
        + name + '">' + title + '</button>';
}

function editForm(key) {
    return '<form class="edit-form" data-edit="' + key.id + '">'
        + '<label class="field">Кому<input type="text" maxlength="100" autocomplete="off" value="'
        + escapeHtml(key.label) + '"></label>'
        + '<fieldset class="tabs-field"><legend>Доступ к вкладкам</legend>'
        + Object.keys(TAB_NAMES).map(id => '<label><input type="checkbox" value="' + id + '"'
            + (key.tabs.indexOf(id) !== -1 ? ' checked' : '') + '> ' + TAB_NAMES[id] + '</label>').join('')
        + '</fieldset>'
        + '<div class="message error" id="edit-message" role="alert" hidden></div>'
        + '<button type="submit" class="btn-primary">Сохранить</button></form>';
}

function historyBlock() {
    if (!state.events.length) {
        return '<div class="history">Событий нет.</div>';
    }
    return '<div class="history"><ul>' + state.events.map(event => {
        let detail = event.detail;
        if (event.type === 'created' || event.type === 'tabs') {
            detail = detail.split(', ').map(id => TAB_NAMES[id] || id).join(', ');
        }
        return '<li><time>' + time(event.at) + '</time> ' + escapeHtml(EVENT_NAMES[event.type] || event.type)
            + (detail ? ': ' + escapeHtml(detail) : '') + '</li>';
    }).join('') + '</ul></div>';
}

$('#create').addEventListener('submit', event => {
    event.preventDefault();
    submitForm(event.target, '#create-message', async () => {
        const created = await api('POST', 'keys', {label: $('#create-label').value, tabs: checkedTabs(event.target)});
        $('#create-label').value = '';
        const box = $('#new-key');
        box.hidden = false;
        box.innerHTML = 'Ключ создан' + (created.label ? ' - ' + escapeHtml(created.label) : '')
            + '<span class="code">' + escapeHtml(created.key) + '</span>'
            + '<button type="button" class="btn-ghost btn-small" data-copy="' + escapeHtml(created.key) + '">Скопировать</button>';
        await load();
    });
});

$('#new-key').addEventListener('click', event => {
    const button = event.target.closest('[data-copy]');
    if (button) {
        copy(button.dataset.copy, button);
    }
});

$('#search').addEventListener('input', event => {
    state.search = event.target.value;
    render();
});

$('#btn-refresh').addEventListener('click', () => {
    load().catch(error => showMessage('#page-message', error.message));
});

$('#list').addEventListener('click', event => {
    const button = event.target.closest('[data-action]');
    if (button) {
        const id = Number(button.closest('[data-id]').dataset.id);
        onAction(state.keys.find(key => key.id === id), button.dataset.action, button)
            .catch(error => showMessage('#page-message', error.message));
    }
});

$('#list').addEventListener('submit', event => {
    event.preventDefault();
    const id = Number(event.target.dataset.edit);
    submitForm(event.target, '#edit-message', async () => {
        await api('PUT', 'keys/' + id, {
            label: event.target.querySelector('input[type="text"]').value,
            tabs: checkedTabs(event.target)
        });
        state.editing = null;
        await load();
    });
});

async function onAction(key, name, button) {
    const title = key.label ? '«' + key.label + '» ' + key.key : key.key;
    if (name === 'copy') {
        await copy(key.key, button);
        return;
    }
    if (name === 'edit') {
        state.editing = state.editing === key.id ? null : key.id;
        render();
        return;
    }
    if (name === 'history') {
        if (state.history === key.id) {
            state.history = null;
        } else {
            state.events = (await api('GET', 'keys/' + key.id + '/events')).events;
            state.history = key.id;
        }
        render();
        return;
    }
    if (name === 'disable' && !window.confirm('Отключить ключ ' + title + '?\n'
        + 'С интернетом человека выкинет в течение минуты, без интернета - через 3 дня.')) {
        return;
    }
    if (name === 'unbind' && !window.confirm('Отвязать устройство от ключа ' + title + '?\n'
        + 'Это устройство выкинет, а ключ можно будет ввести на другом.')) {
        return;
    }
    if (name === 'delete') {
        if (!window.confirm('Удалить ключ ' + title + ' вместе с историей?\nВернуть его будет нельзя.')) {
            return;
        }
        await api('DELETE', 'keys/' + key.id);
    } else {
        await api('POST', 'keys/' + key.id + '/' + name);
    }
    await load();
}

start();
