'use strict';

/* ===========================================================================
   Таблица по фото - для обеих вкладок.

   Кнопка «По фото» -> выбор: камера или галерея -> снимок распознаётся на телефоне
   (ML Kit, модель внутри приложения) -> ядро собирает из слов таблицу ->
   предпросмотр: фото и распознанная таблица -> «Подставить».

   Снимать и распознавать умеет только приложение (мост MultiFlowBridge.scanPhoto),
   поэтому в обычном браузере кнопок «По фото» нет. Ответ приходит позже
   в window.onPhotoScan - его вызывает MainActivity.

   Подключается после app.js и placement.js и берёт оттуда утилиты и функции
   подстановки: applyFlowPhoto (app.js) и plApplyPhoto (placement.js).
   Код рассчитан и на старый WebView из Android 10, поэтому без ?. и ??.
   =========================================================================== */

const photoLog = createLogger('photo');

const PHOTO_TITLES = {
    flow: 'Матрица сети',
    production: 'Издержки производства',
    sales: 'Издержки сбыта',
    plan: 'План по видам продукции'
};

// отметки клеток из ядра (PhotoResult)
const PHOTO_OK = 0;
const PHOTO_CHECK = 1;
const PHOTO_MISSING = 2;
const PHOTO_MIRROR = 3;
const PHOTO_CONFLICT = 4;

const photo = {
    supported: false,
    counter: 0,
    // для какой таблицы открыто окно
    target: null,
    // съёмка, ответа на которую ждём: {scanId, target, source, startedAt}
    waiting: null,
    // съёмка, которую отменили кнопкой, - её поздний ответ не показываем
    dismissed: null,
    // последний распознанный ответ - его подставляет кнопка «Подставить»
    answer: null
};

/* ============================ запуск ============================ */

function photoInit() {
    try {
        photo.supported = typeof MultiFlowBridge !== 'undefined'
            && typeof MultiFlowBridge.photoSupported === 'function'
            && MultiFlowBridge.photoSupported() === 'yes';
    } catch (error) {
        photoLog.warn('photo support check failed, photo buttons stay hidden', {error: error});
        photo.supported = false;
    }
    document.documentElement.classList.toggle('has-photo', photo.supported);

    const sheet = document.createElement('div');
    sheet.className = 'sheet';
    sheet.id = 'photo-sheet';
    sheet.hidden = true;
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    sheet.setAttribute('aria-labelledby', 'photo-title');
    sheet.innerHTML = '<div class="sheet-panel">'
        + '<div class="sheet-head"><h2 id="photo-title"></h2>'
        + '<button type="button" class="btn-link" data-photo-action="close">Закрыть</button></div>'
        + '<div class="sheet-body" id="photo-body"></div></div>';
    document.body.appendChild(sheet);

    document.addEventListener('click', onPhotoClick);
    photoLog.info('photo ready', {supported: photo.supported});
}

/* ============================ окно ============================ */

function photoOpen(target) {
    photo.target = target;
    photo.answer = null;
    $('#photo-title').textContent = 'По фото: ' + PHOTO_TITLES[target].toLowerCase();
    $('#photo-sheet').hidden = false;
    document.body.classList.add('sheet-open');
    renderPhotoChoice();
    photoLog.info('photo sheet opened', {target: target});
}

function photoClose(reason) {
    $('#photo-sheet').hidden = true;
    document.body.classList.remove('sheet-open');
    if (photo.waiting) {
        photo.dismissed = photo.waiting.scanId;
        photoLog.info('photo sheet closed while waiting, the answer will be ignored', {
            scan: photo.waiting.scanId,
            reason: reason
        });
    }
    photo.waiting = null;
    photo.answer = null;
    photoLog.info('photo sheet closed', {target: photo.target, reason: reason});
}

function renderPhotoChoice() {
    $('#photo-body').innerHTML = '<p class="hint">Снимайте ровно, сверху: вся таблица в кадре, крупно, '
        + 'без бликов и тени. Цифры распознаются на телефоне, интернет не нужен. Перед подстановкой '
        + 'покажу, что получилось.</p>'
        + '<div class="photo-sources">'
        + '<button type="button" class="btn-ghost" data-photo-action="camera">Камера</button>'
        + '<button type="button" class="btn-ghost" data-photo-action="gallery">Галерея</button>'
        + '</div>';
}

function renderPhotoWaiting(source) {
    $('#photo-body').innerHTML = '<p class="photo-wait">' + (source === 'camera'
        ? 'Сделайте снимок таблицы - потом здесь появится то, что удалось распознать.'
        : 'Выберите фото таблицы - потом здесь появится то, что удалось распознать.')
        + '</p><p class="hint center">Распознавание занимает несколько секунд.</p>'
        + '<div class="photo-actions single"><button type="button" class="btn-ghost" data-photo-action="close">'
        + 'Отмена</button></div>';
}

function renderPhotoError(message) {
    $('#photo-body').innerHTML = '<div class="message error">' + escapeHtml(message) + '</div>'
        + '<div class="photo-actions">'
        + '<button type="button" class="btn-primary" data-photo-action="retry">Переснять</button>'
        + '<button type="button" class="btn-ghost" data-photo-action="close">Закрыть</button>'
        + '</div>';
}

