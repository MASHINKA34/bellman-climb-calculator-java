'use strict';

/* ===========================================================================
   Вкладка «Транспортная» - математическая модель (открытая и закрытая) и начальный
   план двумя методами: северо-западного угла и минимального тарифа. Для сверки с
   Excel - оптимальный план без шагов.

   Весь расчёт выполняет ядро на Java (пакет transport). В приложении его зовёт мост
   android-bridge.js, на сайте - сервер по адресам api/transport/example и
   api/transport/solve. Здесь только ввод таблицы и вывод решения.

   Подключается после app.js и берёт оттуда утилиты: createLogger, $, escapeHtml,
   plural, fmtMs, readStorage, writeStorage.
   Код рассчитан и на старый WebView из Android 10, поэтому без ?. и ??.
   =========================================================================== */

const TR_MIN_SIZE = 2;
const TR_MAX_SIZE = 10;
const TR_DEFAULT_SUPPLIERS = 4;
const TR_DEFAULT_CONSUMERS = 5;
const TR_MAX_NUMBER = 1000000;
const TR_VARIANTS = 27;
const TR_STORAGE_KEY = 'multiflow.transport.v1';

const trLog = createLogger('transport');

/* ============================ состояние ============================ */

const tr = {
    suppliers: TR_DEFAULT_SUPPLIERS,
    consumers: TR_DEFAULT_CONSUMERS,
    // текст клеток как его ввёл человек
    input: trEmptyInput(TR_DEFAULT_SUPPLIERS, TR_DEFAULT_CONSUMERS),
    // нули фиктивной строки или столбца брать в последнюю очередь
    fictitiousLast: true,
    // последний результат и ввод, по которому он получен
    result: null,
    resultInput: null,
    // ввод до выбора варианта, «Очистить» или фото, чтобы вернуть одним нажатием
    undo: null,
    // клетки, подставленные по фото: ключ trKey -> 'ok', 'check' или 'missing'
    photoMarks: {},
    // встроенные варианты, загружаются при первом выборе
    variants: null,
    busy: false,
    // после первой попытки расчёта пустые клетки подсвечиваются сразу
    checked: false,
    runCounter: 0,
    saveTimer: 0
};

function trEmptyInput(suppliers, consumers) {
    const input = {costs: [], supply: new Array(suppliers).fill(''), demand: new Array(consumers).fill('')};
    for (let i = 0; i < suppliers; i++) {
        input.costs.push(new Array(consumers).fill(''));
    }
    return input;
}

// новый размер; значения, которые в него помещаются, сохраняются
function trResize(input, suppliers, consumers) {
    const resized = trEmptyInput(suppliers, consumers);
    for (let i = 0; i < suppliers; i++) {
        for (let j = 0; j < consumers; j++) {
            if (input.costs[i] !== undefined && input.costs[i][j] !== undefined) {
                resized.costs[i][j] = input.costs[i][j];
            }
        }
        if (input.supply[i] !== undefined) {
            resized.supply[i] = input.supply[i];
        }
    }
    for (let j = 0; j < consumers; j++) {
        if (input.demand[j] !== undefined) {
            resized.demand[j] = input.demand[j];
        }
    }
    return resized;
}

function trSnapshot() {
    return JSON.stringify({
        suppliers: tr.suppliers,
        consumers: tr.consumers,
        input: tr.input,
        fictitiousLast: tr.fictitiousLast
    });
}

function trHasAnyValue(input) {
    const filled = function (text) {
        return String(text).trim() !== '';
    };
    return input.costs.some(function (row) {
        return row.some(filled);
    }) || input.supply.some(filled) || input.demand.some(filled);
}

/* ============================ числа и обозначения ============================ */

// 1234567 -> «1 234 567»; короткие числа без пробелов, как в задании
function trNumber(value) {
    const digits = String(value);
    return digits.length < 6 ? digits : digits.replace(/\B(?=(\d{3})+(?!\d))/g, '&nbsp;');
}

// x с индексами: до 9 подряд (x15), дальше через запятую (x1,10), иначе их не различить
function trVariable(i, j, wide) {
    return '<i>x</i><sub>' + (i + 1) + (wide ? ',' : '') + (j + 1) + '</sub>';
}

function trSupplierName(closed, i) {
    return 'A' + (i + 1) + (closed && i === closed.fictitiousRow - 1 ? 'ф' : '');
}

function trConsumerName(closed, j) {
    return 'B' + (j + 1) + (closed && j === closed.fictitiousColumn - 1 ? 'ф' : '');
}

function trCellName(closed, row, column) {
    return '(' + trSupplierName(closed, row - 1) + ',&nbsp;' + trConsumerName(closed, column - 1) + ')';
}

/* ============================ ввод ============================ */

function trFieldName(field, i, j) {
    if (field === 'costs') {
        return 'Тариф A' + (i + 1) + ' → B' + (j + 1);
    }
    return field === 'supply' ? 'Запас A' + (i + 1) : 'Потребность B' + (j + 1);
}

function trKey(field, i, j) {
    return field + ':' + (i === null ? '' : i) + ':' + (j === null ? '' : j);
}

function trInputHtml(field, i, j, value) {
    const mark = tr.photoMarks[trKey(field, i, j)];
    return '<input type="text" inputmode="numeric" autocomplete="off" autocorrect="off"'
        + ' spellcheck="false" maxlength="9" enterkeyhint="next"'
        + (mark ? ' class="photo-' + mark + '"' : '')
        + ' data-field="' + field + '"' + (i === null ? '' : ' data-i="' + i + '"') + (j === null ? '' : ' data-j="' + j + '"')
        + ' aria-label="' + trFieldName(field, i, j) + '"'
        + ' value="' + escapeHtml(value) + '">';
}

function trBuildSelects() {
    ['suppliers', 'consumers'].forEach(function (kind) {
        let html = '';
        for (let n = TR_MIN_SIZE; n <= TR_MAX_SIZE; n++) {
            html += '<option value="' + n + '">' + n + '</option>';
        }
        $('#tr-' + kind).innerHTML = html;
        $('#tr-' + kind).value = String(tr[kind]);
    });
}

