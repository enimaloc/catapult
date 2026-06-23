/*
 * catapult maintenance-banner.js — preventive, non-blocking, dismissible.
 *
 * Listens to `ws:event` frames from ws-client.js:
 *   maintenance.scheduled  — show banner with countdown if startsAt is > 5min away
 *   maintenance.cancelled  — hide banner (and forget dismissal for that schedule)
 *
 * Once the maintenance window is < 5min away, this module steps aside and the
 * overlay (driven by maintenance.imminent from the lifecycle hook) takes over.
 *
 * Dismissals are remembered per scheduled `startsAt` in sessionStorage so a
 * page reload during the same tab session doesn't bring back a closed banner.
 */
(function (global) {
  "use strict";

  var WINDOW_MS = 5 * 60 * 1000;   // banner active while > 5min away
  var TICK_MS   = 30 * 1000;        // refresh countdown every 30s

  var banner, timeEl, countdownEl, messageEl, dismissBtn;
  var current = null;    // { startsAt: Date, durationMinutes?, message? }
  var tickTimer = null;

  function bind() {
    banner      = document.getElementById("maintenance-banner");
    if (!banner) return false;
    timeEl      = document.getElementById("b-time");
    countdownEl = document.getElementById("b-countdown");
    messageEl   = document.getElementById("b-message");
    dismissBtn  = document.getElementById("b-dismiss");
    if (dismissBtn) dismissBtn.addEventListener("click", onDismiss);
    return true;
  }

  function dismissKey(startsAtIso) {
    return "maintenance-banner-dismissed-" + startsAtIso;
  }

  function isDismissed(startsAtIso) {
    try { return sessionStorage.getItem(dismissKey(startsAtIso)) === "1"; }
    catch (_) { return false; }
  }

  function rememberDismissed(startsAtIso) {
    try { sessionStorage.setItem(dismissKey(startsAtIso), "1"); } catch (_) {}
  }

  function clearDismissed(startsAtIso) {
    try { sessionStorage.removeItem(dismissKey(startsAtIso)); } catch (_) {}
  }

  function formatTime(d) {
    try {
      return d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
    } catch (_) { return d.toISOString(); }
  }

  function formatCountdown(ms) {
    var totalMin = Math.max(0, Math.round(ms / 60000));
    if (totalMin >= 60) {
      var h = Math.floor(totalMin / 60);
      var m = totalMin - h * 60;
      return "dans " + h + "h" + (m > 0 ? (m < 10 ? "0" + m : m) : "");
    }
    return "dans " + totalMin + " min";
  }

  function render() {
    if (!current || !banner) return;
    var deltaMs = current.startsAt.getTime() - Date.now();
    if (deltaMs <= WINDOW_MS) {
      // Hand-off to overlay; the banner steps aside (the actual switch is
      // driven by a separate maintenance.imminent event from the server).
      hide();
      return;
    }
    timeEl.textContent      = formatTime(current.startsAt);
    countdownEl.textContent = formatCountdown(deltaMs);
    if (messageEl) {
      messageEl.textContent = current.message ? " — " + current.message : "";
    }
    banner.hidden = false;
  }

  function startTicking() {
    if (tickTimer) clearInterval(tickTimer);
    tickTimer = setInterval(render, TICK_MS);
  }

  function stopTicking() {
    if (tickTimer) { clearInterval(tickTimer); tickTimer = null; }
  }

  function show(startsAtIso, data) {
    var startsAt = new Date(startsAtIso);
    if (isNaN(startsAt.getTime())) return;
    if (isDismissed(startsAtIso)) return;
    var deltaMs = startsAt.getTime() - Date.now();
    if (deltaMs <= WINDOW_MS) return;     // overlay's job, not ours
    current = {
      startsAt: startsAt,
      startsAtIso: startsAtIso,
      durationMinutes: data.durationMinutes,
      message: data.message
    };
    render();
    startTicking();
  }

  function hide() {
    if (banner) banner.hidden = true;
    stopTicking();
  }

  function onDismiss() {
    if (current) rememberDismissed(current.startsAtIso);
    hide();
  }

  function onWsEvent(evt) {
    var msg = evt.detail || {};
    var name = msg.name;
    var data = msg.data || {};
    if (name === "maintenance.scheduled") {
      var iso = data.startsAt;
      if (typeof iso !== "string") return;
      show(iso, data);
    } else if (name === "maintenance.cancelled") {
      if (data.originalStartsAt) clearDismissed(data.originalStartsAt);
      current = null;
      hide();
    }
  }

  function init() {
    if (!bind()) return;
    document.addEventListener("ws:event", onWsEvent);
  }

  global.catapultBanner = { _current: function () { return current; } };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})(window);