/* ============================ съёмка ============================ */

function photoStart(source) {
    if (!photo.supported) {
        renderPhotoError('Распознавание по фото работает только в приложении на телефоне.');
        return;
    }
    photo.counter += 1;
    const scanId = 's' + Date.now().toString(36) + '-' + photo.counter;
    photo.waiting = {scanId: scanId, target: photo.target, source: source, startedAt: Date.now()};
    renderPhotoWaiting(source);
    photoLog.info('photo requested', {scan: scanId, target: photo.target, source: source});
    try {
        const raw = MultiFlowBridge.scanPhoto(JSON.stringify({scanId: scanId, target: photo.target, source: source}));
        const envelope = JSON.parse(raw);
        if (!envelope.ok) {
            photoLog.warn('photo request rejected', {scan: scanId, status: envelope.status, body: envelope.body});
            photo.waiting = null;
            renderPhotoError((envelope.body.title || 'Не получилось открыть ' + (source === 'camera' ? 'камеру' : 'галерею'))
                + ': ' + (envelope.body.detail || envelope.status));
        }
    } catch (error) {
        photoLog.error('photo request failed', {scan: scanId, error: error});
        photo.waiting = null;
        renderPhotoError('Не получилось открыть ' + (source === 'camera' ? 'камеру' : 'галерею') + ': '
            + (error && error.message ? error.message : error));
    }
}

// ответ приложения: таблица, отказ или «закрыли камеру без снимка». Возвращает true -
// MainActivity по этому видит, что страница ответ приняла
window.onPhotoScan = function (answer) {
    try {
        const expected = photo.waiting;
        if (photo.dismissed && answer.scanId === photo.dismissed) {
            photoLog.info('photo answer after the sheet was closed ignored', {scan: answer.scanId});
            return true;
        }
        if (expected && answer.scanId !== expected.scanId) {
            photoLog.warn('photo answer for another scan ignored', {got: answer.scanId, waiting: expected.scanId});
            return true;
        }
        if (!expected && answer.cancelled) {
            // страница перезагрузилась, пока была открыта камера, а снимок так и не сделали
            photoLog.info('photo cancelled after the page was reloaded, nothing to show', {scan: answer.scanId});
            return true;
        }
        const elapsed = expected ? Date.now() - expected.startedAt : null;
        photo.waiting = null;
        if (photo.target !== answer.target || $('#photo-sheet').hidden) {
            // страница перезагрузилась, пока была открыта камера: окно открывается заново
            photoOpen(answer.target);
        }

        if (answer.cancelled) {
            photoLog.info('photo cancelled by the user', {scan: answer.scanId, waitedMs: elapsed});
            renderPhotoChoice();
            return true;
        }
        if (!answer.ok) {
            photoLog.warn('photo not usable', {scan: answer.scanId, error: answer.error, waitedMs: elapsed});
            renderPhotoError(answer.error || 'Таблицу распознать не удалось.');
            return true;
        }
        photo.answer = answer;
        renderPhotoPreview(answer);
        photoLog.info('photo preview shown', {
            scan: answer.scanId,
            target: answer.target,
            size: answer.rows + 'x' + answer.columns,
            recognized: answer.recognized + '/' + answer.expected,
            check: answer.check,
            missing: answer.missing,
            mirror: answer.mirror,
            conflict: answer.conflict,
            words: answer.tokens,
            ignored: answer.ignored,
            recognitionMs: answer.recognitionMs,
            totalMs: answer.totalMs
        });
    } catch (error) {
        photoLog.error('photo answer could not be shown', {error: error});
        renderPhotoError('Сбой при показе результата: ' + (error && error.message ? error.message : error));
    }
    return true;
};

/* ============================ предпросмотр ============================ */

function renderPhotoPreview(answer) {
    let html = '';
    if (answer.image) {
        html += '<div class="photo-image" data-photo-action="zoom" title="Нажмите, чтобы увеличить">'
            + '<img src="' + answer.image + '" alt="Снимок таблицы"></div>';
    }
    html += '<p class="result-line">' + photoSummary(answer) + '</p>';
    if (answer.notes && answer.notes.length) {
        html += '<ul class="photo-notes">' + answer.notes.map(function (note) {
            return '<li>' + escapeHtml(note) + '</li>';
        }).join('') + '</ul>';
    }
    html += photoGrid(answer);
    html += photoLegend(answer);
    html += '<div class="photo-actions">'
        + '<button type="button" class="btn-primary" data-photo-action="apply">Подставить</button>'
        + '<button type="button" class="btn-ghost" data-photo-action="retry">Переснять</button>'
        + '</div>'
        + '<p class="hint center">Неверную цифру исправьте прямо в таблице после подстановки.</p>';
    $('#photo-body').innerHTML = html;
}