function trBuildVariants() {
    let html = '<option value="">Вариант…</option>';
    for (let number = 1; number <= TR_VARIANTS; number++) {
        html += '<option value="' + number + '">Вариант ' + number + '</option>';
    }
    $('#tr-variant').innerHTML = html;
}

// таблица как в задании: тарифы, справа запасы, снизу потребности
function trBuildTable() {
    const m = tr.suppliers;
    const n = tr.consumers;
    let html = '<thead><tr><th class="corner" aria-label="поставщик \\ потребитель"></th>';
    for (let j = 0; j < n; j++) {
        html += '<th scope="col">B' + (j + 1) + '</th>';
    }
    html += '<th scope="col" class="side">Запас</th></tr></thead><tbody>';
    for (let i = 0; i < m; i++) {
        html += '<tr><th scope="row">A' + (i + 1) + '</th>';
        for (let j = 0; j < n; j++) {
            html += '<td>' + trInputHtml('costs', i, j, tr.input.costs[i][j]) + '</td>';
        }
        html += '<td class="side">' + trInputHtml('supply', i, null, tr.input.supply[i]) + '</td></tr>';
    }
    html += '<tr class="bottom"><th scope="row">Потр.</th>';
    for (let j = 0; j < n; j++) {
        html += '<td class="side">' + trInputHtml('demand', null, j, tr.input.demand[j]) + '</td>';
    }
    const table = $('#tr-table');
    table.innerHTML = html + '<td></td></tr></tbody>';
    // клетка не уже 40 px и не шире 72 px на большом экране; столбец запасов чуть шире
    table.style.minWidth = (44 + (n + 1) * 43) + 'px';
    table.style.maxWidth = (44 + (n + 1) * 75) + 'px';
    trRefreshValidation(false);
}

// разбор клетки: целое число; тариф от 0, запас и потребность от 1
function trParse(text, field) {
    const value = String(text).trim();
    if (value === '') {
        return {ok: false, empty: true, reason: 'не заполнено'};
    }
    if (/^\d+$/.test(value)) {
        const number = Number(value);
        if (field !== 'costs' && number < 1) {
            return {ok: false, reason: 'не меньше 1'};
        }
        if (number > TR_MAX_NUMBER) {
            return {ok: false, reason: 'не больше 1 000 000'};
        }
        return {ok: true, value: number};
    }
    if (/^-\s*\d/.test(value)) {
        return {ok: false, reason: 'не может быть меньше нуля'};
    }
    if (/^\d+[.,]\d*$/.test(value)) {
        return {ok: false, reason: 'только целые числа, без дробной части'};
    }
    return {ok: false, reason: 'только цифры'};
}

function trRead() {
    const data = {costs: [], supply: [], demand: []};
    const errors = [];

    function take(field, i, j, text) {
        const parsed = trParse(text, field);
        if (!parsed.ok) {
            errors.push({field: field, i: i, j: j, text: text, reason: parsed.reason, empty: !!parsed.empty});
        }
        return parsed.ok ? parsed.value : 0;
    }

    for (let i = 0; i < tr.suppliers; i++) {
        const row = [];
        for (let j = 0; j < tr.consumers; j++) {
            row.push(take('costs', i, j, tr.input.costs[i][j]));
        }
        data.costs.push(row);
        data.supply.push(take('supply', i, null, tr.input.supply[i]));
    }
    for (let j = 0; j < tr.consumers; j++) {
        data.demand.push(take('demand', null, j, tr.input.demand[j]));
    }
    return {data: data, errors: errors};
}

// подсветка неверных клеток; текст ошибок под таблицей - сразу или только по кнопке
function trRefreshValidation(showMessage) {
    const input = trRead();
    const visible = input.errors.filter(function (error) {
        return !error.empty || tr.checked;
    });
    const bad = {};
    visible.forEach(function (error) {
        bad[trKey(error.field, error.i, error.j)] = true;
    });
    document.querySelectorAll('#tr-table input[data-field]').forEach(function (element) {
        element.classList.toggle('invalid', !!bad[trElementKey(element)]);
    });

    const box = $('#tr-input-message');
    if (visible.length && (showMessage || !box.hidden)) {
        trShowErrors(visible);
    } else if (!visible.length && box.dataset.kind === 'cells') {
        trHideMessage();
    }
    trShowBalance(input);
    return input;
}

function trElementKey(element) {
    const i = element.dataset.i === undefined ? null : Number(element.dataset.i);
    const j = element.dataset.j === undefined ? null : Number(element.dataset.j);
    return trKey(element.dataset.field, i, j);
}

// «Запасы 550, потребности 560 - модель открытая», как только заполнены все запасы и потребности
function trShowBalance(input) {
    const unknown = input.errors.some(function (error) {
        return error.field !== 'costs';
    });
    const box = $('#tr-balance');
    if (unknown) {
        box.innerHTML = '';
        return;
    }
    const sum = function (total, value) {
        return total + value;
    };
    const supply = input.data.supply.reduce(sum, 0);
    const demand = input.data.demand.reduce(sum, 0);
    box.innerHTML = 'Запасы ' + trNumber(supply) + ', потребности ' + trNumber(demand) + ' - модель <b>'
        + (supply === demand ? 'закрытая' : 'открытая') + '</b>.';
}

function trShowErrors(errors) {
    const wrong = errors.filter(function (error) {
        return !error.empty;
    });
    const empty = errors.filter(function (error) {
        return error.empty;
    });
    const parts = wrong.slice(0, 4).map(function (error) {
        return trFieldName(error.field, error.i, error.j) + ' = «' + escapeHtml(String(error.text).trim()) + '»: '
            + error.reason;
    });
    if (wrong.length > 4) {
        parts.push('и ещё ' + (wrong.length - 4));
    }
    let text = 'Исправьте клетки, выделенные красным.';
    if (parts.length) {
        text += ' ' + parts.join('; ') + '.';
    }
    if (empty.length) {
        text += ' Не ' + plural(empty.length, 'заполнена', 'заполнены', 'заполнено') + ' ' + empty.length + ' '
            + plural(empty.length, 'клетка', 'клетки', 'клеток') + ', первая - '
            + trFieldName(empty[0].field, empty[0].i, empty[0].j) + '.';
    }
    trShowMessage(text, 'cells', true);
}

