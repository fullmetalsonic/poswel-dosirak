'use strict';
// Pure runtime fixture. It makes no requests and does not use a phone/app data.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/assets/web-capture.js'), 'utf8');
const token = '00000000-0000-0000-0000-000000000001';
const privateText = 'PRIVATE_ACCOUNT_ADDRESS_PHONE_TOKEN';
function fixture(origin = 'https://dosirak.poswel.co.kr', subframe = false, sinkThrows = false) {
    const messages = [], calls = [], documentListeners = new Map();
    let fetchReturn, tick = 0;
    class Xhr {
        constructor() { this.listeners = new Map(); this.readyState = 0; this.status = 200; this.responseType = ''; this.responseText = '{"code":"0000","oNo":"' + privateText + '"}'; }
        addEventListener(name, listener, capture) { const values = this.listeners.get(name) || []; values.push({listener, capture: !!capture}); this.listeners.set(name, values); }
        removeEventListener(name, listener) { this.listeners.set(name, (this.listeners.get(name) || []).filter(item => item.listener !== listener)); }
        dispatch(name) { for (const item of [...(this.listeners.get(name) || [])].sort((a, b) => Number(b.capture) - Number(a.capture))) item.listener(); if (this['on' + name]) this['on' + name](); }
        open() { calls.push(['open', this, [...arguments]]); return 'OPEN_RETURN'; }
        send() { calls.push(['send', this, [...arguments]]); if (this.failure) throw this.failure; this.readyState = 4; this.dispatch('readystatechange'); this.dispatch('loadend'); return 'SEND_RETURN'; }
    }
    class Form { constructor() { this.action = origin + '/togobox/order.reg.php'; this.method = 'post'; } submit() { calls.push(['submit', this, [...arguments]]); if (this.failure) throw this.failure; return 'FORM_RETURN'; } }
    const context = {
        console, URL, WeakMap, Reflect, JSON, Object, Array, Math,
        location: {origin, href: origin + '/order.list.php'},
        performance: {now: () => ++tick},
        XMLHttpRequest: Xhr, HTMLFormElement: Form,
        document: {addEventListener: (name, fn) => documentListeners.set(name, fn)},
        PoswelCapture: {postMessage: text => { if (sinkThrows) throw new Error('fixture sink'); messages.push(JSON.parse(text)); }},
        fetch: function () { calls.push(['fetch', this, [...arguments]]); return fetchReturn; }
    };
    context.window = context; context.top = subframe ? {} : context;
    vm.createContext(context); vm.runInContext(source, context);
    return {context, messages, calls, Xhr, Form, documentListeners,
        activate: value => context.__poswelCaptureControl(value), setFetch: value => { fetchReturn = value; }};
}
(async function () {
    let checks = 0;
    const f = fixture();
    const off = new f.Xhr(); off.open('POST', '/togobox/ajax/order.cancel.php', false); off.send(privateText);
    assert.equal(f.messages.length, 0); checks++;
    f.activate(token); assert.equal(f.messages[0].type, 'READY'); checks++;
    const xhr = new f.Xhr(), body = {privateText};
    assert.equal(xhr.open('POST', '/togobox/ajax/order.cancel.php?oNo=' + privateText, false), 'OPEN_RETURN');
    let callbackObservedCapture = false;
    xhr.onreadystatechange = () => { callbackObservedCapture = f.messages.some(m => m.type === 'XHR'); };
    assert.equal(xhr.send(body), 'SEND_RETURN');
    assert.equal(f.calls.at(-2)[2][2], false); assert.equal(f.calls.at(-1)[2][0], body);
    assert.equal(callbackObservedCapture, true); assert.equal(f.messages.filter(m => m.type === 'XHR').length, 1);
    assert.equal(f.messages.at(-1).success, true); assert.equal(JSON.stringify(f.messages).includes(privateText), false); checks++;
    const thrown = new Error('original'), failing = new f.Xhr();
    failing.open('POST', '/togobox/order.temp.php', true); failing.failure = thrown;
    assert.throws(() => failing.send(body), error => error === thrown);
    assert.equal((failing.listeners.get('loadend') || []).length, 0); checks++;
    const outside = new f.Xhr(); outside.open('POST', 'https://evil.test/togobox/ajax/order.cancel.php'); outside.send();
    const suffix = new f.Xhr(); suffix.open('POST', '/togobox/ajax/order.cancel.php/not-matching'); suffix.send();
    assert.equal(f.messages.filter(m => m.type === 'XHR').length, 1); checks++;
    const promise = Promise.resolve({status: 204}); f.setFetch(promise);
    assert.equal(f.context.fetch('/togobox/get.order.temp.php'), promise); await promise; await Promise.resolve();
    assert.equal(f.messages.at(-1).type, 'FETCH'); assert.equal(f.messages.at(-1).method, 'GET'); checks++;
    let extraReads = 0;
    const options = Object.defineProperty({}, 'method', {get() { extraReads++; return 'POST'; }});
    f.setFetch(Promise.resolve({status: 200})); f.context.fetch('/togobox/ajax/order.cancel.php', options); await Promise.resolve();
    assert.equal(extraReads, 0); checks++;
    const form = new f.Form(); f.documentListeners.get('submit')({target: form}); assert.equal(form.submit(body), 'FORM_RETURN');
    assert.equal(f.messages.filter(m => m.type === 'FORM').length, 1); assert.equal(f.calls.at(-1)[2][0], body); checks++;
    form.failure = thrown; assert.throws(() => form.submit(), error => error === thrown); checks++;
    const queued = new f.Xhr(); queued.open('POST', '/togobox/ajax/order.cancel.php');
    f.activate(null); queued.send(); assert.equal(f.messages.at(-1).type, 'FORM'); checks++;
    f.activate(token); for (let i = 0; i < 300; i++) { const item = new f.Xhr(); item.open('POST', '/togobox/ajax/order.cancel.php'); item.send(); }
    assert.equal(f.messages.filter(m => m.token === token && m.seq === 256).length, 1); checks++;
    assert.equal(fixture('https://evil.test').context.__poswelCaptureControl, undefined);
    assert.equal(fixture('https://dosirak.poswel.co.kr', true).context.__poswelCaptureControl, undefined); checks++;
    function historyRow(state = '취소', cancelClass = true) {
        const fields = {'.date': '2026-10-18 (일)', '.qtt': '0', '.pay': '', '.state': state};
        const cancel = {textContent: '26.10.18 07:05', querySelector: () => null};
        return {children: Array.from({length: 8}, (_, i) => i === 6 ? Object.assign(cancel, {tagName: 'TD'}) : {tagName: 'TD', textContent: i === 5 ? privateText : ''}),
            querySelector: selector => selector === '.btn-cancle' ? (cancelClass ? cancel : null) :
            selector === '.point a' ? null : fields[selector] !== undefined ? {textContent: fields[selector]} : null,
            querySelectorAll: () => []};
    }
    const h = fixture(); h.context.location.pathname = '/order.list.php';
    let rows = [historyRow()];
    h.context.document.getElementById = id => id === 'dataTable' ? {querySelector: () => ({querySelectorAll: () => rows})} : null;
    h.activate(token);
    let snapshot = h.messages.find(m => m.type === 'HISTORY').snapshot;
    assert.equal(snapshot.rows[0].state, 'CANCELLED'); assert.equal(snapshot.rows[0].cancelTime, true);
    assert.equal(snapshot.all, true); assert.equal(JSON.stringify(h.messages).includes(privateText), false); checks++;
    rows = Array.from({length: 40}, () => historyRow()); h.activate(token);
    snapshot = h.messages.at(-1).snapshot; assert.equal(snapshot.rows.length, 32); assert.equal(snapshot.all, false); checks++;
    const count = h.messages.length; h.activate(null); assert.equal(h.messages.length, count); checks++;
    rows = [historyRow('취소', false)]; h.activate(token);
    snapshot = h.messages.at(-1).snapshot;
    assert.equal(snapshot.rows[0].cancelCell, true); assert.equal(snapshot.rows[0].cancelTime, true);
    assert.equal(JSON.stringify(snapshot).includes(privateText), false); checks++;
    const c = fixture(); c.context.location.pathname = '/order.info.php';
    const checkout = {querySelectorAll: () => [{value: '1'}], querySelector: selector => selector === '#totamt' ? {value: '5,000 원'} : null};
    c.context.document.getElementById = () => checkout; c.activate(token);
    assert.deepEqual(JSON.parse(JSON.stringify(c.messages.at(-1).snapshot)), {date: null, quantity: 1, total: 5000}); checks++;
    const broken = fixture('https://dosirak.poswel.co.kr', false, true); broken.activate(token);
    const brokenXhr = new broken.Xhr(); brokenXhr.open('POST', '/togobox/ajax/order.cancel.php');
    assert.equal(brokenXhr.send(privateText), 'SEND_RETURN');
    assert.equal(new broken.Form().submit(), 'FORM_RETURN'); checks++;
    const malformed = fixture(); malformed.context.location.pathname = '/order.list.php';
    malformed.context.document.getElementById = () => { throw new Error(privateText); };
    malformed.activate(token); assert.equal(malformed.messages.length, 1); checks++;
    console.log(`PASS ${checks} web capture fixture groups; no requests/phone/app data`);
}()).catch(error => { console.error(error); process.exitCode = 1; });
