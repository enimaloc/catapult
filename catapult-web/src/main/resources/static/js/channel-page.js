/*
 * channel-page.js — subscribes to `channel.viewed.<ownerId>` and dispatches
 * server-pushed events to per-domain handlers that mutate the page in place
 * (no reload). Loaded only on the channel page (template gates the script
 * tag on a non-null channelUser).
 *
 * Two flavours of handler:
 *
 *  - Pure client-side (event data is enough): bot.toggled, stream.state.changed,
 *    game.detected, game.cleared, binding.deleted. The status fragment renders
 *    both states of each toggleable block with hidden=true on the inactive
 *    one; handlers flip visibility on event.
 *
 *  - Fetch-and-swap (event triggers a server re-render of a small section):
 *    steam.profile.changed + connection.changed (complex provider-state UI).
 *    Endpoint URLs are scoped to one section, not the full page.
 *
 * Event envelope (S → C):
 *   { type:"event", channel:"channel.viewed.<uuid>", name:"...",
 *     data:{ channelId:"<uuid>", ... } }
 */
(function () {
  "use strict";

  var meta = document.querySelector('meta[name="channel-owner-id"]');
  if (!meta || !meta.content) return; // not on a channel page
  var channelOwnerId = meta.content;
  var publicChannel = "channel.viewed." + channelOwnerId;

  // channelUsername drives the fetch URLs for refetch-and-swap handlers.
  var usernameMeta = document.querySelector('meta[name="channel-username"]');
  var channelUsername = usernameMeta ? usernameMeta.content : null;

  // ── Pure client-side handlers ─────────────────────────────────────────────

  function setHidden(id, hidden) {
    var el = document.getElementById(id);
    if (el) el.hidden = !!hidden;
  }

  function onBotToggled(data) {
    setHidden("bot-status-active", !data.enabled);
    setHidden("bot-status-inactive", data.enabled);
  }

  function onStreamStateChanged(data) {
    setHidden("stream-status-live", !data.isLive);
    setHidden("stream-status-offline", data.isLive);
  }

  function onGameDetected(data) {
    var name = document.getElementById("current-game-name");
    var src = document.getElementById("current-game-source");
    if (name) name.textContent = data.sourceName || "";
    if (src) src.textContent = data.sourceType || "";
    setHidden("current-game-info", false);
    setHidden("current-game-none", true);
  }

  function onGameCleared() {
    setHidden("current-game-info", true);
    setHidden("current-game-none", false);
  }

  function onBindingDeleted(data) {
    if (!data || !data.bindingId) return;
    var row = document.querySelector(
        'tbody[data-binding-id="' + cssEscape(data.bindingId) + '"]');
    if (row) row.remove();
  }

  // ── Fetch-and-swap handlers ───────────────────────────────────────────────

  function refetchAndSwap(path, targetSelector) {
    if (!channelUsername) return;
    catapultWs.mvc({ method: "GET", path: path }).then(function (msg) {
      if (!msg.ok || !msg.html) return;
      var target = document.querySelector(targetSelector);
      if (!target) return;
      var parsed = new DOMParser().parseFromString(msg.html, "text/html");
      var rep = parsed.querySelector(targetSelector);
      if (rep) target.replaceWith(rep);
    });
  }

  function onBindingUpserted(data) {
    if (!data || !data.binding) return;
    var bindingId = data.binding.id;
    var existing = document.querySelector(
        'tbody[data-binding-id="' + cssEscape(bindingId) + '"]');
    var fresh = window.CatapultBindings.renderBindingRow(data.binding);
    if (existing) {
      // Preserve hidden edit rows — they contain server-rendered CCL checkboxes
      // that we don't reconstruct from the event payload.
      Array.from(existing.querySelectorAll("tr.edit-row")).forEach(function (r) {
        fresh.appendChild(r);
      });
      existing.replaceWith(fresh);
    } else {
      var table = document.querySelector("#bindings-card table");
      if (table) table.appendChild(fresh);
    }
  }

  function onSettingsUpdated(data) {
    if (!data || !data.settings) return;
    if (window.CatapultSettings) window.CatapultSettings.applySettings(data.settings);
  }

  function onConnectionsChanged() {
    refetchAndSwap("/channels/" + encodeURIComponent(channelUsername) + "/fragments/connections",
                   "#connections-section");
  }

  // ── Dispatch ──────────────────────────────────────────────────────────────

  var HANDLERS = {
    "bot.toggled":           onBotToggled,
    "stream.state.changed":  onStreamStateChanged,
    "game.detected":         onGameDetected,
    "game.cleared":          onGameCleared,
    "binding.deleted":       onBindingDeleted,
    "binding.upserted":      onBindingUpserted,
    "settings.updated":      onSettingsUpdated,
    "steam.profile.changed": onConnectionsChanged,
    "connection.changed":    onConnectionsChanged
  };

  function onEvent(msg) {
    if (!msg || msg.channel !== publicChannel) return;
    var fn = HANDLERS[msg.name];
    if (fn) {
      try { fn(msg.data || {}); }
      catch (e) { /* swallow — handler error shouldn't crash the dispatcher */ }
    }
  }

  function subscribe() {
    if (!window.catapultWs) return;
    window.catapultWs.subscribe(publicChannel, onEvent);
  }

  // Wait for ws:auth.ok before subscribing — channel.viewed.* is auth-gated
  // (ChannelResolver calls /api/users/.../channel-access). Subscribing before
  // auth.ok would get sub.denied.
  document.addEventListener("ws:auth.ok", subscribe);

  // CSS.escape polyfill for older browsers — needed when building the
  // attribute selector for data-binding-id (UUIDs contain hyphens which
  // are fine, but be defensive).
  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) {
      return "\\" + c.charCodeAt(0).toString(16) + " ";
    });
  }
}());
