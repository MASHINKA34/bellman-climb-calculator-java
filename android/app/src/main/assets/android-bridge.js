/* ===========================================================================
   Мост между страницей и расчётным ядром на телефоне.

   Веб-версия обращается к серверу через fetch('api/climb'). В приложении
   сервера нет: этот файл подменяет fetch для адресов api/*, зовёт Java
   напрямую и возвращает ответ в том же виде, что и сервер.

   Благодаря этому app.js на сайте и в приложении — один и тот же файл,
   собираемый из одного исходника. Если открыть эту же страницу в обычном
   браузере, моста не окажется и fetch останется нетронутым.
   =========================================================================== */
(function () {
    'use strict';

    if (typeof BellmanBridge === 'undefined') {
        return;
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

    window.fetch = function (url, options) {
        var address = String(url);

        if (address.indexOf('api/defaults') !== -1) {
            return Promise.resolve(toResponse(BellmanBridge.defaults()));
        }

        if (address.indexOf('api/climb') !== -1) {
            var body = options && options.body ? options.body : '{}';
            return new Promise(function (resolve, reject) {
                // Вызов моста синхронный, поэтому уступаем кадр: интерфейс
                // успевает отреагировать на нажатие до начала счёта.
                setTimeout(function () {
                    try {
                        resolve(toResponse(BellmanBridge.climb(body)));
                    } catch (error) {
                        reject(error);
                    }
                }, 0);
            });
        }

        if (nativeFetch) {
            return nativeFetch(url, options);
        }
        return Promise.reject(new Error('Адрес ' + address + ' недоступен без сети'));
    };
}());
