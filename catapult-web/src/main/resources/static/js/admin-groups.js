/*
 * admin-groups.js — live updates for the user-groups admin page.
 *
 * Subscribes to `events.admin`:
 *   group.created { group: {id, key, name, description, memberCount} } → append row
 *   group.updated { group }                                            → patch cells
 *   group.deleted { groupId }                                          → remove row
 *
 * New rows are cloned from a server-rendered <template> so all markup and i18n
 * stay in Thymeleaf; JS only fills [data-field] cells and swaps the __ID__
 * placeholder in the ws:* form paths.
 */
(function () {
  "use strict";

  var card = document.querySelector("[data-groups-card]");
  if (!card) return;
  var tpl = document.querySelector("[data-groups-row-tpl]");

  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) {
      return "\\" + c.charCodeAt(0).toString(16) + " ";
    });
  }

  function body() { return card.querySelector("[data-groups-body]"); }

  function syncEmptyState() {
    var b = body();
    var empty = card.querySelector("[data-groups-empty]");
    var table = card.querySelector("[data-groups-table]");
    var has = b && b.children.length > 0;
    if (empty) empty.style.display = has ? "none" : "";
    if (table) table.style.display = has ? "" : "none";
  }

  function setField(row, name, value) {
    var cell = row.querySelector('[data-field="' + name + '"]');
    if (cell) cell.textContent = value == null ? "" : String(value);
  }

  function patchRow(row, g) {
    setField(row, "name", g.name);
    setField(row, "description", g.description ? g.description : "—");
    setField(row, "memberCount", g.memberCount != null ? g.memberCount : 0);
    var renameForm = row.querySelector('form[data-ws-path$="/rename"]');
    if (renameForm) {
      var n = renameForm.querySelector('input[name="name"]');
      var d = renameForm.querySelector('input[name="description"]');
      if (n) n.value = g.name || "";
      if (d) d.value = g.description || "";
    }
  }

  function onCreated(g) {
    if (!g || !g.id || !tpl) return;
    var b = body();
    if (!b || b.querySelector('tr[data-group-id="' + cssEscape(g.id) + '"]')) return; // idempotent
    var row = tpl.content.firstElementChild.cloneNode(true);
    row.setAttribute("data-group-id", g.id);
    Array.prototype.forEach.call(row.querySelectorAll("[data-ws-path]"), function (el) {
      el.setAttribute("data-ws-path", el.getAttribute("data-ws-path").replace("IDPLACEHOLDER", g.id));
    });
    setField(row, "key", g.key);
    patchRow(row, g);
    b.appendChild(row);
    if (window.catapultWsActions) window.catapultWsActions.bind(row);
    syncEmptyState();
    var form = document.querySelector("[data-groups-create-form]");
    if (form) form.reset();
  }

  function onUpdated(g) {
    if (!g || !g.id) return;
    var row = body() && body().querySelector('tr[data-group-id="' + cssEscape(g.id) + '"]');
    if (row) patchRow(row, g);
  }

  function onDeleted(groupId) {
    var row = body() && body().querySelector('tr[data-group-id="' + cssEscape(groupId) + '"]');
    if (row) { row.remove(); syncEmptyState(); }
  }

  function onEvent(msg) {
    if (!msg || !msg.name) return;
    var data = msg.data || {};
    if (msg.name === "group.created") onCreated(data.group);
    else if (msg.name === "group.updated") onUpdated(data.group);
    else if (msg.name === "group.deleted") onDeleted(data.groupId);
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