function trShowMessage(text, kind, isHtml) {
    const box = $('#tr-input-message');
    if (isHtml) {
        box.innerHTML = text;
    } else {
        box.textContent = text;
    }
    box.dataset.kind = kind;
    box.hidden = false;
}

function trHideMessage() {
    const box = $('#tr-input-message');
    box.hidden = true;
    box.dataset.kind = '';
}

function trOnInput(event) {
    const element = event.target;
    if (!element.matches('#tr-table input[data-field]')) {
        return;
    }
    const field = element.dataset.field;
    if (field === 'costs') {
        tr.input.costs[Number(element.dataset.i)][Number(element.dataset.j)] = element.value;
    } else if (field === 'supply') {
        tr.input.supply[Number(element.dataset.i)] = element.value;
    } else {
        tr.input.demand[Number(element.dataset.j)] = element.value;
    }
    // клетку поправили руками - подсветка «по фото» с неё снимается
    const key = trElementKey(element);
    if (tr.photoMarks[key]) {
        delete tr.photoMarks[key];
        element.classList.remove('photo-ok', 'photo-check', 'photo-missing');
    }
    // числа уже не из варианта
    $('#tr-variant').value = '';
    trHideUndo();
    trRefreshValidation(false);
    trUpdateStaleNote();
    trScheduleSave();
    trLog.debug('cell edited', {cell: key, value: element.value});
}

// Enter переводит к следующей клетке: строка за строкой вместе с запасом, потом потребности
function trOnKeydown(event) {
    if (event.key !== 'Enter' || !event.target.matches('#tr-table input[data-field]')) {
        return;
    }
    event.preventDefault();
    const inputs = Array.prototype.slice.call(document.querySelectorAll('#tr-table input[data-field]'));
    const next = inputs[inputs.indexOf(event.target) + 1];
    if (next) {
        next.focus();
    } else {
        event.target.blur();
    }
}

function trOnFocus(event) {
    // на сенсорном экране выделение вызвало бы меню «Вырезать / Копировать» при каждом касании
    const touch = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    if (!touch && event.target.matches('#tr-table input[data-field]')) {
        try {
            event.target.setSelectionRange(0, event.target.value.length);
        } catch (error) {
            trLog.debug('cannot select cell text', {error: error});
        }
    }
}

function trApplyInput(suppliers, consumers, input) {
    tr.suppliers = suppliers;
    tr.consumers = consumers;
    tr.input = input;
    trBuildSelects();
    trBuildTable();
    trUpdateStaleNote();
    trScheduleSave();
}

function trOnSizeChange() {
    const suppliers = Number($('#tr-suppliers').value);
    const consumers = Number($('#tr-consumers').value);
    trLog.info('size changed', {from: tr.suppliers + 'x' + tr.consumers, to: suppliers + 'x' + consumers});
    $('#tr-variant').value = '';
    trHideUndo();
    trApplyInput(suppliers, consumers, trResize(tr.input, suppliers, consumers));
}

// marks - подсветка клеток, подставленных по фото; без неё подсветка снимается
function trReplaceInput(suppliers, consumers, input, message, marks) {
    const before = {suppliers: tr.suppliers, consumers: tr.consumers, input: tr.input};
    tr.photoMarks = marks || {};
    trApplyInput(suppliers, consumers, input);
    trHideMessage();
    if (trHasAnyValue(before.input)) {
        tr.undo = before;
        $('#tr-undo-text').textContent = message;
        $('#tr-undo').hidden = false;
    } else {
        trHideUndo();
    }
}

function trHideUndo() {
    tr.undo = null;
    $('#tr-undo').hidden = true;
}

function trOnUndo() {
    if (!tr.undo) {
        return;
    }
    const before = tr.undo;
    trHideUndo();
    tr.photoMarks = {};
    $('#tr-variant').value = '';
    trApplyInput(before.suppliers, before.consumers, before.input);
    trLog.info('input restored after variant/clear/photo', {size: before.suppliers + 'x' + before.consumers});
}

function trOnClear() {
    tr.checked = false;
    $('#tr-variant').value = '';
    trReplaceInput(tr.suppliers, tr.consumers, trEmptyInput(tr.suppliers, tr.consumers), 'Таблица очищена.');
    trLog.info('input cleared', {size: tr.suppliers + 'x' + tr.consumers});
}

// вариант контрольной; все варианты приходят одним ответом и дальше берутся из памяти
async function trOnVariant() {
    const number = Number($('#tr-variant').value);
    if (!number) {
        return;
    }
    trLog.info('variant requested', {number: number, loaded: !!tr.variants});
    try {
        if (!tr.variants) {
            const response = await fetch('api/transport/example');
            const body = await response.json();
            if (!response.ok) {
                trLog.warn('variants rejected', {status: response.status, title: body.title, detail: body.detail});
                trShowMessage((body.title || 'Варианты не загрузились') + ': ' + (body.detail || response.status), 'request');
                return;
            }
            tr.variants = body.variants;
        }
        const variant = tr.variants[number - 1];
        const input = {
            costs: variant.costs.map(function (row) {
                return row.map(String);
            }),
            supply: variant.supply.map(String),
            demand: variant.demand.map(String)
        };
        trReplaceInput(variant.supply.length, variant.demand.length, input, 'Загружен вариант ' + number + '.');
        trLog.info('variant loaded', {number: number});
    } catch (error) {
        trLog.error('variant request failed', {number: number, error: error});
        trShowMessage('Вариант не загрузился: ' + (error && error.message ? error.message : error), 'request');
    }
}

