/*
 * ws-actions.js — minimal htmx-style binder for `data-ws-*` attributes emitted
 * by the WsDialect Thymeleaf processor.
 *
 * Markup contract (set server-side by WsDialect, e.g. ws:post="@{/...}"):
 *   data-ws-method="GET|POST|PUT|DELETE"
 *   data-ws-path="/path/with/already/resolved/letiables"
 *   data-ws-target="#css-selector"          (optional; if set, response HTML
 *                                            is swapped into this element)
 *   data-ws-swap="outerHTML|innerHTML|none|delete"   (default: outerHTML)
 *   data-ws-trigger="submit|click|load|every Ns"     (default: submit for
 *                                                     <form>, click otherwise)
 *
 * Behaviour:
 *   - On the configured trigger, build an HTMX-shaped WS request frame
 *     (action="htmx") and send it through window.catapultWs.
 *   - For <form>, collect named field values via FormData and pass as params.
 *   - When the response arrives, optionally swap response.html into target.
 *   - "load" triggers fire once after ws:auth.resolved (so auth-gated
 *     endpoints work) — and only if neither ws:open nor auth has happened
 *     they wait quietly.
 *
 * The frame shape mirrors the existing htmx-over-WS dispatcher contract so
 * server-side controllers see no difference.
 */
(function () {
  "use strict";

  if (!window.catapultWs) {
    console.warn("ws-actions: catapultWs not loaded, dialect bindings inactive");
    return;
  }

  let authResolved = false;
  document.addEventListener("ws:auth.resolved", function () {
    authResolved = true;
    // Fire any deferred load triggers now that auth is settled.
    pendingLoadElements.forEach(function (el) { sendFor(el); });
    pendingLoadElements.length = 0;
  });
  // Conservative back-compat: if the client only ever sees ws:auth.ok (older
  // ws-client without the resolved event), treat that as resolution too.
  document.addEventListener("ws:auth.ok", function () {
    if (!authResolved) {
      authResolved = true;
      pendingLoadElements.forEach(function (el) { sendFor(el); });
      pendingLoadElements.length = 0;
    }
  });

  let pendingLoadElements = [];
  let nextId = 1;
  function newId() { return "w-" + (nextId++) + "-" + Math.random().toString(36).slice(2, 8); }

  function bind(root) {
    let elements = (root || document).querySelectorAll("[data-ws-path]");
    elements.forEach(function (el) {
      if (el.__wsBound) return;
      el.__wsBound = true;
      let trigger = (el.dataset.wsTrigger || defaultTriggerFor(el)).trim();
      if (trigger === "load") {
        if (authResolved) sendFor(el);
        else pendingLoadElements.push(el);
        return;
      }
      if (trigger.indexOf("every ") === 0) {
        let ms = parseInterval(trigger.substring(6));
        if (ms > 0) setInterval(function () { sendFor(el); }, ms);
        return;
      }
      // submit / click / custom DOM event
      let eventName = trigger === "submit" ? "submit" : (trigger === "click" ? "click" : trigger);
      el.addEventListener(eventName, function (evt) {
        if (eventName === "submit" || eventName === "click") evt.preventDefault();
        sendFor(el);
      });
    });
  }

  function defaultTriggerFor(el) {
    return el.tagName === "FORM" ? "submit" : "click";
  }

  function parseInterval(s) {
    // "3s" → 3000, "500ms" → 500
    let m = String(s).trim().match(/^(\d+)\s*(ms|s)?$/);
    if (!m) return 0;
    let n = parseInt(m[1], 10);
    return m[2] === "ms" ? n : n * 1000;
  }

  function sendFor(el) {
    let method = (el.dataset.wsMethod || "GET").toUpperCase();
    let path = el.dataset.wsPath;
    if (!path) return;
    let id = newId();
    let params = collectParams(el);
    let headers = { "HX-Request": "true" };
    let frame = {
      type: "request",
      id: id,
      action: "htmx",
      method: method,
      path: path,
      headers: headers,
      params: params,
      csrfToken: window.__wsCsrfToken || null
    };
    let onResponse = function (e) {
      if (!e.detail || e.detail.id !== id) return;
      document.removeEventListener("ws:response", onResponse);
      applyResponse(el, e.detail);
    };
    document.addEventListener("ws:response", onResponse);
    if (!window.catapultWs.send(frame)) {
      document.removeEventListener("ws:response", onResponse);
    }
  }

  function collectParams(el) {
    if (el.tagName === "FORM") {
      let fd = new FormData(el);
      let out = {};
      fd.forEach(function (value, key) {
        // Skip File entries — multipart isn't supported on the WS path.
        if (typeof File !== "undefined" && value instanceof File) return;
        if (Object.prototype.hasOwnProperty.call(out, key)) {
          if (!Array.isArray(out[key])) out[key] = [out[key]];
          out[key].push(value);
        } else {
          out[key] = value;
        }
      });
      return out;
    }
    return {};
  }

  function applyResponse(el, msg) {
    if (msg.ok === false) return; // error envelope — leave UI untouched
    let swap = (el.dataset.wsSwap || "outerHTML").toLowerCase();
    if (swap === "none" || !msg.html) return;
    let targetSelector = el.dataset.wsTarget;
    let target = targetSelector ? document.querySelector(targetSelector) : el;
    if (!target) return;
    // HTML is server-rendered by Thymeleaf (escapes by default) but we still
    // route everything through DOMParser + node moves rather than innerHTML /
    // insertAdjacentHTML so this code path can never become an XSS sink even
    // if a template author later forgets th:utext defensively.
    let parsed = new DOMParser().parseFromString(msg.html, "text/html");
    let bodyChildren = Array.prototype.slice.call(parsed.body.childNodes);
    if (swap === "outerhtml") {
      let rep = targetSelector ? parsed.querySelector(targetSelector) : parsed.body.firstElementChild;
      if (rep) {
        target.replaceWith(rep);
        bind(rep);
      }
    } else if (swap === "innerhtml") {
      while (target.firstChild) target.removeChild(target.firstChild);
      bodyChildren.forEach(function (n) { target.appendChild(n); });
      bind(target);
    } else if (swap === "delete") {
      target.remove();
    } else if (swap === "beforeend") {
      bodyChildren.forEach(function (n) { target.appendChild(n); });
      bind(target);
    } else if (swap === "afterbegin") {
      let first = target.firstChild;
      bodyChildren.forEach(function (n) { target.insertBefore(n, first); });
      bind(target);
    }
  }

  // Capture the WS-issued CSRF token so we can echo it on every mutation —
  // ws-client.js currently keeps it inside the htmx-ws-extension; expose it
  // via window so this script can use the same value without coupling to that
  // extension.
  document.addEventListener("ws:auth.ok", function (evt) {
    if (evt && evt.detail && evt.detail.csrfToken) {
      window.__wsCsrfToken = evt.detail.csrfToken;
    }
  });
  document.addEventListener("ws:closed", function () { window.__wsCsrfToken = null; });

  function init() { bind(document); }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
}());