function photoSummary(answer) {
    if (answer.target === 'flow') {
        const n = answer.rows;
        let text = 'Матрица ' + n + '×' + n + ', ветвей: <b>' + answer.recognized + '</b>.';
        if (answer.mirror) {
            text += ' Взято из зеркальной клетки: ' + answer.mirror + '.';
        }
        if (answer.conflict) {
            text += ' В ' + (answer.conflict / 2) + ' ' + plural(answer.conflict / 2, 'паре', 'парах', 'парах')
                + ' c(i,j) и c(j,i) распознаны разными числами - взято число над диагональю.';
        }
        if (answer.check) {
            text += ' Проверить: ' + answer.check + '.';
        }
        return text + ' Пустая клетка - ветви нет: сверьте с фото, что там действительно пусто.';
    }
    let size;
    if (answer.target === 'plan') {
        size = answer.rows + ' ' + plural(answer.rows, 'вид', 'вида', 'видов') + ': объём и цена.';
    } else {
        size = 'Таблица ' + answer.rows + '×' + answer.columns + ': ' + answer.rows + ' '
            + plural(answer.rows, 'вид', 'вида', 'видов') + ', ' + answer.columns + ' '
            + plural(answer.columns, 'предприятие', 'предприятия', 'предприятий') + '.';
    }
    let text = size + ' Распознано <b>' + answer.recognized + ' из ' + answer.expected + '</b> '
        + plural(answer.expected, 'клетки', 'клеток', 'клеток') + '.';
    if (answer.check) {
        text += ' Проверить: ' + answer.check + '.';
    }
    if (answer.missing) {
        text += ' Не распознано: ' + answer.missing + ' - заполните их вручную.';
    }
    return text;
}

function photoGrid(answer) {
    const plan = answer.target === 'plan';
    let html = '<div class="table-scroll"><table class="data photo-grid"><thead><tr><th></th>';
    for (let j = 0; j < answer.columns; j++) {
        html += '<th>' + (plan ? (j === 0 ? 'Объём' : 'Цена') : (j + 1)) + '</th>';
    }
    html += '</tr></thead><tbody>';
    for (let i = 0; i < answer.rows; i++) {
        html += '<tr><th>' + (i + 1) + '</th>';
        for (let j = 0; j < answer.columns; j++) {
            if (answer.target === 'flow' && i === j) {
                html += '<td class="diag">-</td>';
                continue;
            }
            const value = answer.values[i][j];
            const flag = answer.flags[i][j];
            const cls = flag === PHOTO_CHECK ? 'check' : flag === PHOTO_MISSING ? 'missing'
                : flag === PHOTO_MIRROR ? 'mirror' : flag === PHOTO_CONFLICT ? 'conflict' : '';
            const text = value === null ? (flag === PHOTO_MISSING ? '?' : '·') : escapeHtml(value);
            html += '<td' + (cls ? ' class="' + cls + '"' : value === null ? ' class="zero"' : '') + '>' + text + '</td>';
        }
        html += '</tr>';
    }
    return html + '</tbody></table></div>';
}

function photoLegend(answer) {
    const parts = [];
    if (answer.check) {
        parts.push('<span class="photo-mark check">12</span> - число исправлено при распознавании, проверьте');
    }
    if (answer.missing) {
        parts.push('<span class="photo-mark missing">?</span> - не распознано');
    }
    if (answer.mirror) {
        parts.push('<span class="photo-mark mirror">7</span> - взято из зеркальной клетки');
    }
    if (answer.conflict) {
        parts.push('<span class="photo-mark conflict">9</span> - в паре разные числа');
    }
    return parts.length ? '<p class="legend">' + parts.join('; ') + '.</p>' : '';
}

function photoApply() {
    const answer = photo.answer;
    if (!answer) {
        photoLog.warn('apply pressed without an answer');
        return;
    }
    try {
        if (answer.target === 'flow') {
            applyFlowPhoto(answer);
        } else {
            plApplyPhoto(answer);
        }
        photoLog.info('photo applied', {scan: answer.scanId, target: answer.target, size: answer.rows + 'x' + answer.columns});
        photoClose('applied');
    } catch (error) {
        photoLog.error('photo apply failed', {scan: answer.scanId, target: answer.target, error: error});
        renderPhotoError('Не получилось подставить таблицу: ' + (error && error.message ? error.message : error));
    }
}

/* ============================ события ============================ */

function onPhotoClick(event) {
    const opener = event.target.closest('[data-photo]');
    if (opener) {
        event.preventDefault();
        photoOpen(opener.dataset.photo);
        return;
    }
    const control = event.target.closest('[data-photo-action]');
    if (!control) {
        if (event.target.id === 'photo-sheet') {
            photoClose('backdrop');
        }
        return;
    }
    const action = control.dataset.photoAction;
    if (action === 'camera' || action === 'gallery') {
        photoStart(action);
    } else if (action === 'apply') {
        photoApply();
    } else if (action === 'retry') {
        photo.answer = null;
        renderPhotoChoice();
        photoLog.info('photo retry', {target: photo.target});
    } else if (action === 'zoom') {
        control.classList.toggle('zoom');
    } else if (action === 'close') {
        photoClose('button');
    }
}

photoInit();
