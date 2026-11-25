'use strict';

/* ===========================================================================
   Вход по ключу.

   Пока ключ не введён или не действует, страница показывает только поле ввода.
   Какие вкладки открыты, говорит сервер (сайт) или Java-часть приложения (мост
   android-bridge.js): здесь доступ только показывается. Сам расчёт без ключа не идёт
   ни на сайте, ни в приложении - страницу можно подменить, а ответ 401 - нет.

   Адреса: api/access/check - действует ли доступ, api/access/activate - ввод ключа.
   Любой ответ 401 на расчёт закрывает страницу, 403 - убирает вкладку.

   Подключается после app.js и берёт оттуда утилиты и selectTab.
   Код рассчитан и на старый WebView из Android 10, поэтому без ?. и ??.
   =========================================================================== */

const ACCESS_CHECK_EVERY_MS = 60000;
// причины, после которых ключ нужно ввести заново
const ACCESS_LOST = ['none', 'disabled', 'unbound', 'deleted'];
const ACCESS_HINT = 'Введите ключ доступа. Ключ выдаёт автор приложения.';

const accessLog = createLogger('access');

const access = {
    // checking - идёт первая проверка, locked - нужен ключ, offline - ключ есть, но нет связи, open - доступ есть
    mode: 'checking',
    tabs: [],
    busy: false,
    // в приложении проверку раз в минуту ведёт Java, на сайте - эта страница
    bridge: typeof MultiFlowBridge !== 'undefined'
};

/* ============================ ответы 401 и 403 ============================ */

// все запросы к api идут через эту обёртку: отказ в доступе виден здесь, а не в каждой вкладке
const accessRawFetch = window.fetch ? window.fetch.bind(window) : null;

window.fetch = function (url, options) {
    if (!accessRawFetch) {
        return Promise.reject(new Error('fetch is not available'));
    }
    return accessRawFetch(url, options).then(function (response) {
        const address = String(url);
        const refused = response.status === 401 || response.status === 403;
        if (refused && address.indexOf('api/') === 0 && address.indexOf('api/access/') !== 0) {
            const copy = response.clone ? response.clone() : response;
            copy.json().then(function (body) {
                accessLog.warn('calculation refused', {address: address, status: response.status, reason: body.reason});
                if (body.reason === 'tab') {
                    accessCheck('tab refused');
                } else if (body.reason === 'offline') {
                    accessSetOffline(body.detail);
                } else {
                    accessLock(body.reason, body.detail);
                }
            }).catch(function (error) {
                accessLog.error('refusal is not readable', {address: address, status: response.status, error: error});
            });
        }
        return response;
    });
};

/* ============================ состояние страницы ============================ */

function accessSetMode(mode) {
    if (access.mode !== mode) {
        accessLog.info('access mode changed', {from: access.mode, to: mode});
    }
    access.mode = mode;
    const root = document.documentElement;
    root.classList.toggle('access-locked', mode !== 'open');
    $('#access').hidden = mode === 'open';
    $('#access-form').hidden = mode !== 'locked';
    $('#access-offline').hidden = mode !== 'offline';
    $('#access-wait').hidden = mode !== 'checking';
}

// доступ есть: видны только разрешённые вкладки
function accessOpen(tabs) {
    const known = tabs.filter(function (tab) {
        return TABS[tab] !== undefined;
    });
    const changed = known.join() !== access.tabs.join();
    access.tabs = known;
    document.querySelectorAll('.tabs [data-tab]').forEach(function (button) {
        button.hidden = known.indexOf(button.dataset.tab) === -1;
    });
    $('.tabs').style.gridTemplateColumns = 'repeat(' + Math.max(1, known.length) + ', 1fr)';
    const current = $('.tabs .active');
    if (known.length && (!current || known.indexOf(current.dataset.tab) === -1)) {
        selectTab(known[0], 'access');
    }
    accessShowMessage('');
    accessSetMode('open');
    if (changed) {
        accessLog.info('tabs opened by the key', {tabs: known.join(',')});
    }
}

// доступа нет: решения с экрана убираются, введённые таблицы остаются на устройстве
function accessLock(reason, detail) {
    const wasOpen = access.mode === 'open';
    access.tabs = [];
    accessClearResults();
    $('#access-key').value = '';
    accessSetMode('locked');
    // «введите ключ» и так написано на карточке, отдельное сообщение - только о причине
    accessShowMessage(reason === 'none' ? '' : detail || '');
    if (wasOpen) {
        accessLog.warn('access lost, the key is asked again', {reason: reason});
        window.scrollTo(0, 0);
    } else {
        accessLog.info('the key is asked', {reason: reason});
    }
}

function accessSetOffline(detail) {
    accessClearResults();
    access.tabs = [];
    $('#access-offline-text').textContent = detail || 'Нет связи с сервером.';
    accessShowMessage('');
    accessSetMode('offline');
}

