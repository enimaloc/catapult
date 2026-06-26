/*
 * channel-handlers/connections.js — client-side connections panel updater.
 *
 * Exposes window.CatapultConnections with two methods:
 *
 *  - applyProvider(providerDto)   — called by connection.changed events.
 *    providerDto: { provider: "STEAM"|"TWITCH", connected: boolean,
 *                   profile?: SteamProfileDto }
 *
 *  - applySteamProfile(profileDto) — called directly by steam.profile.changed
 *    events and internally by applyProvider when provider == "STEAM".
 *    profileDto: { hasSteam, profilePrivate, offlineMode, rateLimited,
 *                  ttlMinutes, hasPersonalToken, tokenShared }
 *
 * Blocks are located by [data-conn-block="<name>"] selectors added to
 * connections.html. Non-owner blocks are not present in the DOM for viewers,
 * so querySelector returns null and the toggle is a safe no-op.
 */
(function (root) {
  "use strict";

  /** Show or hide an element found by its data-conn-block value. */
  function toggle(connBlock, visible) {
    var el = document.querySelector('[data-conn-block="' + connBlock + '"]');
    if (el) el.hidden = !visible;
  }

  /**
   * Updates all Steam-specific blocks in the connections panel from
   * a SteamProfileDto payload.
   */
  function applySteamProfile(p) {
    if (!p) return;
    var hasSteam         = !!p.hasSteam;
    var profilePrivate   = !!p.profilePrivate;
    var offlineMode      = !!p.offlineMode;
    var rateLimited      = !!p.rateLimited;
    var hasPersonalToken = !!p.hasPersonalToken;
    var tokenShared      = !!p.tokenShared;

    // Connection badges (visible to all viewers)
    toggle("steam-connected",    hasSteam);
    toggle("steam-disconnected", !hasSteam);

    // Rate-limited button (owner-only; safe no-op for viewers)
    toggle("steam-rate-limit-btn", hasSteam && rateLimited);

    // Offline mode warning + cache TTL text
    toggle("steam-offline-warning", hasSteam && offlineMode);
    if (hasSteam && offlineMode && p.ttlMinutes != null) {
      var ttlEl = document.querySelector('[data-field="steam-ttl-minutes"]');
      if (ttlEl) ttlEl.textContent = String(p.ttlMinutes);
    }

    // Private profile warning
    toggle("steam-private-warning", hasSteam && profilePrivate && !hasPersonalToken);

    // Personal token section
    toggle("steam-personal-token-ui", hasSteam && hasPersonalToken);
    if (hasSteam && hasPersonalToken) {
      var sharedChk = document.querySelector('[data-field="steam-token-shared"]');
      if (sharedChk) sharedChk.checked = tokenShared;
    }

    // Invite to add a personal token (shown when profile is public and no token yet)
    toggle("steam-add-token-btn", hasSteam && !hasPersonalToken && !profilePrivate);

    // Connect / disconnect actions
    toggle("steam-disconnect-form", hasSteam);
    toggle("steam-connect-link",    !hasSteam);
  }

  /**
   * Applies a ProviderConnectionsDto to the connections panel.
   * For STEAM, delegates to applySteamProfile when a profile snapshot is
   * present, or synthesises a minimal snapshot when the provider disconnected.
   */
  function applyProvider(providerDto) {
    if (!providerDto || !providerDto.provider) return;
    if (providerDto.provider === "STEAM") {
      if (providerDto.profile) {
        applySteamProfile(providerDto.profile);
      } else {
        // Disconnected — collapse the steam panel to the "not connected" state.
        applySteamProfile({ hasSteam: !!providerDto.connected });
      }
    }
    // Other providers (TWITCH) have no dynamic JS-toggled UI in this panel.
  }

  root.CatapultConnections = {
    applyProvider:    applyProvider,
    applySteamProfile: applySteamProfile
  };
}(window));
