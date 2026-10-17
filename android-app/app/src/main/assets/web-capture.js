(function () {
    'use strict';
    if (window !== window.top || location.origin !== 'https://dosirak.poswel.co.kr' || window.__poswelCaptureControl) return;
    var token = null, sequence = 0, maxEvents = 256;
    var bridge = window.PoswelCapture;
    if (!bridge || typeof bridge.postMessage !== 'function') return;
    var nativePost = bridge.postMessage.bind(bridge);
    var stringify = JSON.stringify;
    var parse = JSON.parse;
    var started = performance.now.bind(performance);
    var apply = Reflect.apply;
    var forms = new WeakMap();
    function post(type, route, method, status, duration, success) {
        if (!token || sequence >= maxEvents) return;
        try {
            var message = {v: 1, token: token, seq: type === 'READY' ? 0 : ++sequence, type: type};
            if (type !== 'READY') {
                message.route = route;
                message.method = method;
                message.status = Math.max(0, Math.min(599, status || 0));
                message.duration = Math.max(0, Math.min(600000, Math.round(duration || 0)));
                if (typeof success === 'boolean') message.success = success;
            }
            nativePost(stringify(message));
        } catch (_) { }
    }
    Object.defineProperty(window, '__poswelCaptureControl', {value: function (value) {
        token = typeof value === 'string' ? value : null;
        sequence = 0;
        if (token) { post('READY'); snapshot(); }
    }, configurable: false, writable: false});
    function route(url) {
        // Avoid invoking arbitrary coercion hooks on Request or other user-supplied objects.
        if (typeof url !== 'string') return null;
        try {
            var resolved = new URL(url, location.href);
            if (resolved.origin !== 'https://dosirak.poswel.co.kr' || resolved.username || resolved.password) return null;
            return ({'/togobox/ajax/order.cancel.php': 'CANCEL', '/togobox/order.temp.php': 'TEMP',
                '/togobox/get.order.temp.php': 'CART', '/togobox/order.reg.php': 'SUBMIT'})[resolved.pathname] || null;
        } catch (_) { return null; }
    }
    function method(value) { return value === 'GET' || value === 'POST' ? value : 'OTHER'; }
    function snapshotPost(type, value) {
        if (!token || sequence >= maxEvents) return;
        try { nativePost(stringify({v: 1, token: token, seq: ++sequence, type: type, snapshot: value})); } catch (_) { }
    }
    function boundedText(element) {
        if (!element) return '';
        var value = element.textContent;
        return typeof value === 'string' && value.length <= 128 ? value.trim().replace(/\s+/g, ' ') : '';
    }
    function numeric(value) {
        if (typeof value !== 'string' || value.length > 32 || !/^[0-9]+(?:,[0-9]{3})*(?:\s*원)?$/.test(value.trim())) return null;
        var number = Number(value.replace(/[,\s원]/g, ''));
        return Number.isSafeInteger(number) && number >= 0 && number <= 1000000000 ? number : null;
    }
    function safeDate(value) {
        var found = /^((?:20[0-9]{2}|2100)-[0-9]{2}-[0-9]{2})(?:\s*\([^)]{1,3}\))?$/.exec(value);
        return found ? found[1] : null;
    }
    function checkoutSnapshot(form) {
        if (!form) return;
        var quantities = form.querySelectorAll('input[name="qty[]"]');
        var total = form.querySelector('#totamt');
        var quantity = quantities.length === 1 ? numeric(quantities[0].value) : null;
        snapshotPost('CHECKOUT', {date: safeDate(boundedText(form.querySelector('.date'))),
            quantity: quantity !== null && quantity <= 10000 ? quantity : null,
            total: total ? numeric(total.value) : null});
    }
    function snapshot() {
        try {
            if (location.pathname === '/order.info.php') checkoutSnapshot(document.getElementById('orderGo'));
            if (location.pathname !== '/order.list.php') return;
            var table = document.getElementById('dataTable'), body = table && table.querySelector('tbody');
            var originals = body ? body.querySelectorAll('tr') : [], rows = [], all = !!body && originals.length <= 32;
            for (var i = 0; i < Math.min(32, originals.length); i++) {
                var row = originals[i], date = safeDate(boundedText(row.querySelector('.date')));
                var state = ({'주문완료': 'ORDERED', '배송완료': 'DELIVERED', '취소': 'CANCELLED', '주문취소': 'CANCELLED', '취소완료': 'CANCELLED'})[boundedText(row.querySelector('.state'))] || 'UNKNOWN';
                var cancel = row.querySelector('.btn-cancle'), handler = false, link = false;
                // Cancelled rows omit the action class but retain the known direct column.
                if (!cancel && row.children) {
                    var cells = [];
                    for (var k = 0; k < Math.min(16, row.children.length); k++) {
                        if (row.children[k].tagName === 'TD') cells.push(row.children[k]);
                    }
                    if (cells.length >= 7) cancel = cells[6];
                }
                var links = row.querySelectorAll('a[onclick],a[href]');
                for (var j = 0; j < Math.min(16, links.length); j++) {
                    var node = links[j];
                    var known = /^(?:javascript:\s*)?jsOrderCxl\(\s*(['"]?)[0-9]+\1\s*\)\s*;?$/.test(node.getAttribute('onclick') || '') ||
                        /^(?:javascript:\s*)?jsOrderCxl\(\s*(['"]?)[0-9]+\1\s*\)\s*;?$/.test(node.getAttribute('href') || '');
                    if (known) { handler = true; link = true; }
                }
                if (links.length > 16 || !date || state === 'UNKNOWN') all = false;
                if (cancel && cancel.querySelector('a')) link = true;
                var quantity = numeric(boundedText(row.querySelector('.qtt')));
                rows.push({date: date, quantity: quantity !== null && quantity <= 10000 ? quantity : null,
                    total: numeric(boundedText(row.querySelector('.pay'))), state: state,
                    cancelCell: !!cancel, cancelTime: /(?:^|\s)[0-9]{2}\.[0-9]{2}\.[0-9]{2} [0-9]{2}:[0-9]{2}(?:$|\s)/.test(boundedText(cancel)),
                    cancelLink: link, handler: handler, rating: !!row.querySelector('.point a')});
            }
            snapshotPost('HISTORY', {table: !!table, body: !!body, all: all, rows: rows});
        } catch (_) { }
    }
    function success(text) {
        if (typeof text !== 'string' || text.length > 4096) return undefined;
        try {
            var result = parse(text);
            if (!result || typeof result !== 'object' || Array.isArray(result)) return undefined;
            return result.code === '0000' ? true :
                (typeof result.code === 'string' && /^[0-9]{4}$/.test(result.code) ? false : undefined);
        } catch (_) { return undefined; }
    }
    if (window.XMLHttpRequest) {
        var xhrOpen = XMLHttpRequest.prototype.open;
        var xhrSend = XMLHttpRequest.prototype.send;
        var requests = new WeakMap();
        XMLHttpRequest.prototype.open = function () {
            var returned = apply(xhrOpen, this, arguments);
            try {
                requests.delete(this);
                if (token) requests.set(this, {route: route(arguments[1]), method: method(arguments[0])});
            } catch (_) { }
            return returned;
        };
        XMLHttpRequest.prototype.send = function () {
            var cleanup;
            try {
                var request = requests.get(this);
                if (token && request && request.route && request.route !== 'SUBMIT' && sequence < maxEvents) {
                    var self = this, requestToken = token, time = started(), done = false;
                    var ended = function () {
                        try {
                            if (done || self.readyState !== 4) return;
                            done = true;
                            cleanup();
                            if (token !== requestToken) return;
                            var knownSuccess;
                            if (request.route === 'CANCEL') {
                                if (!self.responseType || self.responseType === 'text') knownSuccess = success(self.responseText);
                                else if (self.responseType === 'json') {
                                    var code = self.response && self.response.code;
                                    knownSuccess = code === '0000' ? true : (typeof code === 'string' && /^[0-9]{4}$/.test(code) ? false : undefined);
                                }
                            }
                            post('XHR', request.route, request.method, self.status, started() - time, knownSuccess);
                        } catch (_) { }
                    };
                    cleanup = function () {
                        self.removeEventListener('readystatechange', ended, true);
                        self.removeEventListener('loadend', ended, true);
                    };
                    self.addEventListener('readystatechange', ended, true);
                    self.addEventListener('loadend', ended, true);
                }
            } catch (_) { }
            try { return apply(xhrSend, this, arguments); }
            catch (error) { if (cleanup) cleanup(); throw error; }
        };
    }
    if (typeof window.fetch === 'function') {
        var originalFetch = window.fetch;
        window.fetch = function () {
            var returned = apply(originalFetch, this, arguments);
            try {
                var target = route(arguments[0]);
                var options = arguments[1];
                // Only read a plain data property, never a getter twice.
                var descriptor = options && Object.getOwnPropertyDescriptor(options, 'method');
                var verb = !options ? 'GET' : descriptor && 'value' in descriptor ? method(descriptor.value) : 'OTHER';
                if (token && target && target !== 'SUBMIT' && sequence < maxEvents) {
                    var requestToken = token, time = started();
                    returned.then(function (response) {
                        if (token !== requestToken) return;
                        // Fetch response bodies are deliberately excluded: cloning streams can alter backpressure.
                        post('FETCH', target, verb, response.status, started() - time);
                    }, function () {
                        if (token === requestToken) post('FETCH', target, verb, 0, started() - time);
                    });
                }
            } catch (_) { }
            return returned;
        };
    }
    function formSubmitted(form) {
        if (!token || sequence >= maxEvents || !(form instanceof HTMLFormElement)) return;
        try {
            if (route(form.action) !== 'SUBMIT' || form.method.toUpperCase() !== 'POST') return;
            var now = started(), previous = forms.get(form);
            if (previous && previous.token === token && now - previous.time < 1000) return;
            forms.set(form, {token: token, time: now});
            try { checkoutSnapshot(form); } catch (_) { }
            post('FORM', 'SUBMIT', 'POST', 0, 0);
        } catch (_) { }
    }
    document.addEventListener('submit', function (event) { formSubmitted(event.target); }, true);
    if (window.HTMLFormElement) {
        var originalSubmit = HTMLFormElement.prototype.submit;
        HTMLFormElement.prototype.submit = function () {
            try { formSubmitted(this); } catch (_) { }
            return apply(originalSubmit, this, arguments);
        };
    }
}());