function accessClearResults() {
    const sheet = $('#photo-sheet');
    if (sheet && !sheet.hidden) {
        photoClose('access lost');
    }
    clearFlowResult();
    plClearResult();
    trClearResult();
}

function accessShowMessage(text) {
    const box = $('#access-message');
    box.textContent = text;
    box.hidden = !text;
}

/* ============================ проверка ============================ */

function accessCheck(why) {
    accessLog.debug('access check', {why: why});
    return fetch('api/access/check', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: '{}'
    }).then(function (response) {
        return response.json().then(function (body) {
            accessApply(response.status, body, why);
        });
    }).catch(function (error) {
        accessLog.warn('access check failed', {why: why, error: error});
        if (access.mode === 'checking') {
            accessSetOffline('Нет связи с сервером. Проверьте интернет.');
        }
    });
}

// ответ проверки: доступ есть, доступа нет или проверить не вышло
function accessApply(status, body, why) {
    if (status === 200) {
        accessOpen(body.tabs || []);
        return;
    }
    if (status === 401 && ACCESS_LOST.indexOf(body.reason) !== -1) {
        accessLock(body.reason, body.detail);
        return;
    }
    if (body.reason === 'offline') {
        accessSetOffline(body.detail);
        return;
    }
    // сервер перегружен или сломан: открытую страницу не закрываем, расчёт он всё равно проверит сам
    accessLog.warn('access check is inconclusive', {why: why, status: status, title: body.title, detail: body.detail});
    if (access.mode === 'checking') {
        accessSetOffline((body.title || 'Сервер не ответил') + ': ' + (body.detail || status));
    }
}

// приложение сообщает об изменении доступа само: проверка раз в минуту, возврат из фона
window.onAccessChanged = function (answer) {
    accessApply(answer.status, answer.body, 'app');
    return true;
};

/* ============================ ввод ключа ============================ */

// ABCD-EFGH-JKLM: дефисы ставятся сами, лишнее отбрасывается
function accessFormatKey(text) {
    const symbols = text.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 12);
    const groups = symbols.match(/.{1,4}/g);
    return groups ? groups.join('-') : '';
}

function accessOnInput() {
    const input = $('#access-key');
    const atEnd = input.selectionStart === input.value.length;
    const formatted = accessFormatKey(input.value);
    // посередине слова курсор не дёргаем: дефисы расставятся, когда человек допишет
    if (atEnd && formatted !== input.value) {
        input.value = formatted;
    }
}

function accessSubmit(event) {
    event.preventDefault();
    if (access.busy) {
        return;
    }
    const key = accessFormatKey($('#access-key').value);
    if (key.length < 14) {
        accessShowMessage('Ключ состоит из 12 знаков, например ABCD-EFGH-JKLM.');
        return;
    }
    accessSetBusy(true);
    accessShowMessage('');
    accessLog.info('key entered', {symbols: key.length});
    fetch('api/access/activate', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({key: key})
    }).then(function (response) {
        return response.json().then(function (body) {
            if (response.ok) {
                $('#access-key').value = '';
                accessOpen(body.tabs || []);
                window.scrollTo(0, 0);
            } else {
                accessLog.warn('key refused', {status: response.status, reason: body.reason});
                accessShowMessage(body.detail || body.title || 'Ключ не принят.');
            }
        });
    }).catch(function (error) {
        accessLog.error('key request failed', {error: error});
        accessShowMessage('Нет связи с сервером. Для ввода ключа нужен интернет.');
    }).then(function () {
        accessSetBusy(false);
    });
}

function accessSetBusy(busy) {
    access.busy = busy;
    const button = $('#access-enter');
    button.disabled = busy;
    button.textContent = busy ? 'Проверяю…' : 'Войти';
}

function accessRetry() {
    const button = $('#access-retry');
    button.disabled = true;
    accessCheck('retry').then(function () {
        button.disabled = false;
    });
}

/* ============================ запуск ============================ */

function accessInit() {
    $('#access-hint').textContent = ACCESS_HINT;
    $('#access-form').addEventListener('submit', accessSubmit);
    $('#access-key').addEventListener('input', accessOnInput);
    $('#access-retry').addEventListener('click', accessRetry);
    accessSetMode('checking');

    if (access.bridge) {
        // приложение помнит доступ само: страница открывается сразу, сеть проверяется следом
        try {
            const known = JSON.parse(MultiFlowBridge.accessState());
            accessApply(known.status, known.body, 'saved state');
        } catch (error) {
            accessLog.error('saved access state is not readable', {error: error});
        }
    } else {
        setInterval(function () {
            if (access.mode === 'open' && !document.hidden) {
                accessCheck('timer');
            }
        }, ACCESS_CHECK_EVERY_MS);
        document.addEventListener('visibilitychange', function () {
            if (!document.hidden && access.mode !== 'checking') {
                accessCheck('page shown');
            }
        });
    }
    accessCheck('page start');
    accessLog.info('access ready', {bridge: access.bridge, mode: access.mode});
}
