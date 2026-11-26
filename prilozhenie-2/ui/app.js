'use strict';

/* ===========================================================================
   Многополюсный поток - клиентская часть.

   Весь расчёт выполняет ядро на Java (модуль core). В приложении его зовёт
   мост android-bridge.js, на сайте - сервер по адресам api/example и api/solve.
   Здесь только ввод матрицы, таблицы и рисунки.

   Код рассчитан и на старый WebView из Android 10, поэтому без ?. и ??.
   =========================================================================== */

const MIN_SIZE = 3;
const MAX_SIZE = 10;
const DEFAULT_SIZE = 7;
const MAX_CAPACITY = 1000000;

// обозначения таблиц шага как на доске: X с нижним индексом st. Заголовки шага пишутся
// заглавными - класс nota оставляет обозначение как есть, иначе вышло бы «XST»
const XST = '<span class="nota">X<sub>st</sub></span>';
const R_XST = '<span class="nota">R - X<sub>st</sub></span>';
const R_NOTA = '<span class="nota">R</span>';
const STORAGE_KEY = 'multiflow.input.v1';

/* ============================ логи ============================ */

const LOG_LEVELS = {debug: 10, info: 20, warn: 30, error: 40};
const LOG_LEVEL = resolveLogLevel();
const log = createLogger('ui');

// в приложении уровень задаёт Android: adb shell setprop log.tag.MultiFlow DEBUG
// на сайте: ?log=debug в адресе или LOG_LEVEL=debug в localStorage
function resolveLogLevel() {
    try {
        if (typeof MultiFlowBridge !== 'undefined') {
            return normalizeLevel(MultiFlowBridge.logLevel());
        }
    } catch (error) {
        console.warn('log level from bridge is unavailable, using info: ' + describeError(error));
    }
    let requested = null;
    try {
        requested = new URLSearchParams(window.location.search).get('log')
            || window.localStorage.getItem('LOG_LEVEL');
    } catch (error) {
        console.warn('log level from URL/localStorage is unavailable, using info: ' + describeError(error));
    }
    return normalizeLevel(requested);
}

function normalizeLevel(value) {
    const level = String(value || '').toLowerCase();
    if (level === 'warning') {
        return 'warn';
    }
    return LOG_LEVELS[level] ? level : 'info';
}

function createLogger(module) {
    function write(level, message, context) {
        if (LOG_LEVELS[level] < LOG_LEVELS[LOG_LEVEL]) {
            return;
        }
        let line = timestamp() + ' ' + level.toUpperCase() + ' [' + module + '] ' + message;
        if (context) {
            line += ' ' + formatContext(context);
        }
        const sink = console[level] || console.log;
        sink.call(console, line);
        if (level === 'warn' || level === 'error') {
            reportToServer(level, module, message, context ? formatContext(context) : '');
        }
    }

    return {
        debug: function (message, context) {
            write('debug', message, context);
        },
        info: function (message, context) {
            write('info', message, context);
        },
        warn: function (message, context) {
            write('warn', message, context);
        },
        error: function (message, context) {
            write('error', message, context);
        }
    };
}

// на сайте предупреждения и ошибки страницы уходят в журнал сервера (api/client-log):
// консоль Safari на чужом айфоне не посмотреть. В приложении это не нужно - там logcat.
// Не больше 20 записей за открытие страницы; сбой отправки только в консоль, без повтора,
// иначе ошибка отправки сама порождала бы новые отправки
const REPORT_LIMIT = 20;
let reportsSent = 0;

function reportToServer(level, module, message, contextText) {
    if (typeof MultiFlowBridge !== 'undefined' || reportsSent >= REPORT_LIMIT || !window.fetch) {
        return;
    }
    reportsSent += 1;
    const body = JSON.stringify({
        level: level,
        module: module,
        message: message,
        context: contextText + (reportsSent === REPORT_LIMIT ? ' (limit reached, next reports dropped)' : ''),
        page: window.location.pathname + window.location.search
    });
    try {
        fetch('api/client-log', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: body,
            keepalive: true
        }).catch(function (error) {
            console.warn('page log was not delivered to the server: ' + describeError(error));
        });
    } catch (error) {
        console.warn('page log was not delivered to the server: ' + describeError(error));
    }
}

function timestamp() {
    const now = new Date();
    return pad(now.getHours(), 2) + ':' + pad(now.getMinutes(), 2) + ':' + pad(now.getSeconds(), 2)
        + '.' + pad(now.getMilliseconds(), 3);
}

function pad(value, width) {
    return String(value).padStart(width, '0');
}

// контекст строкой key=value; длинные значения обрезаются с указанием полного размера
function formatContext(context) {
    return Object.keys(context).map(function (key) {
        let value = context[key];
        if (value instanceof Error) {
            value = describeError(value);
        } else if (value !== null && typeof value === 'object') {
            value = JSON.stringify(value);
        }
        value = String(value);
        if (value.length > 2048) {
            value = value.slice(0, 2048) + '…(' + value.length + ' chars)';
        }
        return key + '=' + value;
    }).join(' ');
}

// Error через JSON.stringify превращается в {}, поэтому поля переписываем явно
function describeError(error) {
    if (error === null || typeof error !== 'object') {
        return String(error);
    }
    const parts = [(error.name || 'Error') + ': ' + (error.message || '')];
    if (error.code) {
        parts.push('code=' + error.code);
    }
    if (error.stack) {
        parts.push('stack=' + error.stack);
    }
    if (error.cause) {
        parts.push('cause=' + describeError(error.cause));
    }
    return parts.join(' ');
}

window.addEventListener('error', function (event) {
    log.error('uncaught error', {
        message: event.message,
        source: event.filename,
        line: event.lineno,
        column: event.colno,
        error: event.error ? describeError(event.error) : 'n/a'
    });
});

window.addEventListener('unhandledrejection', function (event) {
    log.error('unhandled promise rejection', {reason: describeError(event.reason)});
});

/* ============================ мелкие утилиты ============================ */

function $(selector, root) {
    return (root || document).querySelector(selector);
}

