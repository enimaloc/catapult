/*
 * htmx-ws-extension.js — routes every HTMX request through the WebSocket
 * channel exposed by ws-client.js (window.catapultWs).
 *
 * Frame shape (C→S) matches the catapult WS protocol §6:
 *   { type:"request", id:"h-<uniq>", action:"htmx",
 *     method:"GET|POST|PUT|DELETE", path:"/...",
 *     headers:{HX-*:...}, params:{name:value,...}, csrfToken:"..." }
 *
 * Server replies with a `response` frame whose top-level fields
 *   status, html, target, swap, oob[], triggers
 * are inlined into the XHR htmx already created — htmx's swap pipeline
 * then runs unchanged.
 *
 * Cases handled :
 *   - WS not ready          : the request is buffered (≤ 30s) and a GET will
 *                             replay automatically on reconnect; mutations are
 *                             rejected with `code: DISCONNECTED`.
 *   - multipart/form-data   : extension bows out (`return true`) so htmx falls
 *                             back to a native XHR HTTP upload.
 *   - File in params        : same fallback as above (detected from FormData).
 *
 * The extension is attached body-wide via `hx-ext="ws-rpc"`; per-element
 * `hx-ext="ignore:ws-rpc"` can opt back to direct XHR for a specific node.
 */
(function () {
  "use strict";

  if (!window.htmx) {
    // ws-client.js still works; just no HTMX routing in this page.
    console.warn("htmx-ws-extension: htmx not loaded, extension inactive");
    return;
  }

  // id -> { xhr, detail, frame }
  var pending = new Map();
  // queued requests waiting for ws open : [{ id, frame, ts }]
  var buffer = [];
  var BUFFER_TTL_MS = 30000;
  var nextId = 1;

  function newId() {
    return "h-" + (nextId++) + "-" + Math.random().toString(36).slice(2, 8);
  }

  function csrfToken() {
    var meta = document.querySelector("meta[name='_csrf']");
    return meta ? meta.getAttribute("content") : null;
  }

  function isMultipartElt(elt) {
    if (!elt) return false;
    if (elt.enctype && elt.enctype === "multipart/form-data") return true;
    // Look up the closest <form ancestor> in case the trigger is on a button.
    if (elt.closest) {
      var form = elt.closest("form");
      if (form && form.enctype === "multipart/form-data") return true;
    }
    return false;
  }

  function extractHxHeaders(headers) {
    var out = {};
    if (!headers) return out;
    Object.keys(headers).forEach(function (k) {
      if (k && k.toLowerCase().indexOf("hx-") === 0) {
        out[k] = headers[k];
      }
    });
    return out;
  }

  function serializeParams(parameters) {
    if (!parameters) return {};
    // FormData → plain object; bail on file entries (signal MULTIPART).
    if (typeof FormData !== "undefined" && parameters instanceof FormData) {
      var out = {};
      var iter = parameters.entries();
      while (true) {
        var step = iter.next();
        if (step.done) break;
        var k = step.value[0];
        var v = step.value[1];
        if (typeof File !== "undefined" && v instanceof File) {
          return { __multipart: true };
        }
        // Repeated names → array
        if (Object.prototype.hasOwnProperty.call(out, k)) {
          if (!Array.isArray(out[k])) out[k] = [out[k]];
          out[k].push(v);
        } else {
          out[k] = v;
        }
      }
      return out;
    }
    return parameters;
  }

  function buildFrame(detail) {
    var cfg = detail.requestConfig || {};
    var method = (cfg.verb || "GET").toUpperCase();
    var headers = extractHxHeaders(detail.headers || cfg.headers || {});
    var params = serializeParams(cfg.parameters);
    if (params && params.__multipart) return null;
    return {
      type: "request",
      id: newId(),
      action: "htmx",
      method: method,
      path: cfg.path,
      headers: headers,
      params: params,
      csrfToken: csrfToken()
    };
  }

  function applyResponse(msg) {
    var entry = pending.get(msg.id);
    if (!entry) return;
    pending.delete(msg.id);
    var xhr = entry.xhr;
    var status = (msg.ok === false ? 500 : (msg.status || 200));
    var html = msg.html || "";

    // OOB swaps first so they're in the DOM before the main swap runs.
    if (Array.isArray(msg.oob)) {
      msg.oob.forEach(function (oob) {
        if (!oob || !oob.target) return;
        var el = document.querySelector(oob.target);
        if (el && window.htmx && typeof window.htmx.swap === "function") {
          try { window.htmx.swap(el, oob.html, { swapStyle: oob.swap || "outerHTML" }); }
          catch (e) { /* swallow — main response still runs */ }
        }
      });
    }

    // Override xhr "response" so htmx's beforeOnLoad / onLoad pipeline reads our data.
    try { Object.defineProperty(xhr, "status",       { value: status,    configurable: true }); } catch (e) { xhr.status = status; }
    try { Object.defineProperty(xhr, "statusText",   { value: "",        configurable: true }); } catch (e) {}
    try { Object.defineProperty(xhr, "readyState",   { value: 4,         configurable: true }); } catch (e) {}
    try { Object.defineProperty(xhr, "responseText", { value: html,      configurable: true }); } catch (e) { xhr.responseText = html; }
    try { Object.defineProperty(xhr, "response",     { value: html,      configurable: true }); } catch (e) { xhr.response = html; }

    var hxTrigger = msg.triggers ? JSON.stringify(msg.triggers) : null;
    var hxRedirect = (msg.headers && msg.headers["HX-Redirect"]) || null;
    var hxRefresh = (msg.headers && msg.headers["HX-Refresh"]) || null;

    xhr.getResponseHeader = function (h) {
      if (!h) return null;
      var key = h.toLowerCase();
      if (key === "hx-trigger" && hxTrigger) return hxTrigger;
      if (key === "hx-redirect" && hxRedirect) return hxRedirect;
      if (key === "hx-refresh" && hxRefresh) return hxRefresh;
      if (key === "content-type") return "text/html";
      return null;
    };
    xhr.getAllResponseHeaders = function () {
      var parts = ["content-type: text/html"];
      if (hxTrigger) parts.push("hx-trigger: " + hxTrigger);
      if (hxRedirect) parts.push("hx-redirect: " + hxRedirect);
      if (hxRefresh) parts.push("hx-refresh: " + hxRefresh);
      return parts.join("\r\n") + "\r\n";
    };

    if (typeof xhr.onload === "function") {
      try { xhr.onload({ target: xhr }); } catch (e) { /* swallow */ }
    } else if (typeof xhr.onreadystatechange === "function") {
      try { xhr.onreadystatechange(); } catch (e) { /* swallow */ }
    }
  }

  function failPending(id, code, message) {
    var entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    var xhr = entry.xhr;
    try { Object.defineProperty(xhr, "status", { value: 0, configurable: true }); } catch (e) { xhr.status = 0; }
    try { Object.defineProperty(xhr, "readyState", { value: 4, configurable: true }); } catch (e) {}
    if (typeof xhr.onerror === "function") {
      try { xhr.onerror({ target: xhr, code: code, message: message }); } catch (e) { /* swallow */ }
    }
  }

  function trySend(frame) {
    if (window.catapultWs && typeof window.catapultWs.send === "function") {
      return window.catapultWs.send(frame);
    }
    return false;
  }

  window.htmx.defineExtension("ws-rpc", {
    onEvent: function (name, evt) {
      if (name !== "htmx:beforeRequest") return true;

      var detail = evt.detail;
      if (!detail) return true;

      if (isMultipartElt(detail.elt)) return true; // fall through to native XHR

      var frame = buildFrame(detail);
      if (frame === null) return true;             // detected File in params

      pending.set(frame.id, { xhr: detail.xhr, detail: detail, frame: frame });

      if (navigator.onLine !== false && trySend(frame)) {
        // sent; htmx's XHR is short-circuited below.
      } else {
        buffer.push({ id: frame.id, frame: frame, ts: Date.now() });
      }
      // Prevent htmx from actually firing the XHR.
      if (typeof evt.preventDefault === "function") evt.preventDefault();
      return false;
    }
  });

  // Server-pushed responses arrive as "ws:response" custom events.
  document.addEventListener("ws:response", function (evt) {
    var msg = evt.detail;
    if (msg && msg.id && pending.has(msg.id)) applyResponse(msg);
  });

  // On reconnect, replay buffered GETs and reject buffered mutations.
  document.addEventListener("ws:open", function () {
    var now = Date.now();
    while (buffer.length) {
      var item = buffer.shift();
      if (now - item.ts > BUFFER_TTL_MS) {
        failPending(item.id, "TIMEOUT", "Buffered too long");
        continue;
      }
      if (item.frame.method === "GET") {
        if (!trySend(item.frame)) failPending(item.id, "DISCONNECTED", "Replay failed");
      } else {
        failPending(item.id, "DISCONNECTED", "Mutation not auto-replayed");
      }
    }
  });
})();
