/* ===========================================================================
   Мост между страницей и расчётным ядром на телефоне.

   Страница обращается к расчёту через fetch('api/solve'), как к серверу.
   В приложении сервера нет: этот файл подменяет fetch для адресов api/*,
   зовёт Java напрямую (объект MultiFlowBridge из MainActivity) и отдаёт
   ответ в том же виде, что и сервер.

   Поэтому app.js в приложении и на сайте - один и тот же файл. В обычном
   браузере моста нет, и этот файл ничего не делает.

   Подключён в <head>: класс темы ставится до первой отрисовки страницы.
   =========================================================================== */
(function () {
    'use strict';

    if (typeof MultiFlowBridge === 'undefined') {
        return;
    }

    // WebView Android 10-12 не сообщает странице тёмный режим системы,
    // поэтому тему сообщает приложение, а styles.css берёт палитру по классу
    try {
        document.documentElement.classList.add(MultiFlowBridge.theme() === 'dark' ? 'theme-dark' : 'theme-light');
    } catch (error) {
        console.warn('theme from bridge is unavailable, following prefers-color-scheme: ' + error);
    }

    var nativeFetch = window.fetch ? window.fetch.bind(window) : null;

    /** Превращает ответ моста в объект, неотличимый для app.js от ответа сервера. */
    function toResponse(raw) {
        var envelope = JSON.parse(raw);
        return {
            ok: envelope.ok,
            status: envelope.status,
            json: function () {
                return Promise.resolve(envelope.body);
            }
        };
    }

    /** Вызов моста синхронный, поэтому уступаем кадр: кнопка успевает отреагировать. */
    function later(call) {
        return new Promise(function (resolve, reject) {
            setTimeout(function () {
                try {
                    resolve(toResponse(call()));
                } catch (error) {
                    reject(error);
                }
            }, 0);
        });
    }

    // доступ по ключу: Java ходит на сервер и отвечает не сразу, а в window.onAccessAnswer
    var accessCalls = {};
    var accessCounter = 0;

    function accessCall(operation, body) {
        return new Promise(function (resolve) {
            accessCounter += 1;
            var callId = 'a' + Date.now().toString(36) + '-' + accessCounter;
            accessCalls[callId] = resolve;
            MultiFlowBridge.accessCall(callId, operation, body);
        });
    }

    window.onAccessAnswer = function (answer) {
        var resolve = accessCalls[answer.callId];
        delete accessCalls[answer.callId];
        if (resolve) {
            resolve(toResponse(JSON.stringify(answer.envelope)));
        }
        return true;
    };

    // перед расчётом доступ проверяется на сервере (если последняя проверка старше 10 с). Что бы она
    // ни ответила, решает Java: без доступа сам расчёт вернёт отказ
    function guarded(call) {
        return accessCall('check', '{"maxAgeMs":10000}').then(function () {
            return later(call);
        });
    }

    window.fetch = function (url, options) {
        var address = String(url);

        if (address.indexOf('api/access/activate') !== -1) {
            return accessCall('activate', options && options.body ? options.body : '{}');
        }

        if (address.indexOf('api/access/check') !== -1) {
            return accessCall('check', '{}');
        }

        // вкладка «Транспортная»: api/transport/example и api/transport/solve
        if (address.indexOf('api/transport/example') !== -1) {
            return later(function () {
                return MultiFlowBridge.transportExample();
            });
        }

        if (address.indexOf('api/transport/solve') !== -1) {
            var transportBody = options && options.body ? options.body : '{}';
            return guarded(function () {
                return MultiFlowBridge.solveTransport(transportBody);
            });
        }

        // вкладка «Размещение»: api/placement/example и api/placement/solve
        if (address.indexOf('api/placement/example') !== -1) {
            return later(function () {
                return MultiFlowBridge.placementExample();
            });
        }

        if (address.indexOf('api/placement/solve') !== -1) {
            var placementBody = options && options.body ? options.body : '{}';
            return guarded(function () {
                return MultiFlowBridge.solvePlacement(placementBody);
            });
        }

        if (address.indexOf('api/example') !== -1) {
            return later(function () {
                return MultiFlowBridge.example();
            });
        }

        if (address.indexOf('api/solve') !== -1) {
            var body = options && options.body ? options.body : '{}';
            return guarded(function () {
                return MultiFlowBridge.solve(body);
            });
        }

        if (nativeFetch) {
            return nativeFetch(url, options);
        }
        return Promise.reject(new Error('Адрес ' + address + ' недоступен без сети'));
    };
}());