function escapeHtml(value) {
    return String(value)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function setText(vertices) {
    return '{' + vertices.join(', ') + '}';
}

// множество вершин, которое не разрывается переносом строки посередине
function setSpan(vertices) {
    return '<span class="set">' + setText(vertices) + '</span>';
}

function cellName(i, j) {
    return 'c(' + (i + 1) + ',' + (j + 1) + ')';
}

function plural(count, one, few, many) {
    const mod10 = count % 10;
    const mod100 = count % 100;
    if (mod10 === 1 && mod100 !== 11) {
        return one;
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
        return few;
    }
    return many;
}

function fmtMs(ms) {
    return ms < 1 ? 'меньше 1 мс' : Math.round(ms) + ' мс';
}

function readStorage(key) {
    try {
        return window.localStorage.getItem(key);
    } catch (error) {
        log.warn('localStorage read failed, input will not be restored', {key: key, error: error});
        return null;
    }
}

function writeStorage(key, value) {
    try {
        window.localStorage.setItem(key, value);
        return true;
    } catch (error) {
        log.warn('localStorage write failed, input will not survive restart', {key: key, bytes: value.length, error: error});
        return false;
    }
}

/* ============================ состояние ============================ */

const state = {
    size: DEFAULT_SIZE,
    // текст клеток как его ввёл человек; '' - ветви нет
    cells: emptyCells(DEFAULT_SIZE),
    // последний результат и ввод, по которому он получен
    result: null,
    resultInput: null,
    // ввод до нажатия «Пример» или «Очистить», чтобы вернуть одним нажатием
    undo: null,
    // клетки, подставленные по фото: «i:j» -> 'ok' (распознана) или 'check' (проверить)
    photoMarks: {},
    busy: false,
    runCounter: 0,
    saveTimer: 0
};

function emptyCells(size) {
    const cells = [];
    for (let i = 0; i < size; i++) {
        cells.push(new Array(size).fill(''));
    }
    return cells;
}

function snapshotInput() {
    return JSON.stringify({size: state.size, cells: state.cells});
}

function hasAnyValue(cells) {
    return cells.some(function (row) {
        return row.some(function (text) {
            return String(text).trim() !== '';
        });
    });
}

/* ============================ ввод матрицы ============================ */

function buildSizeSelect() {
    const select = $('#size');
    let html = '';
    for (let n = MIN_SIZE; n <= MAX_SIZE; n++) {
        html += '<option value="' + n + '">' + n + '</option>';
    }
    select.innerHTML = html;
}

function buildMatrix() {
    const n = state.size;
    let html = '<thead><tr><th class="corner" aria-label="вершины"></th>';
    for (let j = 0; j < n; j++) {
        html += '<th scope="col">' + (j + 1) + '</th>';
    }
    html += '</tr></thead><tbody>';
    for (let i = 0; i < n; i++) {
        html += '<tr><th scope="row">' + (i + 1) + '</th>';
        for (let j = 0; j < n; j++) {
            if (i === j) {
                html += '<td class="diag" aria-hidden="true">-</td>';
                continue;
            }
            const mark = state.photoMarks[i + ':' + j];
            html += '<td class="' + (i < j ? 'upper' : 'lower') + '">'
                + '<input type="text" inputmode="numeric" autocomplete="off" autocorrect="off"'
                + ' spellcheck="false" maxlength="9" enterkeyhint="next"'
                + (mark ? ' class="photo-' + mark + '"' : '')
                + ' data-i="' + i + '" data-j="' + j + '"'
                + ' aria-label="' + cellName(i, j) + '"'
                + ' value="' + escapeHtml(state.cells[i][j]) + '"></td>';
        }
        html += '</tr>';
    }
    html += '</tbody>';
    const table = $('#matrix');
    table.innerHTML = html;
    // клетка не уже 36 px (иначе не попасть пальцем) и не шире 56 px на большом экране
    table.style.minWidth = (26 + n * 39) + 'px';
    table.style.maxWidth = (26 + n * 59) + 'px';
    refreshValidation(false);
}

function cellInput(i, j) {
    return $('#matrix input[data-i="' + i + '"][data-j="' + j + '"]');
}

// разбор клетки: пусто - ветви нет; иначе целое число от 0 до MAX_CAPACITY
function parseCell(text) {
    const value = String(text).trim();
    if (value === '') {
        return {ok: true, value: 0};
    }
    if (/^\d+$/.test(value)) {
        const number = Number(value);
        if (number > MAX_CAPACITY) {
            return {ok: false, reason: 'не больше 1 000 000'};
        }
        return {ok: true, value: number};
    }
    if (/^-\s*\d/.test(value)) {
        return {ok: false, reason: 'пропускная способность не может быть отрицательной'};
    }
    if (/^\d+[.,]\d*$/.test(value)) {
        return {ok: false, reason: 'только целые числа, без дробной части'};
    }
    return {ok: false, reason: 'только цифры'};
}

function readMatrix() {
    const n = state.size;
    const matrix = [];
    const errors = [];
    for (let i = 0; i < n; i++) {
        const row = [];
        for (let j = 0; j < n; j++) {
            if (i === j) {
                row.push(0);
                continue;
            }
            const parsed = parseCell(state.cells[i][j]);
            if (!parsed.ok && i < j) {
                errors.push({i: i, j: j, text: state.cells[i][j], reason: parsed.reason});
            }
            row.push(parsed.ok ? parsed.value : 0);
        }
        matrix.push(row);
    }
    return {matrix: matrix, errors: errors};
}

function countBranches(matrix) {
    let count = 0;
    for (let i = 0; i < matrix.length; i++) {
        for (let j = i + 1; j < matrix.length; j++) {
            if (matrix[i][j] > 0) {
                count++;
            }
        }
    }
    return count;
}

// подсветка неверных клеток; текст ошибок под таблицей - сразу или только по кнопке
function refreshValidation(showMessage) {
    const input = readMatrix();
    const bad = {};
    input.errors.forEach(function (error) {
        bad[error.i + ':' + error.j] = true;
        bad[error.j + ':' + error.i] = true;
    });
    document.querySelectorAll('#matrix input').forEach(function (element) {
        const key = element.dataset.i + ':' + element.dataset.j;
        element.classList.toggle('invalid', !!bad[key]);
    });

    const box = $('#input-message');
    if (input.errors.length && (showMessage || !box.hidden)) {
        showInputErrors(input.errors);
    } else if (!input.errors.length && box.dataset.kind === 'cells') {
        hideInputMessage();
    }
    return input;
}

function showInputErrors(errors) {
    const shown = errors.slice(0, 4).map(function (error) {
        return cellName(error.i, error.j) + ' = «' + escapeHtml(String(error.text).trim()) + '»: ' + error.reason;
    });
    if (errors.length > shown.length) {
        shown.push('и ещё ' + (errors.length - shown.length));
    }
    showInputMessage('Исправьте клетки, выделенные красным. ' + shown.join('; ') + '.', 'cells', true);
}

function showInputMessage(html, kind, isHtml) {
    const box = $('#input-message');
    if (isHtml) {
        box.innerHTML = html;
    } else {
        box.textContent = html;
    }
    box.dataset.kind = kind;
    box.hidden = false;
}

function hideInputMessage() {
    const box = $('#input-message');
    box.hidden = true;
    box.dataset.kind = '';
}

function onCellInput(event) {
    const input = event.target;
    if (!input.matches('input[data-i]')) {
        return;
    }
    const i = Number(input.dataset.i);
    const j = Number(input.dataset.j);
    const text = input.value;

    // симметрия: c(i,j) = c(j,i), вторая клетка заполняется сама
    state.cells[i][j] = text;
    state.cells[j][i] = text;
    const mirror = cellInput(j, i);
    if (mirror) {
        mirror.value = text;
    }
    // клетку поправили руками - подсветка «по фото» с неё и с зеркальной снимается
    if (state.photoMarks[i + ':' + j] || state.photoMarks[j + ':' + i]) {
        delete state.photoMarks[i + ':' + j];
        delete state.photoMarks[j + ':' + i];
        [input, mirror].forEach(function (element) {
            if (element) {
                element.classList.remove('photo-ok', 'photo-check');
            }
        });
    }

    hideUndo();
    refreshValidation(false);
    updateStaleNote();
    scheduleSave();
    log.debug('cell edited', {cell: cellName(i, j), value: text});
}

// Enter переводит к следующей клетке над диагональю: так каждая ветвь вводится один раз
function onCellKeydown(event) {
    if (event.key !== 'Enter' || !event.target.matches('input[data-i]')) {
        return;
    }
    event.preventDefault();
    const i = Number(event.target.dataset.i);
    const j = Number(event.target.dataset.j);
    let row = Math.min(i, j);
    let column = Math.max(i, j) + 1;
    if (column >= state.size) {
        row += 1;
        column = row + 1;
    }
    const next = row < state.size - 1 ? cellInput(row, column) : null;
    if (next) {
        next.focus();
    } else {
        event.target.blur();
    }
}

function onCellFocus(event) {
    // на сенсорном экране выделение вызвало бы меню «Вырезать / Копировать» при каждом касании
    const touch = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    if (!touch && event.target.matches('input[data-i]')) {
        // с мышью число в клетке выделяется целиком: новое значение просто печатается поверх
        try {
            event.target.setSelectionRange(0, event.target.value.length);
        } catch (error) {
            log.debug('cannot select cell text', {error: error});
        }
    }
}

function applyInput(size, cells) {
    state.size = size;
    state.cells = cells;
    $('#size').value = String(size);
    buildMatrix();
    updateStaleNote();
    scheduleSave();
}

function onSizeChange() {
    const size = Number($('#size').value);
    const previous = state.size;
    // значения, которые помещаются в новый размер, сохраняются
    const cells = emptyCells(size);
    for (let i = 0; i < Math.min(size, previous); i++) {
        for (let j = 0; j < Math.min(size, previous); j++) {
            cells[i][j] = state.cells[i][j];
        }
    }
    hideUndo();
    state.photoMarks = {};
    applyInput(size, cells);
    log.info('matrix resized', {from: previous, to: size});
}

// marks - подсветка клеток, подставленных по фото; без неё подсветка снимается
function replaceInput(size, cells, message, marks) {
    const before = {size: state.size, cells: state.cells};
    state.photoMarks = marks || {};
    applyInput(size, cells);
    hideInputMessage();
    if (hasAnyValue(before.cells)) {
        state.undo = before;
        $('#undo-text').textContent = message;
        $('#undo').hidden = false;
    } else {
        hideUndo();
    }
}

function hideUndo() {
    state.undo = null;
    $('#undo').hidden = true;
}

function onUndo() {
    if (!state.undo) {
        return;
    }
    const before = state.undo;
    hideUndo();
    state.photoMarks = {};
    applyInput(before.size, before.cells);
    log.info('input restored after example/clear/photo', {size: before.size});
}

// таблица по фото (photo.js): матрица n x n уже симметричная - ядро сверило зеркальные клетки
function applyFlowPhoto(answer) {
    const n = answer.rows;
    const cells = emptyCells(n);
    const marks = {};
    let filled = 0;
    for (let i = 0; i < n; i++) {
        for (let j = 0; j < n; j++) {
            if (i === j) {
                continue;
            }
            const value = answer.values[i][j];
            cells[i][j] = value === null ? '' : String(value);
            if (answer.flags[i][j] !== 0) {
                marks[i + ':' + j] = 'check';
            } else if (value !== null) {
                marks[i + ':' + j] = 'ok';
            }
            if (value !== null && i < j) {
                filled++;
            }
        }
    }
    replaceInput(n, cells, 'Подставлено по фото.', marks);
    log.info('matrix filled from photo', {scan: answer.scanId, size: n, branches: filled});
    const card = $('#input-card');
    if (card.scrollIntoView) {
        card.scrollIntoView({behavior: 'smooth', block: 'start'});
    }
}

function onClear() {
    replaceInput(state.size, emptyCells(state.size), 'Матрица очищена.');
    log.info('matrix cleared', {size: state.size});
}

async function onExample() {
    log.info('example requested');
    try {
        const response = await fetch('api/example');
        const body = await response.json();
        if (!response.ok) {
            log.warn('example rejected', {status: response.status, title: body.title, detail: body.detail});
            showInputMessage((body.title || 'Пример не загрузился') + ': ' + (body.detail || response.status), 'request');
            return;
        }
        const cells = body.capacities.map(function (row) {
            return row.map(function (value) {
                return value ? String(value) : '';
            });
        });
        replaceInput(body.n, cells, 'Загружен пример.');
        log.info('example loaded', {size: body.n, branches: countBranches(body.capacities)});
    } catch (error) {
        log.error('example request failed', {error: error});
        showInputMessage('Пример не загрузился: ' + (error && error.message ? error.message : error), 'request');
    }
}

/* ============================ сохранение ввода ============================ */

function scheduleSave() {
    clearTimeout(state.saveTimer);
    state.saveTimer = setTimeout(function () {
        if (writeStorage(STORAGE_KEY, snapshotInput())) {
            log.debug('input saved', {size: state.size});
        }
    }, 300);
}

function loadInput() {
    const raw = readStorage(STORAGE_KEY);
    if (!raw) {
        log.info('no saved input, starting with an empty matrix', {size: DEFAULT_SIZE});
        return null;
    }
    try {
        const saved = JSON.parse(raw);
        const size = Number(saved.size);
        const valid = size >= MIN_SIZE && size <= MAX_SIZE && Array.isArray(saved.cells)
            && saved.cells.length === size
            && saved.cells.every(function (row) {
                return Array.isArray(row) && row.length === size;
            });
        if (!valid) {
            log.warn('saved input has unexpected shape, ignoring it', {bytes: raw.length});
            return null;
        }
        const cells = saved.cells.map(function (row) {
            return row.map(function (text) {
                return text === null || text === undefined ? '' : String(text);
            });
        });
        log.info('saved input restored', {size: size});
        return {size: size, cells: cells};
    } catch (error) {
        log.warn('saved input is not valid JSON, ignoring it', {bytes: raw.length, error: error});
        return null;
    }
}

/* ============================ расчёт ============================ */

function nextRunId() {
    state.runCounter += 1;
    return 'r' + Date.now().toString(36) + '-' + state.runCounter;
}

async function onSolve() {
    if (state.busy) {
        log.debug('solve ignored: previous calculation is still running');
        return;
    }
    const input = refreshValidation(true);
    if (input.errors.length) {
        log.warn('solve blocked by invalid cells', {
            count: input.errors.length,
            first: cellName(input.errors[0].i, input.errors[0].j) + '=' + input.errors[0].text
        });
        const first = cellInput(input.errors[0].i, input.errors[0].j);
        if (first) {
            first.focus();
        }
        return;
    }
    const branches = countBranches(input.matrix);
    if (branches === 0) {
        showInputMessage('В сети нет ни одной ветви: введите хотя бы одну ненулевую пропускную способность '
            + 'или нажмите «Пример».', 'empty');
        log.warn('solve blocked: matrix has no branches', {size: state.size});
        return;
    }
    hideInputMessage();

    const runId = nextRunId();
    const startedAt = performance.now();
    setBusy(true);
    log.info('solve request', {run: runId, size: state.size, branches: branches});
    try {
        const response = await fetch('api/solve', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({runId: runId, capacities: input.matrix})
        });
        const body = await response.json();
        if (!response.ok) {
            log.warn('solve rejected', {run: runId, status: response.status, title: body.title, detail: body.detail});
            showInputMessage((body.title || 'Расчёт не выполнен') + ': ' + (body.detail || response.status), 'request');
            return;
        }
        state.result = body;
        state.resultInput = snapshotInput();
        renderResult(body);
        log.info('solve rendered', {
            run: runId,
            steps: body.steps.length,
            tree: describeTree(body.tree),
            coreMs: body.elapsedMs,
            totalMs: Math.round(performance.now() - startedAt)
        });
        const results = $('#results');
        if (results.scrollIntoView) {
            results.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
    } catch (error) {
        log.error('solve failed', {run: runId, error: error});
        showInputMessage('Сбой расчёта: ' + (error && error.message ? error.message : error), 'request');
    } finally {
        setBusy(false);
    }
}

function setBusy(busy) {
    state.busy = busy;
    const button = $('#btn-solve');
    button.disabled = busy;
    button.textContent = busy ? 'Считаю…' : 'Рассчитать';
}

function describeTree(tree) {
    return tree.map(function (edge) {
        return edge.u + '-' + edge.v + ':' + edge.value;
    }).join(' ');
}

function updateStaleNote() {
    const note = $('#stale-note');
    if (note) {
        note.hidden = !state.result || state.resultInput === snapshotInput();
    }
}

/* ============================ вывод результата ============================ */

function renderPlaceholder() {
    $('#results').innerHTML = '<div class="card"><div class="placeholder">'
        + 'Введите пропускные способности ветвей или нажмите «Пример», затем «Рассчитать».'
        + '</div></div>';
}

// доступ по ключу пропал: решение убирается с экрана, введённая матрица остаётся
function clearFlowResult() {
    state.result = null;
    state.resultInput = null;
    hideInputMessage();
    renderPlaceholder();
}

function renderResult(result) {
    const html = [];
    html.push('<div class="message warn" id="stale-note" hidden>Сеть изменилась после расчёта - результат ниже '
        + 'относится к прошлым данным. Нажмите «Рассчитать».</div>');
    html.push('<nav class="chips" aria-label="Разделы результата">'
        + '<a href="#sec-network" data-target="sec-network">Сеть</a>'
        + '<a href="#sec-steps" data-target="sec-steps">Шаги</a>'
        + '<a href="#sec-tree" data-target="sec-tree">Дерево</a>'
        + '<a href="#sec-table" data-target="sec-table">Таблица</a>'
        + '</nav>');
    if (!result.connected) {
        html.push(disconnectedNote(result));
    }
    html.push(networkCard(result));
    html.push(stepsCard(result));
    html.push(treeCard(result));
    html.push(tableCard(result));
    html.push('<p class="footnote">Расчёт в ядре: ' + fmtMs(result.elapsedMs) + '</p>');
    $('#results').innerHTML = html.join('');
}

function disconnectedNote(result) {
    const parts = result.components.map(setText).join(', ');
    return '<div class="message warn">Сеть несвязная: части ' + parts + '. Между вершинами разных частей '
        + 'путей нет, поток между ними 0, а ветви дерева, которые соединяют такие части, имеют вес 0.</div>';
}

function networkCard(result) {
    const branches = countBranches(result.capacities);
    return '<section class="card" id="sec-network"><h2>Исходная сеть</h2><div class="card-body">'
        + '<div class="figure">' + drawNetwork(result.n, result.capacities) + '</div>'
        + '<p class="hint center">' + result.n + ' ' + plural(result.n, 'вершина', 'вершины', 'вершин') + ', '
        + branches + ' ' + plural(branches, 'ветвь', 'ветви', 'ветвей')
        + '. На ветвях - пропускные способности.</p>'
        + '</div></section>';
}

function stepsCard(result) {
    const count = result.steps.length;
    let html = '<section class="card" id="sec-steps"><h2>Шаги: ' + count + '</h2><div class="card-body">'
        + '<p class="hint">На каждом шаге берётся пара вершин S и T из ещё не разделённой группы. Поток '
        + 'считается по всей исходной сети методом Форда-Фалкерсона: пути от S к T ищутся, пока они не '
        + 'исчерпаются. На шаге три таблицы по всем вершинам, строки и столбцы по порядку: ' + R_NOTA + ' - сеть шага, ' + XST
        + ' - потоки по ветвям, ' + R_XST + ' - что осталось после вычитания потоков; по ней видно, '
        + 'какие ветви насыщены, - это разрез. Разрез делит группу надвое, половины соединяет новая ветвь '
        + 'дерева, а найденный поток '
        + 'вписывается в итоговую таблицу. После последнего шага остальные пары таблицы заполняются по '
        + 'дереву.</p>'
        + summaryTable(result.steps)
        + '<div class="steps-toolbar"><button type="button" class="btn-ghost btn-small" id="btn-expand">'
        + 'Развернуть все шаги</button></div>';
    html += result.steps.map(function (step) {
        return stepDetails(step, result);
    }).join('');
    return html + '</div></section>';
}

function summaryTable(steps) {
    let html = '<div class="table-scroll"><table class="data summary"><thead><tr>'
        + '<th>№</th><th class="nowrap">S → T</th><th>Поток</th><th>Разрез S | T</th><th>Ветвь дерева</th>'
        + '</tr></thead><tbody>';
    steps.forEach(function (step) {
        html += '<tr data-step="' + step.index + '" tabindex="0" title="Открыть шаг ' + step.index + '">'
            + '<td class="num">' + step.index + '</td>'
            + '<td class="nowrap">' + step.s + ' → ' + step.t + '</td>'
            + '<td class="num"><b>' + step.maxFlow + '</b></td>'
            + '<td>' + setSpan(step.sourceSide) + ' | ' + setSpan(step.sinkSide) + '</td>'
            + '<td>' + setSpan(step.sourcePart) + ' - ' + setSpan(step.sinkPart) + '</td>'
            + '</tr>';
    });
    return html + '</tbody></table></div>'
        + '<p class="legend">Нажмите на строку, чтобы открыть шаг целиком.</p>';
}

function stepDetails(step, result) {
    let html = '<details class="step" id="step-' + step.index + '"><summary>'
        + '<span class="step-title">Шаг ' + step.index + ' из ' + step.total + '</span> '
        + '<span class="step-pair">S = ' + step.s + ', T = ' + step.t + '</span> '
        + '<span class="step-flow">поток ' + step.maxFlow + '</span>'
        + '</summary><div class="step-body">';

    // порядок как на доске: R, пути, Xst, R - Xst, затем 1) поток, 2) разрез, 3) ветвь дерева
    html += '<p>Делим группу ' + setText(step.group) + '. Считаем по всей исходной сети.</p>';
    html += '<h4>' + R_NOTA + ' - таблица сети на шаге</h4>' + stepMatrix(step);
    html += '<h4>Пути от S к T, пока не исчерпаются</h4>' + pathsList(step);
    html += '<h4>' + XST + ' - потоки по ветвям</h4>' + flowMatrix(step);
    html += '<h4>' + R_XST + ' - после вычитания потоков</h4>' + residualMatrix(step);

    const sum = step.paths.length > 1
        ? ' = ' + step.paths.map(function (path) {
            return path.amount;
        }).join(' + ')
        : '';
    html += '<h4>1) Максимальный поток S → T</h4>'
        + '<p class="result-line">Максимальный поток ' + step.s + ' → ' + step.t + sum
        + ' = <b>' + step.maxFlow + '</b></p>';

    // сеть неориентированная: ветвь записывается меньшим номером вперёд, как клетка
    // над диагональю, куда матрицу и вводят, - (2,6), а не (6,2)
    const cutPairs = step.cutEdges.map(function (edge) {
        return {a: Math.min(edge.u, edge.v), b: Math.max(edge.u, edge.v), capacity: edge.capacity};
    }).sort(function (x, y) {
        return x.a - y.a || x.b - y.b;
    });
    html += '<h4>2) Разрез S | T</h4>'
        + '<p class="cut"><span class="side-s">' + setText(step.sourceSide) + '</span>'
        + ' <span class="cut-bar">|</span> <span class="side-t">' + setText(step.sinkSide) + '</span></p>';
    html += '<p>Ветви разреза: ' + (cutPairs.length
        ? cutPairs.map(function (pair) {
            return '(' + pair.a + ',' + pair.b + ') = ' + pair.capacity;
        }).join(', ') + '; сумма <b>' + step.maxFlow + '</b> - равна потоку.'
        : 'нет: стороны ничем не связаны, сумма 0.') + '</p>';

    html += '<h4>3) Ветвь дерева</h4><p>' + setText(step.sourcePart) + ' - ' + setText(step.sinkPart)
        + ': <b>' + step.maxFlow + '</b></p>';
    html += '<h4>Дерево после шага</h4><div class="figure">' + drawGroupTree(step.tree) + '</div>';
    html += '<h4>Итоговая таблица после шага ' + step.index + '</h4>' + progressTable(result, step.index);
    return html + '</div></details>';
}

// итоговая таблица после шага upTo. Каждый шаг находит поток ровно одной пары S-T,
// он вписывается в таблицу сразу и уже не меняется. Остальные пары известны только
// после последнего шага - их значения берутся по готовому дереву (result.table)
function progressTable(result, upTo) {
    const n = result.n;
    const found = [];
    for (let i = 0; i < n; i++) {
        found.push(new Array(n).fill(null));
    }
    let current = null;
    result.steps.forEach(function (step) {
        if (step.index > upTo) {
            return;
        }
        found[step.s - 1][step.t - 1] = step.maxFlow;
        found[step.t - 1][step.s - 1] = step.maxFlow;
        if (step.index === upTo) {
            current = step;
        }
    });
    const last = upTo === result.steps.length;

    function isCurrent(i, j) {
        return (i === current.s - 1 && j === current.t - 1) || (i === current.t - 1 && j === current.s - 1);
    }

    let html = '<div class="table-scroll"><table class="data final progress"><thead><tr><th></th>';
    for (let j = 0; j < n; j++) {
        html += '<th>' + (j + 1) + '</th>';
    }
    html += '</tr></thead><tbody>';
    for (let i = 0; i < n; i++) {
        html += '<tr><th>' + (i + 1) + '</th>';
        for (let j = 0; j < n; j++) {
            if (i === j) {
                html += '<td class="diag">-</td>';
            } else if (found[i][j] !== null) {
                html += '<td class="found' + (isCurrent(i, j) ? ' fresh' : '') + '">' + found[i][j] + '</td>';
            } else if (last) {
                html += '<td class="from-tree">' + result.table[i][j] + '</td>';
            } else {
                html += '<td class="zero">·</td>';
            }
        }
        html += '</tr>';
    }
    html += '</tbody></table></div>';

    const pairs = n * (n - 1) / 2;
    html += '<p class="legend">Новое на этом шаге: <span class="fresh-mark">' + current.s + '-' + current.t
        + ' = ' + current.maxFlow + '</span>. ';
    if (last) {
        html += 'Жирным - потоки, найденные на шагах (' + upTo + '), обычным - остальные ' + (pairs - upTo)
            + ' по дереву: самая слабая ветвь на пути.</p>';
    } else {
        html += 'Найдено ' + upTo + ' ' + plural(upTo, 'пара', 'пары', 'пар') + ' из ' + pairs
            + ', остальные - по дереву после шага ' + result.steps.length + '.</p>';
    }
    return html;
}

// подсветка заголовков таблиц шага: строки и столбцы - вершины 1..n по порядку,
// поэтому S - строка и столбец s - 1, T - t - 1
function stepHeaderClass(step, index) {
    if (index === step.s - 1) {
        return 'is-s';
    }
    if (index === step.t - 1) {
        return 'is-t';
    }
    return '';
}

function stepMatrix(step) {
    const nodes = step.nodes;
    let html = '<div class="table-scroll"><table class="data matrix-view"><thead><tr><th></th>';
    nodes.forEach(function (node, index) {
        html += '<th class="' + stepHeaderClass(step, index) + '">' + escapeHtml(node.label) + '</th>';
    });
    html += '</tr></thead><tbody>';
    step.capacities.forEach(function (row, a) {
        html += '<tr><th class="' + stepHeaderClass(step, a) + '">' + escapeHtml(nodes[a].label) + '</th>';
        row.forEach(function (value, b) {
            if (a === b) {
                html += '<td class="diag">-</td>';
            } else {
                html += value ? '<td>' + value + '</td>' : '<td class="zero">·</td>';
            }
        });
        html += '</tr>';
    });
    html += '</tbody></table></div>'
        + '<p class="legend"><span class="is-s">S = ' + step.s + '</span>, <span class="is-t">T = ' + step.t
        + '</span>; точка - ветви нет.</p>';
    return html;
}

// Xst - потоки по ветвям, когда пути исчерпаны: по направлению потока +f, в обратную клетку -f,
// поэтому R - Xst клетка в клетку даёт следующую таблицу. Таблица приходит из ядра
// (step.branchFlow), здесь вывод и строка проверки: из S выходит ровно найденный поток
function flowMatrix(step) {
    const nodes = step.nodes;
    const xst = step.branchFlow;
    if (!Array.isArray(xst)) {
        log.error('step came without branchFlow table', {step: step.index, keys: Object.keys(step)});
        return '<div class="message error">Таблица ' + XST + ' не пришла из ядра.</div>';
    }
    let html = '<div class="table-scroll"><table class="data matrix-view xst"><thead><tr><th></th>';
    nodes.forEach(function (node, index) {
        html += '<th class="' + stepHeaderClass(step, index) + '">' + escapeHtml(node.label) + '</th>';
    });
    html += '</tr></thead><tbody>';
    xst.forEach(function (row, a) {
        html += '<tr><th class="' + stepHeaderClass(step, a) + '">' + escapeHtml(nodes[a].label) + '</th>';
        row.forEach(function (value, b) {
            if (a === b) {
                html += '<td class="diag">-</td>';
            } else if (!value) {
                html += '<td class="zero">·</td>';
            } else {
                html += '<td class="' + (value > 0 ? 'forward' : 'backward') + '">' + value + '</td>';
            }
        });
        html += '</tr>';
    });
    html += '</tbody></table></div>';

    // что выходит из строки S, то и есть поток шага
    const sourceRow = step.s - 1;
    if (!Array.isArray(xst[sourceRow])) {
        log.error('xst has no row for S', {step: step.index, s: step.s, rows: xst.length});
        return html + '<div class="message error">В таблице ' + XST + ' нет строки S = ' + step.s + '.</div>';
    }
    const out = xst[sourceRow].filter(function (value, b) {
        return b !== sourceRow && value !== 0;
    });
    const total = out.reduce(function (acc, value) {
        return acc + value;
    }, 0);
    if (total !== step.maxFlow) {
        log.error('xst row S does not sum to max flow', {step: step.index, sum: total, maxFlow: step.maxFlow});
    }
    let check;
    if (!out.length) {
        check = 'Из S поток не выходит: путей не было.';
    } else {
        const terms = out.map(function (value) {
            return value < 0 ? '(' + value + ')' : String(value);
        });
        check = 'Из S выходит ' + (terms.length > 1 ? terms.join(' + ') + ' = ' : '') + '<b>' + total + '</b>'
            + (total === step.maxFlow ? ' - равно потоку.' : ' - не равно потоку ' + step.maxFlow + '.');
    }
    return html + '<p class="legend">По направлению потока +f, в обратную клетку -f; точка - потока нет. '
        + check + '</p>';
}

// R - Xst: таблица шага после того, как пути исчерпаны, - как в табличном методе на бумаге:
// по каждой ветви пути из c(i,j) вычтен пропущенный поток, к c(j,i) прибавлен.
// Сама таблица приходит из ядра (step.residual), здесь только вывод
function residualMatrix(step) {
    const nodes = step.nodes;
    let html = '<div class="table-scroll"><table class="data matrix-view residual"><thead><tr><th></th>';
    nodes.forEach(function (node, index) {
        html += '<th class="' + stepHeaderClass(step, index) + '">' + escapeHtml(node.label) + '</th>';
    });
    html += '</tr></thead><tbody>';
    step.residual.forEach(function (row, a) {
        html += '<tr><th class="' + stepHeaderClass(step, a) + '">' + escapeHtml(nodes[a].label) + '</th>';
        row.forEach(function (value, b) {
            const before = step.capacities[a][b];
            if (a === b) {
                html += '<td class="diag">-</td>';
            } else if (!before && !value) {
                html += '<td class="zero">·</td>';
            } else {
                html += '<td' + (value !== before ? ' class="changed"' : '') + '>' + value + '</td>';
            }
        });
        html += '</tr>';
    });
    html += '</tbody></table></div>';

    // сторона S разреза в подписях таблицы шага
    const reachable = nodes.filter(function (node) {
        return step.sourceSide.indexOf(node.members[0]) !== -1;
    }).map(function (node) {
        return escapeHtml(node.label);
    });
    html += '<p class="legend">' + (step.paths.length
        ? 'Из каждой клетки ' + R_NOTA + ' вычтена клетка ' + XST + subtractionExample(step) + '. Выделены клетки, которые '
            + 'изменились. '
        : 'Путей не было - ' + XST + ' пустая, таблица не изменилась. ')
        + 'От S по ненулевым клеткам достижимы: ' + reachable.join(', ') + ' - это сторона S разреза.</p>';
    return html;
}

// пример вычитания для подписи под R - Xst: первая клетка с минусом в Xst - на ней видно,
// что минус на минус даёт плюс и обратная клетка растёт
function subtractionExample(step) {
    const xst = step.branchFlow;
    if (!Array.isArray(xst)) {
        return '';
    }
    for (let a = 0; a < xst.length; a++) {
        for (let b = 0; b < xst.length; b++) {
            if (xst[a][b] < 0) {
                return ', например (' + escapeHtml(step.nodes[a].label) + ',' + escapeHtml(step.nodes[b].label)
                    + '): ' + step.capacities[a][b] + ' - (' + xst[a][b] + ') = ' + step.residual[a][b];
            }
        }
    }
    return '';
}

function pathsList(step) {
    if (!step.paths.length) {
        return '<p class="muted">Путей от S к T нет: S и T лежат в разных частях сети, поток 0.</p>';
    }
    let html = '<ol class="paths">';
    step.paths.forEach(function (path) {
        const labels = path.nodes.map(function (index) {
            return escapeHtml(step.nodes[index].label);
        });
        html += '<li><span class="path">' + labels.join(' → ') + '</span> <span class="amount">+' + path.amount
            + '</span></li>';
    });
    return html + '</ol><p class="muted">Путей больше нет - поток максимальный.</p>';
}

function treeCard(result) {
    let html = '<section class="card" id="sec-tree"><h2>Дерево Гомори-Ху</h2><div class="card-body">'
        + '<div class="figure">' + drawFinalTree(result) + '</div>'
        + '<p class="hint">На ветвях - максимальный поток между вершинами, которые ветвь соединяет. Ветвь, '
        + 'найденная на шаге для пары S, T, при следующих делениях групп может перейти к другим вершинам этих '
        + 'групп - её поток при этом не меняется.</p><ul class="branches">';
    result.tree.forEach(function (edge) {
        html += '<li>' + edge.u + ' - ' + edge.v + ': <b>' + edge.value + '</b> <span class="muted">(шаг '
            + edge.step + ')</span></li>';
    });
    return html + '</ul></div></section>';
}

function tableCard(result) {
    const n = result.n;
    let html = '<section class="card" id="sec-table"><h2>Итоговая таблица</h2><div class="card-body">'
        + '<p class="hint">Максимальный поток между каждой парой вершин. Он равен самой слабой ветви на пути '
        + 'между вершинами в дереве.</p>'
        + '<div class="table-scroll"><table class="data final"><thead><tr><th></th>';
    for (let j = 0; j < n; j++) {
        html += '<th>' + (j + 1) + '</th>';
    }
    html += '</tr></thead><tbody>';
    for (let i = 0; i < n; i++) {
        html += '<tr><th>' + (i + 1) + '</th>';
        for (let j = 0; j < n; j++) {
            html += i === j ? '<td class="diag">-</td>' : '<td>' + result.table[i][j] + '</td>';
        }
        html += '</tr>';
    }
    return html + '</tbody></table></div></div></section>';
}

/* ============================ рисунки ============================ */

// исходная сеть: вершины по кругу, толщина ветви растёт с пропускной способностью
function drawNetwork(n, capacities) {
    const size = 340;
    const center = size / 2;
    const radius = n <= 4 ? 105 : 128;
    const points = [];
    for (let i = 0; i < n; i++) {
        const angle = -Math.PI / 2 + 2 * Math.PI * i / n;
        points.push({x: center + radius * Math.cos(angle), y: center + radius * Math.sin(angle)});
    }

    let strongest = 1;
    for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
            strongest = Math.max(strongest, capacities[i][j]);
        }
    }

    let lines = '';
    let labels = '';
    for (let i = 0; i < n; i++) {
        for (let j = i + 1; j < n; j++) {
            const capacity = capacities[i][j];
            if (!capacity) {
                continue;
            }
            const a = points[i];
            const b = points[j];
            const width = 1.2 + 2.8 * capacity / strongest;
            lines += '<line class="edge" x1="' + a.x.toFixed(1) + '" y1="' + a.y.toFixed(1) + '" x2="'
                + b.x.toFixed(1) + '" y2="' + b.y.toFixed(1) + '" stroke-width="' + width.toFixed(1) + '"/>';
            // подписи длинных хорд разводятся от центра, иначе они слипаются в середине круга
            const span = Math.min(j - i, n - (j - i));
            const t = span > 1 && n > 4 ? ((i + j) % 2 === 0 ? 0.36 : 0.64) : 0.5;
            const x = a.x + (b.x - a.x) * t;
            const y = a.y + (b.y - a.y) * t;
            labels += '<text class="edge-label" x="' + x.toFixed(1) + '" y="' + y.toFixed(1) + '" dy=".35em">'
                + capacity + '</text>';
        }
    }

    let nodes = '';
    points.forEach(function (point, i) {
        nodes += '<g class="node"><circle cx="' + point.x.toFixed(1) + '" cy="' + point.y.toFixed(1)
            + '" r="16"/><text x="' + point.x.toFixed(1) + '" y="' + point.y.toFixed(1) + '" dy=".35em">'
            + (i + 1) + '</text></g>';
    });

    return '<svg class="diagram" width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size
        + '" role="img" aria-label="Схема исходной сети">' + lines + labels + nodes + '</svg>';
}