// таблица по фото (photo.js): правый столбец - запасы, нижняя строка - потребности
function trApplyPhoto(answer) {
    const suppliers = answer.rows - 1;
    const consumers = answer.columns - 1;
    const input = trEmptyInput(suppliers, consumers);
    const marks = {};
    let missing = 0;
    for (let i = 0; i < answer.rows; i++) {
        for (let j = 0; j < answer.columns; j++) {
            const bottom = i === suppliers;
            const right = j === consumers;
            if (bottom && right) {
                continue;
            }
            const value = answer.values[i][j] === null ? '' : String(answer.values[i][j]);
            const flag = answer.flags[i][j];
            let key;
            if (bottom) {
                input.demand[j] = value;
                key = trKey('demand', null, j);
            } else if (right) {
                input.supply[i] = value;
                key = trKey('supply', i, null);
            } else {
                input.costs[i][j] = value;
                key = trKey('costs', i, j);
            }
            marks[key] = flag === 0 ? 'ok' : flag === 2 ? 'missing' : 'check';
            missing += flag === 2 ? 1 : 0;
        }
    }
    $('#tr-variant').value = '';
    trReplaceInput(suppliers, consumers, input, 'Подставлено по фото.', marks);
    trLog.info('table filled from photo', {scan: answer.scanId, size: suppliers + 'x' + consumers, missing: missing});
    const table = $('#tr-table');
    if (table.scrollIntoView) {
        table.scrollIntoView({behavior: 'smooth', block: 'center'});
    }
}

function trOnFictitious() {
    tr.fictitiousLast = $('input[name="tr-fictitious"]:checked').value === 'last';
    trLog.info('fictitious rule changed', {last: tr.fictitiousLast});
    trScheduleSave();
    // на экране уже есть решение этой же таблицы - сразу пересчитываем, ничего не листая
    const same = tr.result && JSON.parse(tr.resultInput).fictitiousLast !== tr.fictitiousLast
        && JSON.stringify(Object.assign(JSON.parse(tr.resultInput), {fictitiousLast: tr.fictitiousLast})) === trSnapshot();
    if (same) {
        trSolve(false);
    } else {
        trUpdateStaleNote();
    }
}

/* ============================ сохранение ввода ============================ */

function trScheduleSave() {
    clearTimeout(tr.saveTimer);
    tr.saveTimer = setTimeout(function () {
        if (writeStorage(TR_STORAGE_KEY, trSnapshot())) {
            trLog.debug('input saved', {size: tr.suppliers + 'x' + tr.consumers});
        }
    }, 300);
}

function trLoadInput() {
    const raw = readStorage(TR_STORAGE_KEY);
    if (!raw) {
        trLog.info('no saved input, starting with an empty table', {size: TR_DEFAULT_SUPPLIERS + 'x' + TR_DEFAULT_CONSUMERS});
        return null;
    }
    try {
        const saved = JSON.parse(raw);
        const m = Number(saved.suppliers);
        const n = Number(saved.consumers);
        const input = saved.input;
        const isRow = function (row, length) {
            return Array.isArray(row) && row.length === length;
        };
        const valid = m >= TR_MIN_SIZE && m <= TR_MAX_SIZE && n >= TR_MIN_SIZE && n <= TR_MAX_SIZE && input
            && isRow(input.costs, m) && input.costs.every(function (row) {
                return isRow(row, n);
            }) && isRow(input.supply, m) && isRow(input.demand, n);
        if (!valid) {
            trLog.warn('saved input has unexpected shape, ignoring it', {bytes: raw.length});
            return null;
        }
        const text = function (value) {
            return value === null || value === undefined ? '' : String(value);
        };
        trLog.info('saved input restored', {size: m + 'x' + n});
        return {
            suppliers: m,
            consumers: n,
            fictitiousLast: saved.fictitiousLast !== false,
            input: {
                costs: input.costs.map(function (row) {
                    return row.map(text);
                }),
                supply: input.supply.map(text),
                demand: input.demand.map(text)
            }
        };
    } catch (error) {
        trLog.warn('saved input is not valid JSON, ignoring it', {bytes: raw.length, error: error});
        return null;
    }
}

/* ============================ расчёт ============================ */

async function trSolve(scroll) {
    if (tr.busy) {
        trLog.debug('solve ignored: previous calculation is still running');
        return;
    }
    tr.checked = true;
    const input = trRefreshValidation(true);
    if (input.errors.length) {
        const first = input.errors[0];
        trLog.warn('solve blocked by invalid cells', {
            count: input.errors.length,
            first: trFieldName(first.field, first.i, first.j) + '=' + first.text
        });
        const element = $('#tr-table input[data-field="' + first.field + '"]'
            + (first.i === null ? '' : '[data-i="' + first.i + '"]') + (first.j === null ? '' : '[data-j="' + first.j + '"]'));
        if (element) {
            element.focus();
        }
        return;
    }
    trHideMessage();

    tr.runCounter += 1;
    const runId = 't' + Date.now().toString(36) + '-' + tr.runCounter;
    const startedAt = performance.now();
    const snapshot = trSnapshot();
    trSetBusy(true);
    trLog.info('solve request', {run: runId, size: tr.suppliers + 'x' + tr.consumers, fictitiousLast: tr.fictitiousLast});
    try {
        const response = await fetch('api/transport/solve', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                runId: runId,
                costs: input.data.costs,
                supply: input.data.supply,
                demand: input.data.demand,
                fictitiousLast: tr.fictitiousLast
            })
        });
        const body = await response.json();
        if (!response.ok) {
            trLog.warn('solve rejected', {run: runId, status: response.status, title: body.title, detail: body.detail});
            trShowMessage((body.title || 'Расчёт не выполнен') + ': ' + (body.detail || response.status), 'request');
            return;
        }
        // раскрытые шаги остаются раскрытыми при пересчёте с другим правилом
        const opened = scroll ? [] : trOpenedSteps();
        tr.result = body;
        tr.resultInput = snapshot;
        trRenderResult(body);
        opened.forEach(function (id) {
            const details = document.getElementById(id);
            if (details) {
                details.open = true;
            }
        });
        trLog.info('solve rendered', {
            run: runId,
            type: body.type,
            northWest: body.northWest.cost,
            minimumCost: body.minimumCost.cost,
            otherCosts: body.otherCosts.length,
            optimal: body.optimal.cost,
            coreMs: body.elapsedMs,
            totalMs: Math.round(performance.now() - startedAt)
        });
        const results = $('#tr-results');
        if (scroll && results.scrollIntoView) {
            results.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
    } catch (error) {
        trLog.error('solve failed', {run: runId, error: error});
        trShowMessage('Сбой расчёта: ' + (error && error.message ? error.message : error), 'request');
    } finally {
        trSetBusy(false);
    }
}

