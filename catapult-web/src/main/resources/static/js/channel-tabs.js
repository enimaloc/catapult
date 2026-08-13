/*
 * channel-tabs.js — drives the Dashboard/Configuration/Commandes tab nav on
 * the channel page. No-ops if #channel-tabs isn't present (other pages).
 *
 * Behaviour:
 *   - Click on a .channel-tab button switches the visible panel.
 *   - The initially active tab's panel is already rendered server-side.
 *   - Other panels carry data-tab-path and are fetched via
 *     window.catapultWs.mvc() (zero HTTP fetch), then marked data-loaded
 *     and simply shown/hidden on subsequent clicks. A fetch that races the
 *     WS handshake (WS_CLOSED) is retried once the connection authenticates.
 *   - The active tab is reflected in the URL via history.pushState so a
 *     refresh or shared link reopens on the same tab.
 */
(function () {
  "use strict";

  function init() {
    const nav = document.getElementById("channel-tabs");
    if (!nav) return;

    nav.addEventListener("click", function (evt) {
      const btn = evt.target.closest(".channel-tab");
      if (!btn || !nav.contains(btn)) return;
      activate(btn.dataset.tab);
    });
  }

  function activate(tab) {
    const nav = document.getElementById("channel-tabs");
    const panel = document.querySelector('[data-tab-panel="' + tab + '"]');
    if (!nav || !panel) return;

    nav.querySelectorAll(".channel-tab").forEach(function (b) {
      b.classList.toggle("active", b.dataset.tab === tab);
    });
    document.querySelectorAll("#tab-panels [data-tab-panel]").forEach(function (p) {
      p.style.display = p === panel ? "" : "none";
    });

    const meta = document.querySelector('meta[name="channel-username"]');
    const username = meta ? meta.content : "";
    const path = "/channels/" + encodeURIComponent(username) + "/" + tab;
    if (window.location.pathname !== path) {
      window.history.pushState({ tab: tab }, "", path);
    }

    loadPanel(tab, panel);
  }

  // A tab clicked right after page load can race the WS handshake: mvc()
  // resolves {ok:false, error:{code:"WS_CLOSED"}} when the socket isn't open
  // yet (see ws-client.js), and without a retry the panel was left empty
  // until the user did a full page reload. Retry once the connection is
  // authenticated instead of giving up silently.
  function loadPanel(tab, panel) {
    if (panel.dataset.loaded === "true" || !panel.dataset.tabPath) return;
    if (!window.catapultWs || typeof window.catapultWs.mvc !== "function") return;
    window.catapultWs.mvc({ method: "GET", path: panel.dataset.tabPath }).then(function (resp) {
      if (resp && resp.ok === false && resp.error && resp.error.code === "WS_CLOSED") {
        document.addEventListener("ws:auth.resolved", function retry() {
          document.removeEventListener("ws:auth.resolved", retry);
          loadPanel(tab, panel);
        }, { once: true });
        return;
      }
      if (!resp || resp.ok === false || !resp.html) return;
      const parsed = new DOMParser().parseFromString(resp.html, "text/html");
      const replacement = parsed.body.firstElementChild;
      if (!replacement) return;
      replacement.dataset.loaded = "true";
      replacement.style.display = document.querySelector(".channel-tab.active")?.dataset.tab === tab ? "" : "none";
      panel.replaceWith(replacement);
      if (window.catapultWsActions) window.catapultWsActions.bind(replacement);
      executeScripts(replacement);
    });
  }

  // <script> tags parsed via DOMParser (or inserted via innerHTML/replaceWith)
  // never execute — browsers only run scripts encountered during the initial
  // synchronous HTML parse. Fragments like chat-commands-card.html rely on an
  // inline <script> to populate themselves (e.g. the commands table), so it
  // has to be manually re-created to actually run once inserted live.
  function executeScripts(container) {
    container.querySelectorAll("script").forEach(function (old) {
      const fresh = document.createElement("script");
      for (const attr of old.attributes) fresh.setAttribute(attr.name, attr.value);
      fresh.textContent = old.textContent;
      // Scripts created via document.createElement default to async=true, so
      // two <script src> tags re-created back to back (e.g. blockly_compressed.js
      // then msg/en.js) can execute out of order if the first is still fetching
      // when the second (often smaller) one finishes — async=false restores the
      // classic in-document-order execution guarantee for external scripts.
      if (fresh.src) fresh.async = false;
      try {
        // Inline scripts run synchronously right here, inside replaceWith() — an
        // uncaught exception in one fragment's script would otherwise propagate out
        // of this forEach callback and silently abort it, so every OTHER script still
        // queued in the same tab fragment (e.g. a sibling card's own bootstrap) would
        // simply never run, with no obviously-attributable error in the console.
        old.replaceWith(fresh);
      } catch (err) {
        console.error("[channel-tabs] a tab fragment's <script> threw during init — " +
          "other scripts in this tab still ran, but this one didn't:", err, old);
      }
    });
  }

  // Support browser back/forward across tabs. Derived from the URL rather than
  // evt.state: the very first history entry (before any tab click) never had
  // state pushed onto it, so relying on evt.state left the panel stuck on
  // whatever was last shown when navigating back past that point.
  function tabFromLocation() {
    const parts = window.location.pathname.split("/").filter(Boolean);
    if (parts[0] === "channels" && parts.length >= 3) return parts[2];
    return "dashboard";
  }

  window.addEventListener("popstate", function () {
    if (!document.getElementById("channel-tabs")) return;
    activate(tabFromLocation());
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
}());