function drawFinalTree(result) {
    const nodes = [];
    for (let v = 1; v <= result.n; v++) {
        nodes.push(String(v));
    }
    const edges = result.tree.map(function (edge) {
        return {a: edge.u - 1, b: edge.v - 1, value: edge.value, fresh: false};
    });
    return drawTree(nodes, edges, 0, 'Дерево Гомори-Ху');
}

// дерево после шага: вершины дерева - группы, новая ветвь выделена цветом
function drawGroupTree(tree) {
    const nodes = tree.groups.map(function (group) {
        return group.join(', ');
    });
    let root = 0;
    tree.groups.forEach(function (group, index) {
        if (group.indexOf(1) !== -1) {
            root = index;
        }
    });
    const edges = tree.edges.map(function (edge, index) {
        return {a: edge.a, b: edge.b, value: edge.value, fresh: index === tree.newEdge};
    });
    return drawTree(nodes, edges, root, 'Дерево после шага');
}

// общий рисунок дерева: корень сверху, потомки ярусами, лист за листом слева направо
function drawTree(labels, edges, root, title) {
    const count = labels.length;
    const children = [];
    const adjacency = [];
    for (let i = 0; i < count; i++) {
        children.push([]);
        adjacency.push([]);
    }
    edges.forEach(function (edge) {
        adjacency[edge.a].push(edge.b);
        adjacency[edge.b].push(edge.a);
    });

    const depth = new Array(count).fill(-1);
    depth[root] = 0;
    const queue = [root];
    while (queue.length) {
        const v = queue.shift();
        adjacency[v].slice().sort(function (x, y) {
            return x - y;
        }).forEach(function (u) {
            if (depth[u] === -1) {
                depth[u] = depth[v] + 1;
                children[v].push(u);
                queue.push(u);
            }
        });
    }

    const slot = [];
    let leaves = 0;

    function place(v) {
        if (!children[v].length) {
            slot[v] = leaves;
            leaves += 1;
            return;
        }
        children[v].forEach(place);
        slot[v] = (slot[children[v][0]] + slot[children[v][children[v].length - 1]]) / 2;
    }

    place(root);

    const widths = labels.map(function (label) {
        return Math.max(32, label.length * 7.6 + 20);
    });
    const slotWidth = Math.max.apply(null, widths) + 26;
    const levelHeight = 78;
    const maxDepth = Math.max.apply(null, depth);
    const width = Math.max(leaves * slotWidth, slotWidth);
    const height = (maxDepth + 1) * levelHeight;
    const x = function (v) {
        return slot[v] * slotWidth + slotWidth / 2;
    };
    const y = function (v) {
        return depth[v] * levelHeight + levelHeight / 2;
    };

    let lines = '';
    let values = '';
    edges.forEach(function (edge) {
        const x1 = x(edge.a);
        const y1 = y(edge.a);
        const x2 = x(edge.b);
        const y2 = y(edge.b);
        lines += '<line class="edge' + (edge.fresh ? ' fresh' : '') + '" x1="' + x1.toFixed(1) + '" y1="'
            + y1.toFixed(1) + '" x2="' + x2.toFixed(1) + '" y2="' + y2.toFixed(1) + '"/>';
        values += '<text class="edge-label' + (edge.fresh ? ' fresh' : '') + '" x="' + ((x1 + x2) / 2).toFixed(1)
            + '" y="' + ((y1 + y2) / 2).toFixed(1) + '" dy=".35em">' + edge.value + '</text>';
    });

    let boxes = '';
    labels.forEach(function (label, v) {
        const w = widths[v];
        boxes += '<g class="node"><rect x="' + (x(v) - w / 2).toFixed(1) + '" y="' + (y(v) - 16).toFixed(1)
            + '" width="' + w.toFixed(1) + '" height="32" rx="16"/><text x="' + x(v).toFixed(1) + '" y="'
            + y(v).toFixed(1) + '" dy=".35em">' + escapeHtml(label) + '</text></g>';
    });

    return '<svg class="diagram" width="' + Math.round(width) + '" height="' + height + '" viewBox="0 0 '
        + Math.round(width) + ' ' + height + '" role="img" aria-label="' + escapeHtml(title) + '">'
        + lines + values + boxes + '</svg>';
}

