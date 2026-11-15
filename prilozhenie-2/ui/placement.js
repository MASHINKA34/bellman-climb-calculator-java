'use strict';

/* ===========================================================================
   Вкладка «Размещение» - размещение производства венгерским методом
   (учебное пособие МЭСИ «Логистические модели», п. 3.2, пример 3.1).

   Весь расчёт выполняет ядро на Java (пакет placement). В приложении его зовёт
   мост android-bridge.js, на сайте - сервер по адресам api/placement/example
   и api/placement/solve. Здесь только ввод таблиц и вывод шагов решения.

   Подключается после app.js и берёт оттуда утилиты: createLogger, $, escapeHtml,
   plural, fmtMs, readStorage, writeStorage.
   Деньги из ядра приходят в рублях; общая прибыль и матрицы метода выводятся
   в тыс. руб., как в учебнике.
   Код рассчитан и на старый WebView из Android 10, поэтому без ?. и ??.
   =========================================================================== */

const PL_MIN_PRODUCTS = 2;
const PL_MAX_PRODUCTS = 10;
const PL_DEFAULT_PRODUCTS = 3;
const PL_MIN_PLANTS = 2;
const PL_MAX_PLANTS = 10;
const PL_DEFAULT_PLANTS = 5;
const PL_MAX_MONEY = 1000000;
const PL_MAX_VOLUME = 100000000;
const PL_STORAGE_KEY = 'multiflow.placement.v1';
const PL_GRID_FIELDS = ['production', 'sales'];
const PL_PLAN_FIELDS = ['volume', 'price'];
const PL_TITLES = {
    production: 'Производство',
    sales: 'Сбыт',
    volume: 'Объём',
    price: 'Цена'
};
const PL_PHOTO_TITLES = {
    production: 'издержки производства',
    sales: 'издержки сбыта',
    plan: 'план'
};

const plLog = createLogger('placement');

/* ============================ состояние ============================ */

const pl = {
    products: PL_DEFAULT_PRODUCTS,
    plants: PL_DEFAULT_PLANTS,
    // текст клеток как его ввёл человек
    input: plEmptyInput(PL_DEFAULT_PRODUCTS, PL_DEFAULT_PLANTS),
    // последний результат и ввод, по которому он получен
    result: null,
    resultInput: null,
    // ввод до нажатия «Пример» или «Очистить», чтобы вернуть одним нажатием
    undo: null,
    // клетки, подставленные по фото: ключ plErrorKey -> 'ok', 'check' или 'missing'
    photoMarks: {},
    busy: false,
    // после первой попытки расчёта пустые клетки подсвечиваются сразу, до неё - нет:
    // иначе пустая форма с самого начала была бы вся красной
    checked: false,
    runCounter: 0,
    saveTimer: 0
};

function plEmptyInput(products, plants) {
    const input = {production: [], sales: [], volume: [], price: []};
    for (let i = 0; i < products; i++) {
        input.production.push(new Array(plants).fill(''));
        input.sales.push(new Array(plants).fill(''));
        input.volume.push('');
        input.price.push('');
    }
    return input;
}

// новый размер; значения, которые в него помещаются, сохраняются
function plResize(input, products, plants) {
    const resized = plEmptyInput(products, plants);
    for (let i = 0; i < products; i++) {
        PL_GRID_FIELDS.forEach(function (field) {
            for (let j = 0; j < plants; j++) {
                if (input[field][i] !== undefined && input[field][i][j] !== undefined) {
                    resized[field][i][j] = input[field][i][j];
                }
            }
        });
        PL_PLAN_FIELDS.forEach(function (field) {
            if (input[field][i] !== undefined) {
                resized[field][i] = input[field][i];
            }
        });
    }
    return resized;
}

function plSnapshot() {
    return JSON.stringify({products: pl.products, plants: pl.plants, input: pl.input});
}

function plHasAnyValue(input) {
    const filled = function (text) {
        return String(text).trim() !== '';
    };
    return PL_GRID_FIELDS.some(function (field) {
        return input[field].some(function (row) {
            return row.some(filled);
        });
    }) || PL_PLAN_FIELDS.some(function (field) {
        return input[field].some(filled);
    });
}

/* ============================ числа ============================ */