function trOpenedSteps() {
    return Array.prototype.map.call(document.querySelectorAll('#tr-results details[open]'), function (details) {
        return details.id;
    });
}

function trSetBusy(busy) {
    tr.busy = busy;
    const button = $('#tr-btn-solve');
    button.disabled = busy;
    button.textContent = busy ? 'Считаю…' : 'Рассчитать';
}

function trUpdateStaleNote() {
    const note = $('#tr-stale-note');
    if (note) {
        note.hidden = !tr.result || tr.resultInput === trSnapshot();
    }
}

/* ============================ вывод результата ============================ */

function trRenderPlaceholder() {
    $('#tr-results').innerHTML = '<div class="card"><div class="placeholder">'
        + 'Заполните таблицу или выберите вариант, затем «Рассчитать».'
        + '</div></div>';
}

// доступ по ключу пропал: решение убирается с экрана, введённая таблица остаётся
function trClearResult() {
    tr.result = null;
    tr.resultInput = null;
    tr.variants = null;
    trHideMessage();
    trRenderPlaceholder();
}

function trRenderResult(result) {
    const html = [];
    html.push('<div class="message warn" id="tr-stale-note" hidden>Данные изменились после расчёта - результат '
        + 'ниже относится к прошлым данным. Нажмите «Рассчитать».</div>');
    html.push('<nav class="chips" aria-label="Разделы результата">'
        + '<a href="#tr-sec-model" data-target="tr-sec-model">Модель</a>'
        + '<a href="#tr-sec-nw" data-target="tr-sec-nw">Северо-западный угол</a>'
        + '<a href="#tr-sec-min" data-target="tr-sec-min">Минимальный тариф</a>'
        + '<a href="#tr-sec-opt" data-target="tr-sec-opt">Оптимум</a>'
        + '</nav>');
    html.push(trModelCard(result));
    html.push(trNorthWestCard(result));
    html.push(trMinimumCostCard(result));
    html.push(trOptimalCard(result));
    html.push('<p class="footnote">Расчёт в ядре: ' + fmtMs(result.elapsedMs) + '</p>');
    $('#tr-results').innerHTML = html.join('');
}

/* ------------------------------------------------ модель */

function trModelCard(result) {
    const closed = result.closed;
    const supply = result.supply.map(trNumber).join(' + ');
    const demand = result.demand.map(trNumber).join(' + ');
    let html = '<section class="card" id="tr-sec-model"><h2>Математическая модель</h2><div class="card-body">'
        + '<h4 class="sub">Тип модели</h4>'
        + '<p>Запасы: ' + supply + ' = <b>' + trNumber(result.totalSupply) + '</b>.<br>'
        + 'Потребности: ' + demand + ' = <b>' + trNumber(result.totalDemand) + '</b>.</p>';
    if (result.type === 'closed') {
        html += '<p class="result-line">Запасы равны потребностям - модель <b>закрытая</b>, фиктивные поставщик или '
            + 'потребитель не нужны.</p>'
            + '<h4 class="sub">Закрытая модель</h4>'
            + trModel(result.costs, result.supply, result.demand, 'eq', 'eq');
        return html + '</div></section>';
    }
    const shortage = result.type === 'shortage';
    html += '<p class="result-line">Запасы ' + (shortage ? 'меньше' : 'больше') + ' потребностей на '
        + trNumber(closed.amount) + ' - модель <b>открытая</b>.</p>';

    html += '<h4 class="sub">Открытая модель</h4>'
        + trModel(result.costs, result.supply, result.demand, result.open.supplySign, result.open.demandSign)
        + '<p class="legend">' + (shortage
            ? 'Запасы вывозятся полностью (=), потребности закрываются не полностью (≤).'
            : 'Потребности закрываются полностью (=), запасы вывозятся не полностью (≤).') + '</p>';

    html += '<h4 class="sub">Закрытая модель</h4>'
        + '<p>' + (shortage
            ? 'Добавляется фиктивный поставщик <b>' + trSupplierName(closed, closed.fictitiousRow - 1) + '</b> с запасом '
            : 'Добавляется фиктивный потребитель <b>' + trConsumerName(closed, closed.fictitiousColumn - 1)
                + '</b> с потребностью ')
        + trNumber(closed.amount) + ', все его тарифы - нули.</p>'
        + trClosedTable(closed)
        + trModel(closed.costs, closed.supply, closed.demand, 'eq', 'eq');
    return html + '</div></section>';
}

// Z = 13x11 + 5x12 + ... -> min; ограничения по поставщикам и потребителям; x >= 0
function trModel(costs, supply, demand, supplySign, demandSign) {
    const m = supply.length;
    const n = demand.length;
    const wide = m > 9 || n > 9;
    const sign = {eq: '=', le: '≤'};
    const terms = [];
    for (let i = 0; i < m; i++) {
        for (let j = 0; j < n; j++) {
            const coefficient = costs[i][j] === 1 ? '' : trNumber(costs[i][j]);
            terms.push('<span class="term">' + coefficient + trVariable(i, j, wide) + '</span>');
        }
    }
    const sum = function (variables, relation, value) {
        return '<li>' + variables.join(' + ') + ' ' + sign[relation] + ' ' + trNumber(value) + '</li>';
    };
    const rows = supply.map(function (value, i) {
        return sum(demand.map(function (unused, j) {
            return '<span class="term">' + trVariable(i, j, wide) + '</span>';
        }), supplySign, value);
    });
    const columns = demand.map(function (value, j) {
        return sum(supply.map(function (unused, i) {
            return '<span class="term">' + trVariable(i, j, wide) + '</span>';
        }), demandSign, value);
    });
    return '<div class="model"><p class="objective"><i>Z</i> = ' + terms.join(' + ') + ' → min</p>'
        + '<p class="model-title">По запасам:</p><ul class="system">' + rows.join('') + '</ul>'
        + '<p class="model-title">По потребностям:</p><ul class="system">' + columns.join('') + '</ul>'
        + '<p class="nonnegative"><i>x</i><sub>ij</sub> ≥ 0, <i>i</i> = 1..' + m + ', <i>j</i> = 1..' + n + '</p></div>';
}