/* ============================ события результата ============================ */

function onResultsClick(event) {
    const chip = event.target.closest('a[data-target]');
    if (chip) {
        event.preventDefault();
        const section = document.getElementById(chip.dataset.target);
        if (section) {
            section.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
        return;
    }

    const row = event.target.closest('tr[data-step]');
    if (row) {
        openStep(row.dataset.step);
        return;
    }

    if (event.target.id === 'btn-expand') {
        const steps = document.querySelectorAll('#results details.step');
        const open = !Array.prototype.every.call(steps, function (details) {
            return details.open;
        });
        steps.forEach(function (details) {
            details.open = open;
        });
        event.target.textContent = open ? 'Свернуть все шаги' : 'Развернуть все шаги';
        log.debug('all steps toggled', {open: open, count: steps.length});
    }
}

function onResultsKeydown(event) {
    const row = event.target.closest('tr[data-step]');
    if (row && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault();
        openStep(row.dataset.step);
    }
}

function openStep(index) {
    const details = document.getElementById('step-' + index);
    if (!details) {
        log.warn('step details not found for summary row', {step: index});
        return;
    }
    details.open = true;
    details.scrollIntoView({behavior: 'smooth', block: 'start'});
    log.debug('step opened from summary', {step: index});
}

/* ============================ вкладки ============================ */

// вкладка «Поток» - этот файл, «Размещение» - placement.js, «Транспортная» - transport.js.
// Какие из них видны, решает ключ (access.js)
const TAB_STORAGE_KEY = 'multiflow.tab.v1';
const TABS = {
    flow: {subtitle: 'Максимальные потоки между всеми парами вершин - дерево Гомори-Ху'},
    placement: {subtitle: 'Размещение производства - задача о назначениях, венгерский метод'},
    transport: {subtitle: 'Транспортная задача - северо-западный угол и минимальный тариф'}
};

function selectTab(name, reason) {
    document.querySelectorAll('.tabs [data-tab]').forEach(function (button) {
        const active = button.dataset.tab === name;
        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    Object.keys(TABS).forEach(function (tab) {
        $('#tab-' + tab).hidden = tab !== name;
    });
    $('#masthead-sub').textContent = TABS[name].subtitle;
    writeStorage(TAB_STORAGE_KEY, name);
    log.info('tab selected', {tab: name, reason: reason});
}

function onTabClick(event) {
    const button = event.target.closest('[data-tab]');
    if (!button || button.classList.contains('active')) {
        return;
    }
    selectTab(button.dataset.tab, 'click');
    // новая вкладка открывается с начала, а не с того места, где листали прошлую
    window.scrollTo(0, 0);
}

function initTabs() {
    const saved = readStorage(TAB_STORAGE_KEY);
    if (saved && !TABS[saved]) {
        log.warn('saved tab is unknown, opening the flow tab', {saved: saved});
    }
    selectTab(saved && TABS[saved] ? saved : 'flow', saved && TABS[saved] ? 'restored' : 'default');
    $('.tabs').addEventListener('click', onTabClick);
}

/* ============================ запуск ============================ */

function init() {
    log.info('page start', {
        bridge: typeof MultiFlowBridge !== 'undefined',
        logLevel: LOG_LEVEL,
        viewport: window.innerWidth + 'x' + window.innerHeight,
        userAgent: navigator.userAgent
    });

    buildSizeSelect();
    const saved = loadInput();
    if (saved) {
        applyInput(saved.size, saved.cells);
    } else {
        applyInput(DEFAULT_SIZE, emptyCells(DEFAULT_SIZE));
    }
    renderPlaceholder();

    const matrix = $('#matrix');
    matrix.addEventListener('input', onCellInput);
    matrix.addEventListener('keydown', onCellKeydown);
    matrix.addEventListener('focusin', onCellFocus);
    $('#size').addEventListener('change', onSizeChange);
    $('#btn-example').addEventListener('click', onExample);
    $('#btn-clear').addEventListener('click', onClear);
    $('#btn-undo').addEventListener('click', onUndo);
    $('#btn-solve').addEventListener('click', onSolve);
    $('#results').addEventListener('click', onResultsClick);
    $('#results').addEventListener('keydown', onResultsKeydown);
    initTabs();

    log.info('page ready', {size: state.size, restored: !!saved});
}

init();