// 1234567 -> «1 234 567»; короткие числа без пробелов, как в учебнике (16800)
function plGroup(value) {
    const digits = String(value);
    if (digits.length < 6) {
        return digits;
    }
    return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

// объём в тексте - всегда с пробелами по разрядам: 35000 -> «35 000»
function plVolume(value) {
    return String(value).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

// рубли -> тыс. руб.: 5235000 -> «5235», 52500 -> «52,5», -630000 -> «-630»
function plK(rubles) {
    const negative = rubles < 0;
    const abs = Math.abs(rubles);
    const whole = Math.floor(abs / 1000);
    const rest = abs % 1000;
    let text = plGroup(whole);
    if (rest) {
        text += ',' + String(rest).padStart(3, '0').replace(/0+$/, '');
    }
    return (negative ? '-' : '') + text;
}

function plRub(value) {
    return (value < 0 ? '-' : '') + plGroup(Math.abs(value));
}

/* ============================ ввод ============================ */

function plCellName(field, i, j) {
    let name = PL_TITLES[field] + ', вид ' + (i + 1);
    if (j !== null && j !== undefined) {
        name += ', предприятие ' + (j + 1);
    }
    return name;
}

function plInputHtml(field, i, j, value) {
    const mark = pl.photoMarks[plErrorKey(field, i, j)];
    return '<input type="text" inputmode="numeric" autocomplete="off" autocorrect="off"'
        + ' spellcheck="false" maxlength="9" enterkeyhint="next"'
        + (mark ? ' class="photo-' + mark + '"' : '')
        + ' data-field="' + field + '" data-i="' + i + '"' + (j === null ? '' : ' data-j="' + j + '"')
        + ' aria-label="' + escapeHtml(plCellName(field, i, j)) + '"'
        + ' value="' + escapeHtml(value) + '">';
}

function plBuildSelects() {
    let plants = '';
    for (let n = PL_MIN_PLANTS; n <= PL_MAX_PLANTS; n++) {
        plants += '<option value="' + n + '">' + n + '</option>';
    }
    $('#pl-plants').innerHTML = plants;
    $('#pl-plants').value = String(pl.plants);

    // видов не больше, чем предприятий: лишние варианты в списке не показываются
    let products = '';
    for (let m = PL_MIN_PRODUCTS; m <= Math.min(PL_MAX_PRODUCTS, pl.plants); m++) {
        products += '<option value="' + m + '">' + m + '</option>';
    }
    $('#pl-products').innerHTML = products;
    $('#pl-products').value = String(pl.products);
}

function plBuildGrid(field) {
    const m = pl.products;
    const n = pl.plants;
    let html = '<thead><tr><th class="corner" aria-label="вид \\ предприятие"></th>';
    for (let j = 0; j < n; j++) {
        html += '<th scope="col">' + (j + 1) + '</th>';
    }
    html += '</tr></thead><tbody>';
    for (let i = 0; i < m; i++) {
        html += '<tr><th scope="row">' + (i + 1) + '</th>';
        for (let j = 0; j < n; j++) {
            html += '<td>' + plInputHtml(field, i, j, pl.input[field][i][j]) + '</td>';
        }
        html += '</tr>';
    }
    const table = $('#pl-' + field);
    table.innerHTML = html + '</tbody>';
    // клетка не уже 44 px (до 7 цифр) и не шире 72 px на большом экране
    table.style.minWidth = (26 + n * 47) + 'px';
    table.style.maxWidth = (26 + n * 75) + 'px';
}

function plBuildPlan() {
    let html = '<thead><tr><th class="corner">Вид</th><th scope="col">Объём, ед.</th>'
        + '<th scope="col">Цена, руб.</th></tr></thead><tbody>';
    for (let i = 0; i < pl.products; i++) {
        html += '<tr><th scope="row">' + (i + 1) + '</th>'
            + '<td>' + plInputHtml('volume', i, null, pl.input.volume[i]) + '</td>'
            + '<td>' + plInputHtml('price', i, null, pl.input.price[i]) + '</td></tr>';
    }
    $('#pl-plan').innerHTML = html + '</tbody>';
}

function plBuildInputs() {
    plBuildGrid('production');
    plBuildGrid('sales');
    plBuildPlan();
    plRefreshValidation(false);
}

// разбор клетки: целое число; объём от 1, издержки и цена от 0
function plParse(text, field) {
    const value = String(text).trim();
    if (value === '') {
        return {ok: false, empty: true, reason: 'не заполнено'};
    }
    if (/^\d+$/.test(value)) {
        const number = Number(value);
        if (field === 'volume') {
            if (number < 1) {
                return {ok: false, reason: 'объём не меньше 1'};
            }
            if (number > PL_MAX_VOLUME) {
                return {ok: false, reason: 'не больше 100 000 000'};
            }
        } else if (number > PL_MAX_MONEY) {
            return {ok: false, reason: 'не больше 1 000 000'};
        }
        return {ok: true, value: number};
    }
    if (/^-\s*\d/.test(value)) {
        return {ok: false, reason: 'не может быть отрицательным'};
    }
    if (/^\d+[.,]\d*$/.test(value)) {
        return {ok: false, reason: 'только целые числа, без дробной части'};
    }
    return {ok: false, reason: 'только цифры'};
}

function plRead() {
    const data = {production: [], sales: [], volume: [], price: []};
    const errors = [];

    function take(field, i, j, text) {
        const parsed = plParse(text, field);
        if (!parsed.ok) {
            errors.push({field: field, i: i, j: j, text: text, reason: parsed.reason, empty: !!parsed.empty});
        }
        return parsed.ok ? parsed.value : 0;
    }

    PL_GRID_FIELDS.forEach(function (field) {
        for (let i = 0; i < pl.products; i++) {
            const row = [];
            for (let j = 0; j < pl.plants; j++) {
                row.push(take(field, i, j, pl.input[field][i][j]));
            }
            data[field].push(row);
        }
    });
    for (let i = 0; i < pl.products; i++) {
        PL_PLAN_FIELDS.forEach(function (field) {
            data[field].push(take(field, i, null, pl.input[field][i]));
        });
    }
    return {data: data, errors: errors};
}

function plErrorKey(field, i, j) {
    return field + ':' + i + ':' + (j === null || j === undefined ? '' : j);
}

function plVisibleErrors(errors) {
    return errors.filter(function (error) {
        return !error.empty || pl.checked;
    });
}

// подсветка неверных клеток; текст ошибок под таблицами - сразу или только по кнопке
function plRefreshValidation(showMessage) {
    const input = plRead();
    const visible = plVisibleErrors(input.errors);
    const bad = {};
    visible.forEach(function (error) {
        bad[plErrorKey(error.field, error.i, error.j)] = true;
    });
    document.querySelectorAll('#tab-placement input[data-field]').forEach(function (element) {
        const j = element.dataset.j === undefined ? null : element.dataset.j;
        element.classList.toggle('invalid', !!bad[plErrorKey(element.dataset.field, element.dataset.i, j)]);
    });

    const box = $('#pl-input-message');
    if (visible.length && (showMessage || !box.hidden)) {
        plShowErrors(visible);
    } else if (!visible.length && box.dataset.kind === 'cells') {
        plHideMessage();
    }
    return input;
}

function plShowErrors(errors) {
    const wrong = errors.filter(function (error) {
        return !error.empty;
    });
    const empty = errors.filter(function (error) {
        return error.empty;
    });
    const parts = wrong.slice(0, 4).map(function (error) {
        return plCellName(error.field, error.i, error.j) + ' = «' + escapeHtml(String(error.text).trim()) + '»: '
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
            + plCellName(empty[0].field, empty[0].i, empty[0].j) + '.';
    }
    plShowMessage(text, 'cells', true);
}

function plShowMessage(text, kind, isHtml) {
    const box = $('#pl-input-message');
    if (isHtml) {
        box.innerHTML = text;
    } else {
        box.textContent = text;
    }
    box.dataset.kind = kind;
    box.hidden = false;
}

function plHideMessage() {
    const box = $('#pl-input-message');
    box.hidden = true;
    box.dataset.kind = '';
}

function plOnInput(event) {
    const element = event.target;
    if (!element.matches('input[data-field]')) {
        return;
    }
    const field = element.dataset.field;
    const i = Number(element.dataset.i);
    const j = element.dataset.j === undefined ? null : Number(element.dataset.j);
    if (j === null) {
        pl.input[field][i] = element.value;
    } else {
        pl.input[field][i][j] = element.value;
    }
    // клетку поправили руками - подсветка «по фото» с неё снимается
    const key = plErrorKey(field, i, j);
    if (pl.photoMarks[key]) {
        delete pl.photoMarks[key];
        element.classList.remove('photo-ok', 'photo-check', 'photo-missing');
    }
    plHideUndo();
    plRefreshValidation(false);
    plUpdateStaleNote();
    plScheduleSave();
    plLog.debug('cell edited', {cell: plCellName(field, i, j), value: element.value});
}

// Enter переводит к следующей клетке: производство, сбыт, потом план - строка за строкой
function plOnKeydown(event) {
    if (event.key !== 'Enter' || !event.target.matches('input[data-field]')) {
        return;
    }
    event.preventDefault();
    const inputs = Array.prototype.slice.call(document.querySelectorAll('#tab-placement input[data-field]'));
    const next = inputs[inputs.indexOf(event.target) + 1];
    if (next) {
        next.focus();
    } else {
        event.target.blur();
    }
}

function plOnFocus(event) {
    // на сенсорном экране выделение вызвало бы меню «Вырезать / Копировать» при каждом касании
    const touch = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    if (!touch && event.target.matches('input[data-field]')) {
        try {
            event.target.setSelectionRange(0, event.target.value.length);
        } catch (error) {
            plLog.debug('cannot select cell text', {error: error});
        }
    }
}

function plApplyInput(products, plants, input) {
    pl.products = products;
    pl.plants = plants;
    pl.input = input;
    plBuildSelects();
    plBuildInputs();
    plUpdateStaleNote();
    plScheduleSave();
}

function plOnProductsChange() {
    const products = Number($('#pl-products').value);
    const previous = pl.products;
    plHideUndo();
    plApplyInput(products, pl.plants, plResize(pl.input, products, pl.plants));
    plLog.info('products changed', {from: previous, to: products, plants: pl.plants});
}

function plOnPlantsChange() {
    const plants = Number($('#pl-plants').value);
    const previous = pl.plants;
    // видов не может быть больше, чем предприятий
    const products = Math.min(pl.products, plants);
    if (products < pl.products) {
        plLog.info('products reduced to fit plants', {from: pl.products, to: products, plants: plants});
    }
    plHideUndo();
    plApplyInput(products, plants, plResize(pl.input, products, plants));
    plLog.info('plants changed', {from: previous, to: plants, products: products});
}

// marks - подсветка клеток, подставленных по фото; без неё подсветка снимается
function plReplaceInput(products, plants, input, message, marks) {
    const before = {products: pl.products, plants: pl.plants, input: pl.input};
    pl.photoMarks = marks || {};
    plApplyInput(products, plants, input);
    plHideMessage();
    if (plHasAnyValue(before.input)) {
        pl.undo = before;
        $('#pl-undo-text').textContent = message;
        $('#pl-undo').hidden = false;
    } else {
        plHideUndo();
    }
}

function plHideUndo() {
    pl.undo = null;
    $('#pl-undo').hidden = true;
}

function plOnUndo() {
    if (!pl.undo) {
        return;
    }
    const before = pl.undo;
    plHideUndo();
    pl.photoMarks = {};
    plApplyInput(before.products, before.plants, before.input);
    plLog.info('input restored after example/clear/photo', {products: before.products, plants: before.plants});
}

// таблица по фото (photo.js): издержки производства или сбыта задают число видов
// и предприятий, план - число видов (предприятий при нужде становится не меньше)
function plApplyPhoto(answer) {
    const field = answer.target;
    const rows = answer.rows;
    let products;
    let plants;
    if (field === 'plan') {
        products = rows;
        plants = Math.max(pl.plants, rows);
        if (plants > pl.plants) {
            plLog.info('plants increased to fit products from the plan photo', {from: pl.plants, to: plants});
        }
    } else {
        products = rows;
        plants = answer.columns;
    }
    const input = plResize(pl.input, products, plants);

    // подсветка других таблиц остаётся, этой - заменяется
    const marks = {};
    Object.keys(pl.photoMarks).forEach(function (key) {
        const owner = key.split(':')[0];
        const sameTable = field === 'plan' ? (owner === 'volume' || owner === 'price') : owner === field;
        if (!sameTable) {
            marks[key] = pl.photoMarks[key];
        }
    });

    let missing = 0;
    for (let i = 0; i < rows; i++) {
        for (let j = 0; j < answer.columns; j++) {
            const value = answer.values[i][j];
            const text = value === null ? '' : String(value);
            const flag = answer.flags[i][j];
            let key;
            if (field === 'plan') {
                const planField = j === 0 ? 'volume' : 'price';
                input[planField][i] = text;
                key = plErrorKey(planField, i, null);
            } else {
                input[field][i][j] = text;
                key = plErrorKey(field, i, j);
            }
            marks[key] = flag === 0 ? 'ok' : flag === 2 ? 'missing' : 'check';
            if (flag === 2) {
                missing++;
            }
        }
    }
    plReplaceInput(products, plants, input, 'Подставлено по фото: ' + PL_PHOTO_TITLES[field] + '.', marks);
    plLog.info('table filled from photo', {
        scan: answer.scanId,
        table: field,
        products: products,
        plants: plants,
        missing: missing
    });
    const table = $(field === 'plan' ? '#pl-plan' : '#pl-' + field);
    if (table && table.scrollIntoView) {
        table.scrollIntoView({behavior: 'smooth', block: 'center'});
    }
}

function plOnClear() {
    pl.checked = false;
    plReplaceInput(pl.products, pl.plants, plEmptyInput(pl.products, pl.plants), 'Таблицы очищены.');
    plLog.info('input cleared', {products: pl.products, plants: pl.plants});
}

async function plOnExample() {
    plLog.info('example requested');
    try {
        const response = await fetch('api/placement/example');
        const body = await response.json();
        if (!response.ok) {
            plLog.warn('example rejected', {status: response.status, title: body.title, detail: body.detail});
            plShowMessage((body.title || 'Пример не загрузился') + ': ' + (body.detail || response.status), 'request');
            return;
        }
        const text = function (value) {
            return String(value);
        };
        const input = {
            production: body.production.map(function (row) {
                return row.map(text);
            }),
            sales: body.sales.map(function (row) {
                return row.map(text);
            }),
            volume: body.volume.map(text),
            price: body.price.map(text)
        };
        plReplaceInput(body.products, body.plants, input, 'Загружен пример.');
        plLog.info('example loaded', {products: body.products, plants: body.plants});
    } catch (error) {
        plLog.error('example request failed', {error: error});
        plShowMessage('Пример не загрузился: ' + (error && error.message ? error.message : error), 'request');
    }
}

/* ============================ сохранение ввода ============================ */

function plScheduleSave() {
    clearTimeout(pl.saveTimer);
    pl.saveTimer = setTimeout(function () {
        if (writeStorage(PL_STORAGE_KEY, plSnapshot())) {
            plLog.debug('input saved', {products: pl.products, plants: pl.plants});
        }
    }, 300);
}

function plLoadInput() {
    const raw = readStorage(PL_STORAGE_KEY);
    if (!raw) {
        plLog.info('no saved input, starting with empty tables', {products: PL_DEFAULT_PRODUCTS, plants: PL_DEFAULT_PLANTS});
        return null;
    }
    try {
        const saved = JSON.parse(raw);
        const m = Number(saved.products);
        const n = Number(saved.plants);
        const input = saved.input;
        const isRow = function (row, length) {
            return Array.isArray(row) && row.length === length;
        };
        const valid = m >= PL_MIN_PRODUCTS && m <= PL_MAX_PRODUCTS && n >= PL_MIN_PLANTS && n <= PL_MAX_PLANTS
            && m <= n && input
            && PL_GRID_FIELDS.every(function (field) {
                return isRow(input[field], m) && input[field].every(function (row) {
                    return isRow(row, n);
                });
            })
            && PL_PLAN_FIELDS.every(function (field) {
                return isRow(input[field], m);
            });
        if (!valid) {
            plLog.warn('saved input has unexpected shape, ignoring it', {bytes: raw.length});
            return null;
        }
        const text = function (value) {
            return value === null || value === undefined ? '' : String(value);
        };
        const restored = {
            production: input.production.map(function (row) {
                return row.map(text);
            }),
            sales: input.sales.map(function (row) {
                return row.map(text);
            }),
            volume: input.volume.map(text),
            price: input.price.map(text)
        };
        plLog.info('saved input restored', {products: m, plants: n});
        return {products: m, plants: n, input: restored};
    } catch (error) {
        plLog.warn('saved input is not valid JSON, ignoring it', {bytes: raw.length, error: error});
        return null;
    }
}

/* ============================ расчёт ============================ */

async function plOnSolve() {
    if (pl.busy) {
        plLog.debug('solve ignored: previous calculation is still running');
        return;
    }
    pl.checked = true;
    const input = plRefreshValidation(true);
    if (input.errors.length) {
        const first = input.errors[0];
        plLog.warn('solve blocked by invalid cells', {
            count: input.errors.length,
            empty: input.errors.filter(function (error) {
                return error.empty;
            }).length,
            first: plCellName(first.field, first.i, first.j) + '=' + first.text
        });
        const selector = '#tab-placement input[data-field="' + first.field + '"][data-i="' + first.i + '"]'
            + (first.j === null ? ':not([data-j])' : '[data-j="' + first.j + '"]');
        const element = $(selector);
        if (element) {
            element.focus();
        }
        return;
    }
    plHideMessage();

    pl.runCounter += 1;
    const runId = 'p' + Date.now().toString(36) + '-' + pl.runCounter;
    const startedAt = performance.now();
    plSetBusy(true);
    plLog.info('solve request', {run: runId, products: pl.products, plants: pl.plants});
    try {
        const response = await fetch('api/placement/solve', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                runId: runId,
                production: input.data.production,
                sales: input.data.sales,
                volume: input.data.volume,
                price: input.data.price
            })
        });
        const body = await response.json();
        if (!response.ok) {
            plLog.warn('solve rejected', {run: runId, status: response.status, title: body.title, detail: body.detail});
            plShowMessage((body.title || 'Расчёт не выполнен') + ': ' + (body.detail || response.status), 'request');
            return;
        }
        pl.result = body;
        pl.resultInput = plSnapshot();
        plRenderResult(body);
        plLog.info('solve rendered', {
            run: runId,
            plan: body.placements.map(function (item) {
                return item.product + '->' + item.plant;
            }).join(' '),
            totalRub: body.totalProfit,
            iterations: body.iterations.length,
            alternatives: body.alternativeCount,
            coreMs: body.elapsedMs,
            totalMs: Math.round(performance.now() - startedAt)
        });
        const results = $('#pl-results');
        if (results.scrollIntoView) {
            results.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
    } catch (error) {
        plLog.error('solve failed', {run: runId, error: error});
        plShowMessage('Сбой расчёта: ' + (error && error.message ? error.message : error), 'request');
    } finally {
        plSetBusy(false);
    }
}

function plSetBusy(busy) {
    pl.busy = busy;
    const button = $('#pl-btn-solve');
    button.disabled = busy;
    button.textContent = busy ? 'Считаю…' : 'Рассчитать';
}

function plUpdateStaleNote() {
    const note = $('#pl-stale-note');
    if (note) {
        note.hidden = !pl.result || pl.resultInput === plSnapshot();
    }
}

/* ============================ вывод результата ============================ */

function plRenderPlaceholder() {
    $('#pl-results').innerHTML = '<div class="card"><div class="placeholder">'
        + 'Заполните таблицы или нажмите «Пример», затем «Рассчитать».'
        + '</div></div>';
}

function plRenderResult(result) {
    const html = [];
    html.push('<div class="message warn" id="pl-stale-note" hidden>Данные изменились после расчёта - результат '
        + 'ниже относится к прошлым данным. Нажмите «Рассчитать».</div>');
    html.push('<nav class="chips" aria-label="Разделы результата">'
        + '<a href="#pl-sec-profit" data-target="pl-sec-profit">Прибыль</a>'
        + '<a href="#pl-sec-cost" data-target="pl-sec-cost">Сведение к минимуму</a>'
        + '<a href="#pl-sec-method" data-target="pl-sec-method">Венгерский метод</a>'
        + '<a href="#pl-sec-answer" data-target="pl-sec-answer">Ответ</a>'
        + '</nav>');
    html.push(plProfitCard(result));
    html.push(plCostCard(result));
    html.push(plMethodCard(result));
    html.push(plAnswerCard(result));
    html.push('<p class="footnote">Расчёт в ядре: ' + fmtMs(result.elapsedMs) + '</p>');
    $('#pl-results').innerHTML = html.join('');
}

// подпись строки квадратной матрицы: настоящий вид или фиктивный («4ф»)
function plRowLabel(result, i) {
    return i < result.products ? String(i + 1) : (i + 1) + 'ф';
}

function plColumnLabel(j) {
    return String(j + 1);
}

// таблица чисел. options: rowLabel(i), cell(value, i, j) -> {text, cls}, rowClass(i),
// extraColumn {title, values}, extraRow {title, values}, className
function plTable(rows, options) {
    const columns = rows.length ? rows[0].length : 0;
    let html = '<div class="table-scroll"><table class="data pl-matrix' + (options.className ? ' ' + options.className : '')
        + '"><thead><tr><th class="corner">' + (options.corner || '') + '</th>';
    for (let j = 0; j < columns; j++) {
        html += '<th>' + plColumnLabel(j) + '</th>';
    }
    if (options.extraColumn) {
        html += '<th class="extra">' + options.extraColumn.title + '</th>';
    }
    html += '</tr></thead><tbody>';
    rows.forEach(function (row, i) {
        const rowClass = options.rowClass ? options.rowClass(i) : '';
        html += '<tr><th' + (rowClass ? ' class="' + rowClass + '"' : '') + '>' + options.rowLabel(i) + '</th>';
        row.forEach(function (value, j) {
            const cell = options.cell(value, i, j);
            html += '<td' + (cell.cls ? ' class="' + cell.cls + '"' : '') + '>' + cell.text + '</td>';
        });
        if (options.extraColumn) {
            html += '<td class="extra">' + options.extraColumn.values[i] + '</td>';
        }
        html += '</tr>';
    });
    if (options.extraRow) {
        html += '<tr class="extra-row"><th>' + options.extraRow.title + '</th>';
        options.extraRow.values.forEach(function (value) {
            html += '<td class="extra">' + value + '</td>';
        });
        if (options.extraColumn) {
            html += '<td></td>';
        }
        html += '</tr>';
    }
    return html + '</tbody></table></div>';
}

function plProfitCard(result) {
    const productRow = function (i) {
        return String(i + 1);
    };
    let best = null;
    result.profit.forEach(function (row) {
        row.forEach(function (value) {
            best = best === null ? value : Math.max(best, value);
        });
    });

    const prices = result.price.map(function (price, i) {
        return 'вид ' + (i + 1) + ' - ' + plRub(price);
    }).join(', ');
    const volumes = result.volume.map(function (volume, i) {
        return 'вид ' + (i + 1) + ' - ' + plVolume(volume);
    }).join(', ');

    return '<section class="card" id="pl-sec-profit"><h2>Прибыль по видам и предприятиям</h2><div class="card-body">'
        + '<p class="hint">Строки - виды продукции, столбцы - предприятия.</p>'
        + '<h4 class="sub">Общие издержки на единицу, руб.</h4>'
        + plTable(result.unitCost, {
            rowLabel: productRow,
            cell: function (value) {
                return {text: plRub(value)};
            }
        })
        + '<p class="legend">Издержки производства + издержки сбыта.</p>'
        + '<h4 class="sub">Прибыль на единицу, руб.</h4>'
        + plTable(result.unitProfit, {
            rowLabel: productRow,
            cell: function (value) {
                return {text: plRub(value), cls: value < 0 ? 'negative' : ''};
            }
        })
        + '<p class="legend">Плановая цена - общие издержки. Цена: ' + prices + '.</p>'
        + '<h4 class="sub">Общая годовая прибыль, тыс. руб.</h4>'
        + plTable(result.profit, {
            rowLabel: productRow,
            cell: function (value) {
                return {text: plK(value), cls: value === best ? 'best' : value < 0 ? 'negative' : ''};
            }
        })
        + '<p class="legend">Прибыль на единицу × плановый объём / 1000. Объём, ед.: ' + volumes
        + '. Выделена наибольшая прибыль - ' + plK(best) + '.</p>'
        + '</div></section>';
}

function plCostCard(result) {
    const fictitious = [];
    for (let i = result.products; i < result.plants; i++) {
        fictitious.push(plRowLabel(result, i));
    }
    let html = '<section class="card" id="pl-sec-cost"><h2>Сведение к минимуму</h2><div class="card-body">'
        + '<p>Венгерский метод ищет наименьшую сумму, а нужна наибольшая прибыль. Поэтому каждая прибыль '
        + 'умножается на -1, и к ней прибавляется наибольшая прибыль <b>' + plK(result.maxProfit) + '</b>: '
        + 'клетка = ' + plK(result.maxProfit) + ' - прибыль. Отрицательных чисел в матрице не остаётся.</p>';
    if (fictitious.length) {
        html += '<p>Видов (' + result.products + ') меньше, чем предприятий (' + result.plants + '), а матрица '
            + 'должна быть квадратной. Поэтому ' + plural(fictitious.length, 'добавлен фиктивный вид', 'добавлены фиктивные виды',
                'добавлены фиктивные виды') + ' ' + fictitious.join(', ') + ' с нулевой прибылью - в матрице это '
            + 'нулевые строки. Предприятие, которому достанется фиктивный вид, новую продукцию не производит.</p>';
    }
    html += '<h4 class="sub">Матрица для венгерского метода, тыс. руб.</h4>'
        + plTable(result.cost, {
            rowLabel: function (i) {
                return plRowLabel(result, i);
            },
            rowClass: function (i) {
                return i >= result.products ? 'fict' : '';
            },
            cell: function (value, i) {
                return {text: plK(value), cls: i >= result.products ? 'fict' : ''};
            }
        });
    return html + '</div></section>';
}

function plMethodCard(result) {
    const count = result.iterations.length;
    let html = '<section class="card" id="pl-sec-method"><h2>Венгерский метод: ' + count + ' '
        + plural(count, 'итерация', 'итерации', 'итераций') + '</h2><div class="card-body">'
        + '<p class="hint">Шаг 1 - из каждой строки вычитается её минимум, затем из каждого столбца - его. '
        + 'Шаг 2 - назначения по нулям: а) строки, где ровно один невычеркнутый ноль, б) такие же столбцы; '
        + 'назначение вычёркивает остальные нули своей строки и столбца. Если назначение полное - оно оптимальное. '
        + 'Иначе шаг 3: нули закрываются линиями, из незакрытых клеток вычитается их минимум, на пересечениях '
        + 'линий прибавляется, и снова шаг 2. Все числа - тыс. руб.</p>';
    html += result.iterations.map(function (iteration) {
        return plIteration(iteration, result);
    }).join('');
    return html + '</div></section>';
}

function plIteration(iteration, result) {
    const n = result.plants;
    const assignment = iteration.assignment;
    const label = function (i) {
        return plRowLabel(result, i);
    };
    const rowClass = function (i) {
        return i >= result.products ? 'fict' : '';
    };
    const status = assignment.complete
        ? 'назначение полное'
        : 'назначено ' + assignment.picks.length + ' из ' + n;

    let html = '<details class="step" id="pl-iteration-' + iteration.index + '" open><summary>'
        + '<span class="step-title">Итерация ' + iteration.index + '</span> '
        + '<span class="step-flow">' + status + '</span>'
        + '</summary><div class="step-body">';

    // шаг 1 - только на первой итерации
    const reduction = iteration.reduction;
    if (reduction) {
        html += '<h4>Шаг 1. Редукция строк и столбцов</h4>'
            + '<p>Из каждой строки вычитается её минимум (столбец «min»):</p>'
            + plTable(reduction.afterRows, {
                rowLabel: label,
                rowClass: rowClass,
                cell: function (value) {
                    return {text: plK(value), cls: value === 0 ? 'zero-cell' : ''};
                },
                extraColumn: {title: 'min', values: reduction.rowMin.map(plK)}
            });
        const columnsReduced = reduction.columnMin.some(function (value) {
            return value !== 0;
        });
        if (columnsReduced) {
            html += '<p>Из каждого столбца вычитается его минимум (строка «min»):</p>'
                + plTable(reduction.afterColumns, {
                    rowLabel: label,
                    rowClass: rowClass,
                    cell: function (value) {
                        return {text: plK(value), cls: value === 0 ? 'zero-cell' : ''};
                    },
                    extraRow: {title: 'min', values: reduction.columnMin.map(plK)}
                });
        } else {
            html += '<p>Минимумы столбцов - нули: в каждом столбце уже есть ноль, матрица не меняется.</p>';
        }
    }

    // шаг 2
    html += '<h4>Шаг 2. Назначения</h4>'
        + plTable(assignment.matrix, {
            rowLabel: label,
            rowClass: rowClass,
            cell: function (value, i, j) {
                const mark = assignment.marks[i][j];
                const cls = mark === 1 ? 'assigned' : mark === 2 ? 'assigned chosen' : mark === 3 ? 'crossed' : '';
                return {text: plK(value), cls: cls};
            }
        })
        + '<p class="legend"><span class="mark-assigned">0</span> - назначение, '
        + '<span class="mark-chosen">0</span> - назначение на выбор, <span class="mark-crossed">0</span> - '
        + 'вычеркнутый ноль.</p>'
        + '<ol class="picks">' + assignment.picks.map(function (pick) {
            const cell = '(' + label(pick.row - 1) + ',' + pick.column + ')';
            let why;
            if (pick.rule === 'row') {
                why = 'в строке ' + label(pick.row - 1) + ' один невычеркнутый ноль';
            } else if (pick.rule === 'column') {
                why = 'в столбце ' + pick.column + ' один невычеркнутый ноль';
            } else {
                why = 'выбран: в каждой оставшейся строке по два нуля и больше';
            }
            return '<li><b>' + cell + '</b> - ' + why + '</li>';
        }).join('') + '</ol>';

    if (assignment.complete && !assignment.completedBySearch) {
        html += '<p class="result-line">Назначено ' + n + ' из ' + n + ' - назначение полное, значит оптимальное.</p>';
    } else if (assignment.completedBySearch) {
        const cells = assignment.columns.map(function (column, i) {
            return '(' + label(i) + ',' + (column + 1) + ')';
        });
        html += '<div class="message warn">По правилам а) и б) назначено ' + assignment.picks.length + ' из ' + n
            + ', но полное назначение по нулям этой матрицы есть - оно найдено перебором нулей: '
            + cells.join(', ') + '. Оно и оптимальное.</div>';
    } else {
        html += '<p class="result-line">Назначено ' + assignment.picks.length + ' из ' + n
            + ' - назначение неполное, переходим к шагу 3.</p>';
    }

    // шаг 3
    const cover = iteration.cover;
    if (cover) {
        html += '<h4>Шаг 3. Вычёркивание нулей</h4>';
        if (cover.replaced) {
            html += '<div class="message warn">По правилу «строка или столбец с наибольшим числом нулей» вышло '
                + cover.ruleLineCount + ' ' + plural(cover.ruleLineCount, 'линия', 'линии', 'линий')
                + ', а все нули закрываются ' + cover.lines.length + ' '
                + plural(cover.lines.length, 'линией', 'линиями', 'линиями')
                + ' - столько, сколько можно сделать назначений. Взято наименьшее число линий: с лишней линией '
                + 'метод может ходить по кругу.</div>';
        } else {
            html += '<p>Каждый раз вычёркивается строка или столбец с наибольшим числом ещё не закрытых нулей.</p>';
        }
        html += plTable(assignment.matrix, {
            rowLabel: label,
            rowClass: rowClass,
            cell: function (value, i, j) {
                const row = cover.rows[i];
                const column = cover.columns[j];
                const cls = row && column ? 'line-both' : row ? 'line-row' : column ? 'line-col' : '';
                return {text: plK(value), cls: cls + (value === 0 ? ' zero-cell' : '')};
            }
        });
        html += '<p>Линии: ' + cover.lines.map(function (line) {
            const name = line.type === 'row' ? 'строка ' + label(line.index - 1) : 'столбец ' + line.index;
            return name + ' (' + line.zeros + ' ' + plural(line.zeros, 'ноль', 'нуля', 'нулей') + ')';
        }).join(', ') + '.</p>';

        html += '<h4>Модификация</h4>'
            + '<p>Минимальный незакрытый элемент - <b>' + plK(cover.minimum) + '</b>. Он вычитается из всех '
            + 'незакрытых клеток и прибавляется к клеткам на пересечении двух линий:</p>'
            + plTable(cover.after, {
                rowLabel: label,
                rowClass: rowClass,
                cell: function (value, i, j) {
                    const row = cover.rows[i];
                    const column = cover.columns[j];
                    const cls = !row && !column ? 'minus' : row && column ? 'plus' : '';
                    return {text: plK(value), cls: cls + (value === 0 ? ' zero-cell' : '')};
                }
            })
            + '<p class="legend">Выделены изменённые клетки: обычные - минус ' + plK(cover.minimum)
            + ', на пересечениях линий - плюс ' + plK(cover.minimum) + '. Дальше - снова шаг 2.</p>';
    }
    return html + '</div></details>';
}

function plAnswerCard(result) {
    let html = '<section class="card" id="pl-sec-answer"><h2>Ответ</h2><div class="card-body">'
        + '<div class="table-scroll"><table class="data answer"><thead><tr>'
        + '<th>Вид</th><th>Предприятие</th><th>Прибыль, тыс. руб.</th></tr></thead><tbody>';
    result.placements.forEach(function (item) {
        html += '<tr><td>' + item.product + '</td><td>' + item.plant + '</td><td class="num'
            + (item.profit < 0 ? ' negative' : '') + '">' + plK(item.profit) + '</td></tr>';
    });
    html += '</tbody><tfoot><tr><th colspan="2">Итого</th><th class="num">' + plK(result.totalProfit)
        + '</th></tr></tfoot></table></div>';

    html += '<p class="result-line">Наибольшая суммарная годовая прибыль - <b>' + plK(result.totalProfit)
        + ' тыс. руб.</b></p>';

    if (result.fictitious) {
        const idle = [];
        for (let i = result.products; i < result.plants; i++) {
            idle.push(result.columns[i] + 1);
        }
        idle.sort(function (a, b) {
            return a - b;
        });
        html += '<p>' + (idle.length === 1 ? 'Предприятие ' : 'Предприятия ') + idle.join(', ')
            + (idle.length === 1 ? ' новую продукцию не производит - ему' : ' новую продукцию не производят - им')
            + ' достались фиктивные виды.</p>';
    }
    if (result.placements.some(function (item) {
        return item.profit < 0;
    })) {
        html += '<div class="message warn">Часть продукции убыточна даже на лучшем для неё предприятии, но по условию '
            + 'каждый вид должен быть размещён.</div>';
    }

    html += '<h4 class="sub">Размещение на таблице прибыли, тыс. руб.</h4>'
        + plTable(result.profit, {
            rowLabel: function (i) {
                return String(i + 1);
            },
            cell: function (value, i, j) {
                const picked = result.columns[i] === j;
                return {text: plK(value), cls: picked ? 'picked' : value < 0 ? 'negative' : ''};
            }
        });

    if (result.alternativeCount > 0) {
        const plans = result.alternatives.map(function (plan) {
            return '<li>' + plan.map(function (plant, i) {
                return (i + 1) + ' → ' + plant;
            }).join(', ') + '</li>';
        }).join('');
        const more = result.alternativesTruncated
            ? 'Их больше ' + plGroup(result.alternativeCount) + ' - перебор остановлен. '
            : 'Всего ' + plGroup(result.alternativeCount) + '. ';
        html += '<h4 class="sub">Другие размещения с такой же прибылью</h4>'
            + '<p class="legend">' + more + (result.alternatives.length < result.alternativeCount
                ? 'Показаны первые ' + result.alternatives.length + '. ' : '')
            + 'Запись «вид → предприятие».</p><ul class="alternatives">' + plans + '</ul>';
    } else {
        html += '<p class="muted">Других размещений с такой же прибылью нет.</p>';
    }
    return html + '</div></section>';
}

/* ============================ события результата ============================ */

function plOnResultsClick(event) {
    const chip = event.target.closest('a[data-target]');
    if (chip) {
        event.preventDefault();
        const section = document.getElementById(chip.dataset.target);
        if (section) {
            section.scrollIntoView({behavior: 'smooth', block: 'start'});
        }
    }
}

/* ============================ запуск ============================ */

function plInit() {
    const saved = plLoadInput();
    if (saved) {
        plApplyInput(saved.products, saved.plants, saved.input);
    } else {
        plApplyInput(PL_DEFAULT_PRODUCTS, PL_DEFAULT_PLANTS, plEmptyInput(PL_DEFAULT_PRODUCTS, PL_DEFAULT_PLANTS));
    }
    plRenderPlaceholder();

    const panel = $('#tab-placement');
    panel.addEventListener('input', plOnInput);
    panel.addEventListener('keydown', plOnKeydown);
    panel.addEventListener('focusin', plOnFocus);
    $('#pl-products').addEventListener('change', plOnProductsChange);
    $('#pl-plants').addEventListener('change', plOnPlantsChange);
    $('#pl-btn-example').addEventListener('click', plOnExample);
    $('#pl-btn-clear').addEventListener('click', plOnClear);
    $('#pl-btn-undo').addEventListener('click', plOnUndo);
    $('#pl-btn-solve').addEventListener('click', plOnSolve);
    $('#pl-results').addEventListener('click', plOnResultsClick);

    plLog.info('placement tab ready', {products: pl.products, plants: pl.plants, restored: !!saved});
}

plInit();
