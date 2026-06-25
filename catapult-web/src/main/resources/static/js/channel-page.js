/*
 * channel-page.js — subscribes to `channel.viewed.<ownerId>` and dispatches
 * server-pushed events to per-domain handlers. Loaded only on the channel
 * page (template gates the script tag on a non-null channelUser).
 *
 * Phase 3 ships a single handler: bot.toggled triggers a full page reload.
 * Reload is a deliberately simple V1 strategy that avoids client-side
 * templating and i18n duplication; later phases will replace it with
 * targeted DOM mutations as each domain handler is fleshed out.
 *
 * Event envelope (S → C):
 *   { type:"event", channel:"channel.viewed.<uuid>", name:"bot.toggled",
 *     data:{ channelId:"<uuid>", enabled:true } }
 */
(function () {
  "use strict";

  var meta = document.querySelector('meta[name="channel-owner-id"]');
  if (!meta || !meta.content) return; // not on a channel page
  var channelOwnerId = meta.content;
  var publicChannel = "channel.viewed." + channelOwnerId;

  function onEvent(msg) {
    if (!msg || msg.channel !== publicChannel) return;
    switch (msg.name) {
      case "bot.toggled":
        // V1: reload to pull the new server-rendered state. Phase 4 will
        // replace this with targeted DOM updates for high-frequency events.
        window.location.reload();
        break;
      default:
        break;
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
}());
