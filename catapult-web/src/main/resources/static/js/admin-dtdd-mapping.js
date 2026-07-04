/*
 * admin-dtdd-mapping.js — live updates for the DTDD mapping proposals page.
 *
 * On the PENDING tab, approving/rejecting a proposal removes it from the list.
 * Subscribes to `events.admin`:
 *   dtdd.proposal.resolved { proposalId, status }
 * and removes the matching row. On the APPROVED/REJECTED tabs the row is not
 * present, so the event is a no-op there (it appears on next tab load).
 */
(function () {
  "use strict";

  var card = document.querySelector("[data-dtdd-card]");
  if (!card) return;

  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) {
      return "\\" + c.charCodeAt(0).toString(16) + " ";
    });
  }

  function body() { return card.querySelector("[data-dtdd-body]"); }

  function syncEmptyState() {
    var b = body();
    var empty = card.querySelector("[data-dtdd-empty]");
    var table = card.querySelector("[data-dtdd-table]");
    var has = b && b.children.length > 0;
    if (empty) empty.style.display = has ? "none" : "";
    if (table) table.style.display = has ? "" : "none";
  }

  function onResolved(proposalId) {
    var b = body();
    if (!b || !proposalId) return;
    var row = b.querySelector('tr[data-proposal-id="' + cssEscape(proposalId) + '"]');
    if (row) { row.remove(); syncEmptyState(); }
  }

  function onEvent(msg) {
    if (!msg || msg.name !== "dtdd.proposal.resolved") return;
    onResolved((msg.data || {}).proposalId);
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
