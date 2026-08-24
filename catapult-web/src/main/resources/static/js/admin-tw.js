/*
 * admin-tw.js — live updates for the Trigger Warnings admin page.
 *
 * Subscribes to the ADMIN-gated `events.admin` channel and appends a row when
 * a TW definition is created, instead of the former POST → redirect reload.
 *
 *   tw.definition.added { definition: {id, label, enabled, sortOrder} }
 *
 * The `tw.rebuild` action goes over WS with no swap and produces no visible
 * table change (it recomputes binding mappings, not the definition list).
 */
(function () {
  "use strict";

  // Loaded globally for admins; re-resolved per event so it survives hx-boost swaps.
  var card = null;

  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) {
      return "\\" + c.charCodeAt(0).toString(16) + " ";
    });
  }

  function el(tag, className, text) {
    var n = document.createElement(tag);
    if (className) n.className = className;
    if (text != null) n.textContent = String(text);
    return n;
  }

  function body() { return card.querySelector("[data-tw-body]"); }

  function syncEmptyState() {
    var b = body();
    var empty = card.querySelector("[data-tw-empty]");
    var table = card.querySelector("[data-tw-table]");
    var has = b && b.children.length > 0;
    if (empty) empty.style.display = has ? "none" : "";
    if (table) table.style.display = has ? "" : "none";
  }

  function renderRow(d) {
    var tr = document.createElement("tr");
    tr.setAttribute("data-tw-id", d.id);

    var tdId = el("td");
    tdId.appendChild(el("span", "badge", d.id));
    tr.appendChild(tdId);

    tr.appendChild(el("td", null, d.label || ""));

    var tdStatus = el("td");
    if (d.enabled) tdStatus.appendChild(el("span", "badge badge-connected", card.dataset.i18nEnabled || "Enabled"));
    else tdStatus.appendChild(el("span", "badge badge-warning", card.dataset.i18nDisabled || "Disabled"));
    tr.appendChild(tdStatus);

    tr.appendChild(el("td", null, d.sortOrder != null ? d.sortOrder : 0));

    var tdActions = el("td");
    var link = document.createElement("a");
    link.className = "btn btn-secondary";
    link.href = "/admin/tw/" + encodeURIComponent(d.id) + "/keywords";
    link.textContent = card.dataset.i18nManageKeywords || "Manage keywords";
    tdActions.appendChild(link);
    tr.appendChild(tdActions);

    return tr;
  }

  function onDefinitionAdded(d) {
    if (!d || !d.id) return;
    var b = body();
    if (!b || b.querySelector('tr[data-tw-id="' + cssEscape(d.id) + '"]')) return; // idempotent
    b.appendChild(renderRow(d));
    syncEmptyState();
    var form = document.querySelector("[data-tw-add-form]");
    if (form) form.reset();
  }

  function onEvent(msg) {
    if (!msg || msg.name !== "tw.definition.added") return;
    card = document.querySelector("[data-tw-card]");
    if (!card) return;
    onDefinitionAdded((msg.data || {}).definition);
  }

  var subscribed = false;
  function subscribe() {
    if (subscribed || !window.catapultWs) return;
    subscribed = true;
    window.catapultWs.subscribe("events.admin", onEvent);
  }

  document.addEventListener("ws:auth.ok", subscribe, { once: true });
  subscribe();
}());
