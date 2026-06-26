/*
 * channel-handlers/settings.js — client-side settings panel updater.
 *
 * Exposes window.CatapultSettings.applySettings(dto) that mutates the 4
 * settings panels in place from a UserSettingsDto payload (as delivered by
 * the settings.updated WS event), without replacing the forms wholesale.
 *
 * Simple scalar fields are targeted via [data-setting="<panel>.<field>"]
 * attributes added to each input. Checkbox groups (blockedCcls, blockedTws,
 * noGame ccls, incompleteFallback ccls) are targeted via
 * [data-settings-group="<panel>.<field>"] on their container div; the JS
 * iterates the existing checkboxes inside and sets their checked state.
 *
 * Transient UI state (open game-search dropdowns, autocomplete suggestions)
 * is intentionally left untouched — only persisted-state fields are updated.
 */
(function (root) {
  "use strict";

  function applySettings(s) {
    if (!s) return;

    // CCL panel
    var ccl = s.ccl || {};
    setAll("ccl.enabled", ccl.enabled);
    updateCheckboxGroup("ccl.blockedCcls", ccl.blockedCcls || []);

    // TW panel
    var tw = s.tw || {};
    setAll("tw.enabled", tw.enabled);
    updateCheckboxGroup("tw.blockedTws", tw.blockedTws || []);

    // NoGame panel
    var noGame = s.noGame || {};
    setAll("noGame.twitchGameId", noGame.twitchGameId || "");
    setAll("noGame.twitchGameName", noGame.twitchGameName || "");
    setAll("noGame.applyOnStreamStart", noGame.applyOnStreamStart);
    setAll("noGame.applyOnNoGame", noGame.applyOnNoGame);
    setAll("noGame.applyOnStreamEnd", noGame.applyOnStreamEnd);
    updateCheckboxGroup("noGame.ccls", noGame.ccls || []);

    // IncompleteFallback panel
    var ifb = s.incompleteFallback || {};
    setAll("incompleteFallback.twitchGameId", ifb.twitchGameId || "");
    setAll("incompleteFallback.twitchGameName", ifb.twitchGameName || "");
    updateCheckboxGroup("incompleteFallback.ccls", ifb.ccls || []);
  }

  /** Update every element bearing [data-setting="key"]. */
  function setAll(key, value) {
    var els = document.querySelectorAll('[data-setting="' + attrEscape(key) + '"]');
    for (var i = 0; i < els.length; i++) {
      setField(els[i], value);
    }
  }

  function setField(el, value) {
    if (el.type === "checkbox") {
      el.checked = !!value;
    } else if (el.tagName === "INPUT" || el.tagName === "SELECT" || el.tagName === "TEXTAREA") {
      el.value = value == null ? "" : String(value);
    } else {
      el.textContent = value == null ? "" : String(value);
    }
  }

  /**
   * For a checkbox group container ([data-settings-group="key"]), iterate all
   * child checkboxes and set their checked state from the given id array.
   */
  function updateCheckboxGroup(groupKey, values) {
    var container = document.querySelector('[data-settings-group="' + attrEscape(groupKey) + '"]');
    if (!container) return;
    var boxes = container.querySelectorAll('input[type="checkbox"]');
    var set = {};
    (values || []).forEach(function (v) { set[String(v)] = true; });
    for (var i = 0; i < boxes.length; i++) {
      boxes[i].checked = !!set[boxes[i].value];
    }
  }

  /** Escape double-quotes for use inside an attribute-value selector string. */
  function attrEscape(s) {
    return String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  }

  root.CatapultSettings = { applySettings: applySettings };
}(window));
