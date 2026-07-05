/*
 * admin-ccl.js — live updates for the CCL ↔ IGDB descriptor mapping admin page.
 *
 * Subscribes to the ADMIN-gated `events.admin` channel:
 *   ccl.mappings.updated { ccl: {id, name, description, mappedDescriptions[]} }
 *       → patch that row's badges + select selection (multi-admin sync)
 *   ccl.refreshed { ccls: [...], igdbDescriptors: [{id, description}] }
 *       → rebuild the whole table (catalog re-synced from Twitch)
 *
 * Rows are rebuilt with textContent / createElement only — never innerHTML on
 * event data.
 */
(function () {
  "use strict";

  var card = document.querySelector("[data-ccl-card]");
  if (!card) return;

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

  function body() { return card.querySelector("[data-ccl-body]"); }

  function fillBadges(td, mapped) {
    while (td.firstChild) td.removeChild(td.firstChild);
    (mapped || []).forEach(function (desc) {
      td.appendChild(el("span", "badge badge-ccl", desc));
    });
  }

  function syncSelectSelection(select, mapped) {
    var set = {};
    (mapped || []).forEach(function (d) { set[d] = true; });
    Array.prototype.forEach.call(select.options, function (opt) {
      opt.selected = !!set[opt.textContent];
    });
  }

  function buildSelect(descriptors, mapped) {
    var select = document.createElement("select");
    select.name = "igdbCategoryIds";
    select.multiple = true;
    select.size = 8;
    select.style.width = "100%";
    select.style.minWidth = "250px";
    select.setAttribute("aria-label", "Catégories IGDB");
    var set = {};
    (mapped || []).forEach(function (d) { set[d] = true; });
    (descriptors || []).forEach(function (desc) {
      var opt = document.createElement("option");
      opt.value = desc.id;
      opt.textContent = desc.description;
      if (set[desc.description]) opt.selected = true;
      select.appendChild(opt);
    });
    return select;
  }

  function renderRow(ccl, descriptors) {
    var tr = document.createElement("tr");
    tr.setAttribute("data-ccl-id", ccl.id);

    var tdName = el("td");
    tdName.appendChild(el("strong", null, ccl.name || ""));
    tdName.appendChild(document.createElement("br"));
    tdName.appendChild(el("span", "badge", ccl.id));
    tr.appendChild(tdName);

    tr.appendChild(el("td", "text-muted", ccl.description || ""));

    var tdForm = el("td");
    var form = document.createElement("form");
    form.setAttribute("data-ws-method", "POST");
    form.setAttribute("data-ws-path", card.dataset.mappingsBase + "/" + ccl.id + "/mappings");
    form.setAttribute("data-ws-swap", "none");
    form.appendChild(buildSelect(descriptors, ccl.mappedDescriptions));
    var actions = el("div");
    actions.style.marginTop = "0.5rem";
    var btn = el("button", "btn btn-sm btn-primary", card.dataset.i18nSave || "Save");
    btn.type = "submit";
    actions.appendChild(btn);
    form.appendChild(actions);
    tdForm.appendChild(form);
    tr.appendChild(tdForm);

    var tdBadges = el("td");
    tdBadges.setAttribute("data-ccl-badges", "");
    fillBadges(tdBadges, ccl.mappedDescriptions);
    tr.appendChild(tdBadges);

    // Bind the whole row so ws-actions attaches to the mappings form (it scans
    // descendants of its argument, never the argument itself).
    if (window.catapultWsActions) window.catapultWsActions.bind(tr);
    return tr;
  }

  function onMappingsUpdated(ccl) {
    if (!ccl || !ccl.id) return;
    var b = body();
    var row = b && b.querySelector('tr[data-ccl-id="' + cssEscape(ccl.id) + '"]');
    if (!row) return; // unknown row — appears on next full refresh / reload
    var badges = row.querySelector("[data-ccl-badges]");
    if (badges) fillBadges(badges, ccl.mappedDescriptions);
    var select = row.querySelector('select[name="igdbCategoryIds"]');
    if (select) syncSelectSelection(select, ccl.mappedDescriptions);
  }

  function onRefreshed(ccls, descriptors) {
    var b = body();
    if (!b) return;
    while (b.firstChild) b.removeChild(b.firstChild);
    (ccls || []).forEach(function (ccl) { b.appendChild(renderRow(ccl, descriptors)); });
    var has = b.children.length > 0;
    var empty = card.querySelector("[data-ccl-empty]");
    var table = card.querySelector("[data-ccl-table]");
    if (empty) empty.style.display = has ? "none" : "";
    if (table) table.style.display = has ? "" : "none";
  }

  function onEvent(msg) {
    if (!msg || !msg.name) return;
    var data = msg.data || {};
    if (msg.name === "ccl.mappings.updated") onMappingsUpdated(data.ccl);
    else if (msg.name === "ccl.refreshed") onRefreshed(data.ccls, data.igdbDescriptors);
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
