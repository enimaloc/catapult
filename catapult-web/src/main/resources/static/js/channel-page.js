/*
 * channel-page.js — subscribes to `channel.viewed.<ownerId>` and dispatches
 * server-pushed events to per-domain handlers. Loaded only on the channel
 * page (template gates the script tag on a non-null channelUser).
 *
 * V1 strategy: every known event triggers a debounced page reload. This is
 * a deliberately simple approach that avoids client-side templating and
 * i18n duplication while the event surface is still small. P5 will replace
 * the reload with targeted DOM mutations for high-frequency events
 * (stream.state.changed, game.detected) where the visible flash matters.
 *
 * Debouncing matters: several events may fire back-to-back during a single
 * mutation (e.g. saving CCL settings triggers settings.updated + a possible
 * bindings recompute). Without debounce we'd reload mid-render.
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

  // Events known to this client version. Anything outside this set is
  // silently ignored — lets the server ship new events without crashing
  // older clients.
  var RELOAD_EVENTS = {
    "bot.toggled": true,
    "stream.state.changed": true,
    "game.detected": true,
    "game.cleared": true,
    "binding.upserted": true,
    "binding.deleted": true,
    "settings.updated": true,
    "steam.profile.changed": true,
    "connection.changed": true
  };

  var reloadScheduled = false;
  function scheduleReload() {
    if (reloadScheduled) return;
    reloadScheduled = true;
    // 250ms debounce — long enough to absorb a burst of related events
    // from a single mutation, short enough to feel instant to the viewer.
    setTimeout(function () { window.location.reload(); }, 250);
  }

  function onEvent(msg) {
    if (!msg || msg.channel !== publicChannel) return;
    if (RELOAD_EVENTS[msg.name]) scheduleReload();
  }

  function subscribe() {
    if (!window.catapultWs) return;
    window.catapultWs.subscribe(publicChannel, onEvent);
  }

  // Wait for ws:auth.ok before subscribing — channel.viewed.* is auth-gated
  // (ChannelResolver calls /api/users/.../channel-access). Subscribing before
  // auth.ok would get sub.denied.
  document.addEventListener("ws:auth.ok", subscribe);
}());
