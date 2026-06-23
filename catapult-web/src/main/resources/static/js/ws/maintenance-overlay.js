/*
 * catapult maintenance-overlay.js — blocking overlay state machine.
 *
 * Listens to ws-client.js custom events on `document`:
 *   ws:open        — fires on first connect AND every reconnect (we
 *                    distinguish via a local `wasConnected` flag).
 *   ws:closed      — connection dropped.
 *   ws:degraded    — watchdog ping timeout.
 *   ws:event       — generic event frame (detail = EventMessage).
 *
 * State machine: live | degraded | unreachable | shutdown | scheduled.
 *
 * Accessibility :
 *   - inert on <main> so the rest of the page is non-interactive
 *     (HTML native, but script listeners inside <main> keep running — that
 *     is intentional, the WS dispatcher still receives events).
 *   - previous focus saved, restored on hide.
 *   - focus trap on the overlay card (Tab cycles inside).
 *
 * Public surface (window.catapultOverlay):
 *   showToast(msg, opts)  — small helper, used by other modules.
 *   _state()              — for debugging.
 */
(function (global) {
  "use strict";

  var STATE = { LIVE: "live", DEGRADED: "degraded", UNREACHABLE: "unreachable",
                SHUTDOWN: "shutdown", SCHEDULED: "scheduled" };

  var INITIAL_CONNECT_TIMEOUT_MS = 5000;
  var SHUTDOWN_STICKY_MS         = 10000;   // prefer "shutdown" over "degraded" within this window
  var TOAST_DEFAULT_MS           = 3000;

  var state           = STATE.LIVE;
  var wasConnected    = false;
  var shutdownStickyUntil = 0;
  var countdownTimer  = null;
  var retryTimer      = null;
  var initialTimer    = null;
  var previousFocus   = null;
  var trapHandler     = null;

  var overlay, card, titleEl, msgEl, countdownEl, retryInfoEl, retryInEl, retryNEl, toastContainer;

  // ── DOM bootstrap ──────────────────────────────────────────────────────
  function bind() {
    overlay        = document.getElementById("maintenance-overlay");
    if (!overlay) return false;
    card           = overlay.querySelector(".overlay-card");
    titleEl        = document.getElementById("m-title");
    msgEl          = document.getElementById("m-msg");
    countdownEl    = document.getElementById("m-countdown");
    retryInfoEl    = document.getElementById("m-retry-info");
    retryInEl      = document.getElementById("m-retry-in");
    retryNEl       = document.getElementById("m-retry-n");
    toastContainer = document.getElementById("toast-container");
    return true;
  }

  // ── Variant copy ───────────────────────────────────────────────────────
  var VARIANTS = {
    degraded:    { title: "Connexion perdue",
                   msg:   "Tentative de reconnexion…",
                   showRetry: true },
    unreachable: { title: "Service indisponible",
                   msg:   "Le serveur ne répond pas. Nouvelle tentative en cours.",
                   showRetry: true },
    shutdown:    { title: "Redémarrage en cours",
                   msg:   "Le service va redémarrer. Tes données sont préservées.",
                   showRetry: false },
    scheduled:   { title: "Maintenance en cours",
                   msg:   "Retour estimé bientôt.",
                   showRetry: false }
  };

  // ── Show / hide ────────────────────────────────────────────────────────
  function showOverlay(cause) {
    if (!overlay) return;
    var variant = VARIANTS[cause] || VARIANTS.degraded;
    card.setAttribute("data-cause", cause);
    titleEl.textContent = variant.title;
    msgEl.textContent   = variant.msg;
    retryInfoEl.hidden  = !variant.showRetry;

    if (overlay.hidden) {
      overlay.hidden = false;
      previousFocus = document.activeElement;
      applyInert(true);
      // Defer focus so screen readers pick up the dialog.
      setTimeout(function () { try { titleEl.focus(); } catch (_) {} }, 50);
      attachFocusTrap();
    }
    state = cause;
  }

  function hideOverlay() {
    if (!overlay || overlay.hidden) { state = STATE.LIVE; return; }
    overlay.classList.add("fading-out");
    var reduceMotion = matchMedia && matchMedia("(prefers-reduced-motion: reduce)").matches;
    var delay = reduceMotion ? 0 : 300;
    setTimeout(function () {
      overlay.hidden = true;
      overlay.classList.remove("fading-out");
      stopCountdown();
      stopRetryUI();
      applyInert(false);
      detachFocusTrap();
      try { if (previousFocus && previousFocus.focus) previousFocus.focus(); } catch (_) {}
      previousFocus = null;
      state = STATE.LIVE;
    }, delay);
  }

  function applyInert(on) {
    var mains = document.querySelectorAll("main");
    mains.forEach(function (m) {
      if (on) m.setAttribute("inert", "");
      else    m.removeAttribute("inert");
    });
  }

  // ── Focus trap ─────────────────────────────────────────────────────────
  function attachFocusTrap() {
    detachFocusTrap();
    trapHandler = function (e) {
      if (e.key !== "Tab" || overlay.hidden) return;
      var focusables = card.querySelectorAll(
        "button, [href], input, select, textarea, [tabindex]:not([tabindex='-1'])");
      if (focusables.length === 0) { e.preventDefault(); titleEl.focus(); return; }
      var first = focusables[0];
      var last  = focusables[focusables.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault(); last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault(); first.focus();
      }
    };
    document.addEventListener("keydown", trapHandler, true);
  }

  function detachFocusTrap() {
    if (trapHandler) {
      document.removeEventListener("keydown", trapHandler, true);
      trapHandler = null;
    }
  }

  // ── Countdown ──────────────────────────────────────────────────────────
  function startCountdown(seconds) {
    stopCountdown();
    if (typeof seconds !== "number" || seconds <= 0) { countdownEl.hidden = true; return; }
    var remaining = Math.max(0, Math.floor(seconds));
    countdownEl.hidden = false;
    countdownEl.textContent = remaining + "s";
    countdownTimer = setInterval(function () {
      remaining -= 1;
      if (remaining <= 0) { countdownEl.textContent = "0s"; stopCountdown(); return; }
      countdownEl.textContent = remaining + "s";
    }, 1000);
  }

  function stopCountdown() {
    if (countdownTimer) { clearInterval(countdownTimer); countdownTimer = null; }
  }

  // ── Retry visual (best-effort, ws-client owns the real backoff) ────────
  function startRetryUI() {
    stopRetryUI();
    var attempt = 1;
    var nextDelay = 3;
    if (retryNEl) retryNEl.textContent = String(attempt);
    if (retryInEl) retryInEl.textContent = String(nextDelay);
    retryTimer = setInterval(function () {
      nextDelay -= 1;
      if (nextDelay <= 0) {
        attempt += 1;
        nextDelay = Math.min(30, 3 * attempt);
        if (retryNEl) retryNEl.textContent = String(attempt);
      }
      if (retryInEl) retryInEl.textContent = String(nextDelay);
    }, 1000);
  }

  function stopRetryUI() {
    if (retryTimer) { clearInterval(retryTimer); retryTimer = null; }
  }

  // ── Toast ──────────────────────────────────────────────────────────────
  function showToast(msg, opts) {
    if (!toastContainer) return;
    opts = opts || {};
    var t = document.createElement("div");
    t.className = "toast" + (opts.kind ? " toast-" + opts.kind : "");
    t.textContent = msg;
    toastContainer.appendChild(t);
    // trigger transition
    requestAnimationFrame(function () { t.classList.add("visible"); });
    var duration = opts.duration || TOAST_DEFAULT_MS;
    setTimeout(function () {
      t.classList.remove("visible");
      setTimeout(function () { if (t.parentNode) t.parentNode.removeChild(t); }, 250);
    }, duration);
  }

  // ── State machine reactions ────────────────────────────────────────────
  function onWsOpen() {
    if (initialTimer) { clearTimeout(initialTimer); initialTimer = null; }
    if (!wasConnected) {
      wasConnected = true;
      // First successful connect — if we showed an "unreachable" overlay during
      // the initial 5s wait, hide it now silently (no toast on first connect).
      if (state === STATE.UNREACHABLE) hideOverlay();
      return;
    }
    // Reconnect.
    hideOverlay();
    showToast("Connexion rétablie", { kind: "success", duration: 3000 });
  }

  function onWsDegraded() {
    if (inShutdownWindow()) return;          // prefer shutdown variant
    if (state === STATE.SHUTDOWN) return;
    showOverlay(STATE.DEGRADED);
    startRetryUI();
  }

  function onWsClosed() {
    if (inShutdownWindow()) return;
    if (state === STATE.SHUTDOWN) return;
    // Defensive : if we were connected and the watchdog didn't fire (e.g. tab
    // came back from sleep), still show degraded.
    if (wasConnected && state === STATE.LIVE) {
      showOverlay(STATE.DEGRADED);
      startRetryUI();
    }
  }

  function onWsEvent(evt) {
    var msg = evt.detail || {};
    var name = msg.name;
    var data = msg.data || {};
    if (name === "maintenance.imminent") {
      shutdownStickyUntil = Date.now() + SHUTDOWN_STICKY_MS;
      showOverlay(STATE.SHUTDOWN);
      if (typeof data.etaSeconds === "number") startCountdown(data.etaSeconds);
    } else if (name === "maintenance.cancelled") {
      if (state === STATE.SCHEDULED) hideOverlay();
    } else if (name === "maintenance.scheduled") {
      // The banner handles distant scheduled maintenance; the overlay only
      // takes over once we are actually inside the window (handled by banner).
    }
  }

  function inShutdownWindow() {
    return Date.now() < shutdownStickyUntil;
  }

  // ── Bootstrap ──────────────────────────────────────────────────────────
  function init() {
    if (!bind()) return;
    document.addEventListener("ws:open",      onWsOpen);
    document.addEventListener("ws:closed",    onWsClosed);
    document.addEventListener("ws:degraded",  onWsDegraded);
    document.addEventListener("ws:event",     onWsEvent);

    // Detect "initial connection impossible" : if no ws:open fires within 5s
    // of page load, show the unreachable overlay. Hidden silently when the
    // first ws:open eventually arrives (see onWsOpen).
    initialTimer = setTimeout(function () {
      if (!wasConnected) {
        showOverlay(STATE.UNREACHABLE);
        startRetryUI();
      }
    }, INITIAL_CONNECT_TIMEOUT_MS);
  }

  global.catapultOverlay = {
    showToast: showToast,
    _state: function () { return { state: state, wasConnected: wasConnected,
                                   shutdownStickyUntil: shutdownStickyUntil }; }
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})(window);