// таблица закрытой модели: тарифы, запасы, потребности
function trClosedTable(closed) {
    return trTable(closed, function (i, j) {
        return {text: trNumber(closed.costs[i][j]), cls: trIsFictitious(closed, i, j) ? 'fict' : ''};
    }, closed.supply, closed.demand, null);
}

function trIsFictitious(closed, i, j) {
    return i === closed.fictitiousRow - 1 || j === closed.fictitiousColumn - 1;
}

// общая таблица: cell(i, j) -> {text, cls}; right и bottom - остатки или запасы и потребности;
// crossed - {rows, columns} вычеркнутых строк и столбцов
function trTable(closed, cell, right, bottom, crossed, titles) {
    const n = bottom.length;
    let html = '<div class="table-scroll"><table class="data tr-plan"><thead><tr><th class="corner"></th>';
    for (let j = 0; j < n; j++) {
        const cls = (j === closed.fictitiousColumn - 1 ? 'fict' : '') + (crossed && crossed.columns[j] ? ' crossed' : '');
        html += '<th' + (cls ? ' class="' + cls.trim() + '"' : '') + '>' + trConsumerName(closed, j) + '</th>';
    }
    html += '<th class="side">' + (titles ? titles.right : 'Запас') + '</th></tr></thead><tbody>';
    right.forEach(function (value, i) {
        const cls = (i === closed.fictitiousRow - 1 ? 'fict' : '') + (crossed && crossed.rows[i] ? ' crossed' : '');
        html += '<tr><th' + (cls ? ' class="' + cls.trim() + '"' : '') + '>' + trSupplierName(closed, i) + '</th>';
        for (let j = 0; j < n; j++) {
            const content = cell(i, j);
            const off = crossed && (crossed.rows[i] || crossed.columns[j]) ? ' off' : '';
            html += '<td class="' + ((content.cls || '') + off).trim() + '">' + content.text + '</td>';
        }
        html += '<td class="side">' + trNumber(value) + '</td></tr>';
    });
    html += '<tr><th class="side">' + (titles ? titles.bottom : 'Потр.') + '</th>';
    bottom.forEach(function (value) {
        html += '<td class="side">' + trNumber(value) + '</td>';
    });
    return html + '<td class="side"></td></tr></tbody></table></div>';
}

/* ------------------------------------------------ планы */

// номер шага, на котором заполнена клетка: ① ... ⑳. Шагов не больше 20 (10 + 11 - 1 с фиктивным столбцом),
// дальше на всякий случай - число в скобках
function trStepMark(index) {
    return index >= 1 && index <= 20 ? String.fromCharCode(0x2460 + index - 1) : '(' + index + ')';
}

// верх клетки: слева бледно номер шага (если клетка уже заполнена), справа тариф
function trCellTop(tariff, stepIndex) {
    return '<span class="tariff">' + (stepIndex
        ? '<span class="step-no" title="шаг ' + stepIndex + '">' + trStepMark(stepIndex) + '</span>' : '')
        + trNumber(tariff) + '</span>';
}

// в какой клетке какой шаг: «строка:столбец» -> номер шага (у оптимума шагов нет - пусто)
function trStepOrder(plan) {
    const order = {};
    plan.steps.forEach(function (step) {
        order[(step.row - 1) + ':' + (step.column - 1)] = step.index;
    });
    return order;
}

// клетка плана: номер шага и тариф мелко сверху, перевозка крупно
function trPlanCell(closed, plan, i, j, order) {
    const filled = plan.filled[i][j];
    const amount = plan.amount[i][j];
    return {
        text: trCellTop(closed.costs[i][j], order[i + ':' + j]) + '<b>' + (filled ? trNumber(amount) : '&nbsp;') + '</b>',
        cls: (filled ? 'filled' : '') + (filled && amount === 0 ? ' basic-zero' : '')
            + (trIsFictitious(closed, i, j) ? ' fict' : '')
    };
}

function trPlanTable(result, plan) {
    const order = trStepOrder(plan);
    let html = trTable(result.closed, function (i, j) {
        return trPlanCell(result.closed, plan, i, j, order);
    }, result.closed.supply, result.closed.demand, null);
    if (plan.steps.length) {
        html += '<p class="legend">' + trStepMark(1) + ' ' + trStepMark(2) + ' ... - на каком шаге заполнена клетка.</p>';
    }
    return html;
}

// Z = 13·50 + 5·50 + ... = 5190; базисные нули в сумму не пишутся
function trTotalLine(result, plan) {
    const parts = [];
    plan.amount.forEach(function (row, i) {
        row.forEach(function (value, j) {
            if (value > 0) {
                parts.push('<span class="term">' + trNumber(result.closed.costs[i][j]) + '·' + trNumber(value) + '</span>');
            }
        });
    });
    return '<p class="result-line total"><i>Z</i> = ' + parts.join(' + ') + ' = <b>' + trNumber(plan.cost) + '</b></p>';
}

function trFilledLine(plan) {
    let text = '<p class="legend">Заполнено клеток: ' + plan.filledCount + ' = m + n - 1, план опорный.';
    if (plan.zeroCount) {
        text += ' Из них с перевозкой 0: ' + plan.zeroCount + ' - план вырожденный, базисный '
            + plural(plan.zeroCount, 'ноль', 'нули', 'нули') + ' выделен' + (plan.zeroCount === 1 ? '' : 'ы') + ' рамкой.';
    }
    return text + '</p>';
}

