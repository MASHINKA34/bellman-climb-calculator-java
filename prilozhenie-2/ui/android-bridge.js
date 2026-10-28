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

    window.fetch = function (url, options) {
        var address = String(url);

        if (address.indexOf('api/example') !== -1) {
            return later(function () {
                return MultiFlowBridge.example();
            });
        }

        if (address.indexOf('api/solve') !== -1) {
            var body = options && options.body ? options.body : '{}';
            return later(function () {
                return MultiFlowBridge.solve(body);
            });
        }

        if (nativeFetch) {
            return nativeFetch(url, options);
        }
        return Promise.reject(new Error('Адрес ' + address + ' недоступен без сети'));
    };
}());
