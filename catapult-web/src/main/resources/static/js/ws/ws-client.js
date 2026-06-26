/*
 * catapult ws-client.js — single-connection WebSocket client.
 *
 * Public surface (window.catapultWs) :
 *   connect()
 *   send(frame)                      — fire-and-forget raw frame
 *   subscribe(channel, callback)     — server-resolved channel; cb receives the event message
 *   unsubscribe(channel, callback)
 *   request(action, params, timeout) — RPC over WS, returns a Promise<{result|error}>
 *   command(action, params)          — fire-and-forget action (no response expected)
 *
 * DOM events fired on document :
 *   ws:open, ws:closed, ws:degraded  — connection state
 *   ws:<type>    detail = full frame — every typed message (event, response, error, auth.ok, sub.ok, sub.denied)
 *
 * Reconnect strategy : exponential backoff capped at 30s + ±500ms jitter.
 * Watchdog : closes the socket if no message arrives in 45s (2 missed pings).
 * Subscriptions are remembered and re-sent on every (re)connect.
 * Pending RPC requests are rejected with code "DISCONNECTED" on close.
 */
(function (global) {
  "use strict";

  var ws = null;
  var attempts = 0;
  var watchdog = null;
  var nextRequestId = 1;

  var PING_TIMEOUT_MS = 45000;
  var DEFAULT_REQUEST_TIMEOUT_MS = 30000;

  /** channel (string) -> Set<callback> */
  var subscribers = new Map();
  /** request id (string) -> { resolve, reject, timer } */
  var pendingRequests = new Map();

  function wsUrl() {
    var proto = location.protocol === "https:" ? "wss:" : "ws:";
    return proto + "//" + location.host + "/ws";
  }

  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) {
      return;
    }
    try {
      ws = new WebSocket(wsUrl());
    } catch (e) {
      scheduleReconnect();
      return;
    }
    ws.onopen = onOpen;
    ws.onmessage = onMessage;
    ws.onclose = onClose;
    ws.onerror = function (e) { /* onclose will run; nothing to do here */ };
  }

  function onOpen() {
    attempts = 0;
    armWatchdog();
    document.dispatchEvent(new CustomEvent("ws:open"));
    // Promote the anonymous WS session by trading the session cookie for a
    // one-shot ticket. Subscriptions wait for ws:auth.ok so auth-gated
    // channels (notifications.user, events.admin) resolve correctly; on 401
    // we fall back to anonymous mode and still send them — public channels
    // succeed, private ones get sub.denied.
    authenticate();
  }

  function authenticate() {
    fetch("/ws/auth-ticket", { credentials: "same-origin" })
      .then(function (res) {
        if (!res.ok) { resubscribeAll(); return; }
        return res.json().then(function (body) {
          if (body && body.ticket) {
            send({ type: "auth", token: body.ticket });
            // resubscribeAll() runs from the ws:auth.ok listener below.
          } else {
            resubscribeAll();
          }
        });
      })
      .catch(function () { resubscribeAll(); });
  }

  function resubscribeAll() {
    subscribers.forEach(function (_cbs, ch) {
      send({ type: "subscribe", channel: ch });
    });
  }

  document.addEventListener("ws:auth.ok", resubscribeAll);

  function armWatchdog() {
    if (watchdog) clearTimeout(watchdog);
    watchdog = setTimeout(onPingTimeout, PING_TIMEOUT_MS);
  }

  function onPingTimeout() {
    document.dispatchEvent(new CustomEvent("ws:degraded"));
    try { if (ws) ws.close(); } catch (e) { /* ignore */ }
  }

  function onClose() {
    if (watchdog) { clearTimeout(watchdog); watchdog = null; }
    document.dispatchEvent(new CustomEvent("ws:closed"));
    // Reject pending RPCs — caller may choose to retry on reconnect.
    pendingRequests.forEach(function (entry) {
      if (entry.timer) clearTimeout(entry.timer);
      entry.reject({ code: "DISCONNECTED", message: "WebSocket closed before response" });
    });
    pendingRequests.clear();
    scheduleReconnect();
  }

  function scheduleReconnect() {
    var base = Math.min(30000, 500 * Math.pow(2, attempts));
    var delay = base + Math.random() * 500;
    attempts += 1;
    setTimeout(connect, delay);
  }

  function onMessage(e) {
    armWatchdog();
    var msg;
    try { msg = JSON.parse(e.data); } catch (err) { return; }

    if (msg.type === "ping") return;

    if (msg.type === "event" && msg.channel) {
      var cbs = subscribers.get(msg.channel);
      if (cbs) cbs.forEach(function (cb) { try { cb(msg); } catch (_) { /* swallow */ } });
    }

    if (msg.type === "response" && msg.id != null) {
      var pending = pendingRequests.get(msg.id);
      if (pending) {
        if (pending.timer) clearTimeout(pending.timer);
        pendingRequests.delete(msg.id);
        if (msg.ok) pending.resolve(msg);
        else pending.reject(msg);
      }
    }

    if (msg.type) {
      document.dispatchEvent(new CustomEvent("ws:" + msg.type, { detail: msg }));
    }
  }

  function send(frame) {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    try { ws.send(JSON.stringify(frame)); return true; }
    catch (e) { return false; }
  }

  function subscribe(channel, callback) {
    if (typeof channel !== "string" || !channel) return;
    var set = subscribers.get(channel);
    if (!set) {
      set = new Set();
      subscribers.set(channel, set);
      send({ type: "subscribe", channel: channel });
    }
    if (typeof callback === "function") set.add(callback);
  }

  function unsubscribe(channel, callback) {
    var set = subscribers.get(channel);
    if (!set) return;
    if (typeof callback === "function") {
      set.delete(callback);
    } else {
      set.clear();
    }
    if (set.size === 0) {
      subscribers.delete(channel);
      send({ type: "unsubscribe", channel: channel });
    }
  }

  function newRequestId() {
    return "r-" + (nextRequestId++) + "-" + Date.now().toString(36);
  }

  /**
   * Send a request and return a Promise that resolves with the response frame
   * (`{ok: true, result, ...}`) or rejects with the error frame.
   * @param action  string — server-side action name (e.g. "search.twitch.categories")
   * @param params  object — payload merged into the frame
   * @param timeoutMs  optional override (defaults to 30s)
   */
  function request(action, params, timeoutMs) {
    return new Promise(function (resolve, reject) {
      var id = newRequestId();
      var timeout = timeoutMs || DEFAULT_REQUEST_TIMEOUT_MS;
      var timer = setTimeout(function () {
        if (pendingRequests.has(id)) {
          pendingRequests.delete(id);
          reject({ code: "TIMEOUT", message: "Request " + action + " timed out after " + timeout + "ms" });
        }
      }, timeout);

      pendingRequests.set(id, { resolve: resolve, reject: reject, timer: timer });

      var frame = Object.assign({ type: "request", id: id, action: action }, { params: params || {} });
      if (!send(frame)) {
        clearTimeout(timer);
        pendingRequests.delete(id);
        reject({ code: "DISCONNECTED", message: "WebSocket not open" });
      }
    });
  }

  /** Fire-and-forget action; no correlation, no response expected. */
  function command(action, params) {
    return send({ type: "command", action: action, params: params || {} });
  }

  global.catapultWs = {
    connect: connect,
    send: send,
    subscribe: subscribe,
    unsubscribe: unsubscribe,
    request: request,
    command: command
  };

  /**
   * Send a MVC request over WebSocket and return a Promise that resolves with
   * the response frame (`{ok, html, status, target?, swap?, oob?, triggers?}`).
   * Falls back to `{ok: false, error: {code: "WS_CLOSED"}}` when the socket is
   * not open.
   * @param opts  {method?, path, headers?, params?}
   */
  global.catapultWs.mvc = function (opts) {
    return new Promise(function (resolve) {
      var id = "m-" + Date.now() + "-" + Math.random().toString(36).slice(2, 6);
      var frame = {
        type: "request",
        id: id,
        action: "mvc",
        method: (opts.method || "GET").toUpperCase(),
        path: opts.path,
        headers: Object.assign({ "HX-Request": "true" }, opts.headers || {}),
        params: opts.params || {},
        csrfToken: window.__wsCsrfToken || null
      };
      var onResp = function (e) {
        if (!e.detail || e.detail.id !== id) return;
        document.removeEventListener("ws:response", onResp);
        resolve(e.detail);
      };
      document.addEventListener("ws:response", onResp);
      if (!global.catapultWs.send(frame)) {
        document.removeEventListener("ws:response", onResp);
        resolve({ ok: false, error: { code: "WS_CLOSED" } });
      }
    });
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", connect);
  } else {
    connect();
  }
})(window);