function trFictitiousLine(result, plan) {
    const closed = result.closed;
    if (result.type === 'closed') {
        return '';
    }
    const parts = [];
    if (closed.fictitiousRow) {
        const i = closed.fictitiousRow - 1;
        plan.amount[i].forEach(function (value, j) {
            if (value > 0) {
                parts.push(trConsumerName(closed, j) + ' недополучит ' + trNumber(value));
            }
        });
    } else {
        const j = closed.fictitiousColumn - 1;
        plan.amount.forEach(function (row, i) {
            if (row[j] > 0) {
                parts.push('у ' + trSupplierName(closed, i) + ' останется ' + trNumber(row[j]));
            }
        });
    }
    return parts.length ? '<p class="legend">Фиктивные перевозки: ' + parts.join(', ') + '.</p>' : '';
}

function trNorthWestCard(result) {
    const plan = result.northWest;
    return '<section class="card" id="tr-sec-nw"><h2>Метод северо-западного угла</h2><div class="card-body">'
        + '<p class="hint">Каждый раз заполняется левая верхняя из невычеркнутых клеток: в неё пишется наименьшее из '
        + 'остатка запаса и остатка потребности. Исчерпанная строка или столбец вычёркивается.</p>'
        + trPlanTable(result, plan)
        + trTotalLine(result, plan)
        + trFilledLine(plan)
        + trFictitiousLine(result, plan)
        + trSteps(result, plan, 'nw')
        + '</div></section>';
}

function trMinimumCostCard(result) {
    const plan = result.minimumCost;
    let rule = 'Каждый раз заполняется клетка с наименьшим тарифом из невычеркнутых. Если таких несколько - та, куда '
        + 'помещается больше груза, из равных - верхняя, затем левая.';
    if (result.type !== 'closed') {
        rule += result.fictitiousLast
            ? ' Фиктивные клетки берутся в последнюю очередь.'
            : ' Фиктивные клетки берутся по их тарифу 0, то есть первыми.';
    }
    let html = '<section class="card" id="tr-sec-min"><h2>Метод минимального тарифа</h2><div class="card-body">'
        + '<p class="hint">' + rule + '</p>'
        + trPlanTable(result, plan)
        + trTotalLine(result, plan)
        + trFilledLine(plan)
        + trFictitiousLine(result, plan);
    if (result.otherCosts.length) {
        const shown = result.otherCosts.slice(0, 30);
        html += '<div class="message note">Были клетки с одинаковым тарифом. Если взять другую из них, тоже по правилам '
            + 'метода выйдет <i>Z</i> = ' + shown.map(trNumber).join(', ')
            + (shown.length < result.otherCosts.length || result.otherCostsTruncated ? ' и другие' : '') + '.</div>';
    }
    return html + trSteps(result, plan, 'min') + '</div></section>';
}

function trOptimalCard(result) {
    const plan = result.optimal;
    const difference = function (cost) {
        return cost === plan.cost ? 'совпадает с оптимумом' : 'дороже на ' + trNumber(cost - plan.cost);
    };
    return '<section class="card" id="tr-sec-opt"><h2>Оптимальный план - для сверки с Excel</h2><div class="card-body">'
        + trPlanTable(result, plan)
        + trTotalLine(result, plan)
        + trFictitiousLine(result, plan)
        + '<p>' + (result.optimalUnique
            ? 'Оптимальный план единственный: в Excel должны получиться те же перевозки.'
            : 'Оптимальных планов несколько: в Excel перевозки могут быть другими, но <i>Z</i> = ' + trNumber(plan.cost) + '.')
        + '</p>'
        + '<h4 class="sub">Сравнение</h4>'
        + '<div class="table-scroll"><table class="data answer"><thead><tr><th>План</th><th>Z</th><th></th></tr></thead><tbody>'
        + '<tr><td>Северо-западный угол</td><td class="num">' + trNumber(result.northWest.cost) + '</td><td>'
        + difference(result.northWest.cost) + '</td></tr>'
        + '<tr><td>Минимальный тариф</td><td class="num">' + trNumber(result.minimumCost.cost) + '</td><td>'
        + difference(result.minimumCost.cost) + '</td></tr>'
        + '<tr><td>Оптимальный</td><td class="num">' + trNumber(plan.cost) + '</td><td></td></tr>'
        + '</tbody></table></div></div></section>';
}

/* ------------------------------------------------ шаги */

function trSteps(result, plan, id) {
    const count = plan.steps.length;
    let html = '<h4 class="sub">Шаги: ' + count + '</h4>'
        + '<div class="steps-toolbar"><button type="button" class="btn-link" data-steps="' + id + '">Раскрыть все</button></div>';
    // что уже лежит в клетках и на каком шаге туда попало
    const amounts = {};
    plan.steps.forEach(function (step, k) {
        const previous = k === 0 ? null : plan.steps[k - 1];
        amounts[(step.row - 1) + ':' + (step.column - 1)] = {amount: step.amount, index: step.index};
        html += trStep(result, step, previous, Object.assign({}, amounts), id);
    });
    return '<div class="tr-steps" id="tr-steps-' + id + '">' + html + '</div>';
}

function trStep(result, step, previous, amounts, id) {
    const closed = result.closed;
    const row = step.row - 1;
    const column = step.column - 1;
    const supplyBefore = (previous ? previous.supplyAfter : closed.supply)[row];
    const demandBefore = (previous ? previous.demandAfter : closed.demand)[column];
    const cell = trCellName(closed, step.row, step.column);
    const supplier = trSupplierName(closed, row);
    const consumer = trConsumerName(closed, column);

    let html = '<details class="step" id="tr-step-' + id + '-' + step.index + '"><summary>'
        + '<span class="step-title">Шаг ' + step.index + '</span> '
        + '<span class="step-pair">' + cell + '</span> '
        + '<span class="step-flow">' + trNumber(step.amount) + '</span></summary><div class="step-body">';

    if (id === 'min') {
        html += '<p>' + trChoiceText(result, step, previous, cell) + '</p>';
    } else {
        html += '<p>Левая верхняя из невычеркнутых клеток - <b>' + cell + '</b>.</p>';
    }
    html += '<p><i>x</i><sub>' + step.row + (result.suppliers > 8 || result.consumers > 8 ? ',' : '') + step.column + '</sub> = min('
        + trNumber(supplyBefore) + '; ' + trNumber(demandBefore) + ') = <b>' + trNumber(step.amount) + '</b>'
        + (step.amount === 0 ? ' - базисный ноль' : '') + '.</p>';

    const supplyLeft = step.supplyAfter[row];
    const demandLeft = step.demandAfter[column];
    if (step.crossed === 'both') {
        html += '<p>Запасы и потребности исчерпаны - план построен.</p>';
    } else if (step.degenerate) {
        html += '<p>Одновременно исчерпаны запас ' + supplier + ' и потребность ' + consumer + '. Вычёркивается только '
            + (step.crossed === 'row' ? 'строка ' + supplier + ', а в столбце ' + consumer : 'столбец ' + consumer
                + ', а в строке ' + supplier) + ' остаётся 0: позже туда запишется перевозка 0, чтобы заполненных клеток '
            + 'было m + n - 1.</p>';
    } else if (step.crossed === 'row') {
        html += '<p>Запас ' + supplier + ' исчерпан, строка вычёркивается. Потребности ' + consumer + ' осталось '
            + trNumber(demandLeft) + '.</p>';
    } else {
        html += '<p>Потребность ' + consumer + ' закрыта, столбец вычёркивается. Запаса ' + supplier + ' осталось '
            + trNumber(supplyLeft) + '.</p>';
    }

    html += trTable(closed, function (i, j) {
        const filled = amounts[i + ':' + j];
        const current = i === row && j === column;
        return {
            text: trCellTop(closed.costs[i][j], filled && filled.index) + '<b>'
                + (filled === undefined ? '&nbsp;' : trNumber(filled.amount)) + '</b>',
            cls: (filled === undefined ? '' : 'filled') + (current ? ' current' : '')
                + (trIsFictitious(closed, i, j) ? ' fict' : '')
        };
    }, step.supplyAfter, step.demandAfter, {rows: step.rowsCrossed, columns: step.columnsCrossed},
    {right: 'Ост.', bottom: 'Ост.'});
    return html + '</div></details>';
}

// почему на этом шаге взята именно эта клетка
function trChoiceText(result, step, previous, cell) {
    const closed = result.closed;
    const fictitious = trIsFictitious(closed, step.row - 1, step.column - 1);
    let text;
    if (fictitious && result.fictitiousLast) {
        text = 'Настоящие клетки закончились, остались фиктивные (тариф 0)';
    } else {
        text = 'Наименьший тариф из невычеркнутых - ' + trNumber(step.cost);
    }
    if (step.candidates.length === 1) {
        return text + ', клетка <b>' + cell + '</b>.';
    }
    const supply = previous ? previous.supplyAfter : closed.supply;
    const demand = previous ? previous.demandAfter : closed.demand;
    // по порядку: сверху вниз, слева направо; в скобках - сколько груза поместится
    const list = step.candidates.slice().sort(function (a, b) {
        return a[0] !== b[0] ? a[0] - b[0] : a[1] - b[1];
    }).map(function (candidate) {
        return trCellName(closed, candidate[0], candidate[1]) + ' - '
            + trNumber(Math.min(supply[candidate[0] - 1], demand[candidate[1] - 1]));
    });
    const same = step.candidates.filter(function (candidate) {
        return Math.min(supply[candidate[0] - 1], demand[candidate[1] - 1]) === step.amount;
    }).length;
    return text + '. Таких клеток ' + step.candidates.length + ' (сколько груза поместится): ' + list.join(', ')
        + '. Берётся <b>' + cell + '</b>: '
        + (same > 1 ? 'груза поместится больше всего, из равных она верхняя левая.' : 'туда поместится больше всего.');
}

/* ============================ события результата ============================ */

function trOnResultsClick(event) {
    const chip = event.target.closest('a[data-target]');
    if (chip) {
        event.preventDefault();
        const section = document.getElementById(chip.dataset.target);
        if (section) {
            section.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
        return;
    }
    const toggle = event.target.closest('button[data-steps]');
    if (toggle) {
        const steps = document.querySelectorAll('#tr-steps-' + toggle.dataset.steps + ' details.step');
        const open = Array.prototype.some.call(steps, function (details) {
            return !details.open;
        });
        steps.forEach(function (details) {
            details.open = open;
        });
        toggle.textContent = open ? 'Свернуть все' : 'Раскрыть все';
        trLog.debug('all steps toggled', {method: toggle.dataset.steps, open: open, count: steps.length});
    }
}

/* ============================ запуск ============================ */

function trInit() {
    trBuildVariants();
    const saved = trLoadInput();
    if (saved) {
        tr.fictitiousLast = saved.fictitiousLast;
        trApplyInput(saved.suppliers, saved.consumers, saved.input);
    } else {
        trApplyInput(TR_DEFAULT_SUPPLIERS, TR_DEFAULT_CONSUMERS,
            trEmptyInput(TR_DEFAULT_SUPPLIERS, TR_DEFAULT_CONSUMERS));
    }
    $('input[name="tr-fictitious"][value="' + (tr.fictitiousLast ? 'last' : 'tariff') + '"]').checked = true;
    trRenderPlaceholder();

    const panel = $('#tab-transport');
    panel.addEventListener('input', trOnInput);
    panel.addEventListener('keydown', trOnKeydown);
    panel.addEventListener('focusin', trOnFocus);
    $('#tr-suppliers').addEventListener('change', trOnSizeChange);
    $('#tr-consumers').addEventListener('change', trOnSizeChange);
    $('#tr-variant').addEventListener('change', trOnVariant);
    $('#tr-btn-clear').addEventListener('click', trOnClear);
    $('#tr-btn-undo').addEventListener('click', trOnUndo);
    $('#tr-btn-solve').addEventListener('click', function () {
        trSolve(true);
    });
    $('#tr-fictitious').addEventListener('change', trOnFictitious);
    $('#tr-results').addEventListener('click', trOnResultsClick);

    trLog.info('transport tab ready', {size: tr.suppliers + 'x' + tr.consumers, restored: !!saved});
}

trInit();
