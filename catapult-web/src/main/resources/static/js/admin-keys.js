/*
 * admin-keys.js — live updates for the Steam / DTDD API-key admin pages.
 *
 * Subscribes to the auth-gated `events.admin` channel and patches the key
 * table in place when another admin (or this one) adds / deletes / refreshes
 * a key. Replaces the former full-page POST → redirect reload.
 *
 * Event contract (payloads carry only masked status, never raw keys):
 *   <provider>.key.added     { key: {id, masked, owner, blocked, blockedForSeconds} }
 *   <provider>.key.deleted   { keyId }
 *   <provider>.keys.refreshed{ keys: [ ...KeyStatus ] }
 * where <provider> is "steam" or "dtdd".
 *
 * Loaded globally for admins (not per active_page): the events.admin
 * subscription lives in ws-client and survives hx-boost body swaps, and each
 * event re-resolves its card from the current DOM — so live updates keep
 * working on pages reached through boosted navigation.
 */
(function () {
  "use strict";

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

  function renderRow(card, key) {
    var tr = document.createElement("tr");
    tr.setAttribute("data-key-id", key.id);

    var tdKey = el("td");
    tdKey.appendChild(el("span", "badge", key.masked || ""));
    tr.appendChild(tdKey);

    var tdOwner = el("td");
    if (key.owner) tdOwner.appendChild(el("span", "badge badge-default", key.owner));
    else tdOwner.appendChild(el("span", "text-muted", card.dataset.i18nOwnerAdmin || "Admin"));
    tr.appendChild(tdOwner);

    var tdStatus = el("td");
    if (key.blocked) {
      var blockedLabel = (card.dataset.i18nBlocked || "Blocked") + " " + (key.blockedForSeconds || 0) + "s";
      tdStatus.appendChild(el("span", "badge badge-warning", blockedLabel));
    } else {
      tdStatus.appendChild(el("span", "badge badge-connected", card.dataset.i18nAvailable || "Available"));
    }
    tr.appendChild(tdStatus);

    tr.appendChild(renderDeleteCell(card, key.id));
    // Bind the whole row: ws-actions.bind() scans descendants of its argument,
    // so it must receive an ancestor of the form, not the form itself.
    if (window.catapultWsActions) window.catapultWsActions.bind(tr);
    return tr;
  }

  function renderDeleteCell(card, keyId) {
    var td = el("td");
    var form = document.createElement("form");
    // ws-actions.js binds any element carrying data-ws-path — mirror the
    // server-side ws:post dialect output so the delete goes over WebSocket.
    form.setAttribute("data-ws-method", "POST");
    form.setAttribute("data-ws-path", card.dataset.deleteUrl);
    form.setAttribute("data-ws-swap", "none");

    var hidden = document.createElement("input");
    hidden.type = "hidden";
    hidden.name = "keyId";
    hidden.value = keyId;
    form.appendChild(hidden);

    var btn = el("button", "btn btn-sm", card.dataset.i18nDelete || "Delete");
    btn.type = "submit";
    form.appendChild(btn);

    td.appendChild(form);
    return td;
  }

  function bodyOf(card) { return card.querySelector("[data-keys-body]"); }

  function syncEmptyState(card) {
    var body = bodyOf(card);
    var empty = card.querySelector("[data-keys-empty]");
    var table = card.querySelector("[data-keys-table]");
    var has = body && body.children.length > 0;
    if (empty) empty.style.display = has ? "none" : "";
    if (table) table.style.display = has ? "" : "none";
  }

  function onKeyAdded(card, key) {
    if (!key || !key.id) return;
    var body = bodyOf(card);
    if (!body || body.querySelector('tr[data-key-id="' + cssEscape(key.id) + '"]')) return; // idempotent
    body.appendChild(renderRow(card, key));
    syncEmptyState(card);
    var input = card.parentElement && card.parentElement.querySelector("[data-keys-add-input]");
    if (input) input.value = "";
  }

  function onKeyDeleted(card, keyId) {
    var body = bodyOf(card);
    if (!body || !keyId) return;
    var row = body.querySelector('tr[data-key-id="' + cssEscape(keyId) + '"]');
    if (row) row.remove();
    syncEmptyState(card);
  }

  function onKeysRefreshed(card, keys) {
    var body = bodyOf(card);
    if (!body) return;
    while (body.firstChild) body.removeChild(body.firstChild);
    (keys || []).forEach(function (k) { body.appendChild(renderRow(card, k)); });
    syncEmptyState(card);
  }

  function onEvent(msg) {
    if (!msg || !msg.name) return;
    var dot = msg.name.indexOf(".");
    var provider = dot > 0 ? msg.name.slice(0, dot) : "";
    // Resolve fresh each event: after an hx-boost swap the card is a new node.
    var card = document.querySelector('[data-keys-card][data-provider="' + provider + '"]');
    if (!card) return;
    var data = msg.data || {};
    if (msg.name === provider + ".key.added") onKeyAdded(card, data.key);
    else if (msg.name === provider + ".key.deleted") onKeyDeleted(card, data.keyId);
    else if (msg.name === provider + ".keys.refreshed") onKeysRefreshed(card, data.keys);
  }

  var subscribed = false;
  function subscribe() {
    if (subscribed || !window.catapultWs) return;
    subscribed = true;
    window.catapultWs.subscribe("events.admin", onEvent);
  }

  // events.admin is ADMIN-gated (ChannelResolver), so wait for auth before
  // subscribing — subscribing earlier would get sub.denied.
  document.addEventListener("ws:auth.ok", subscribe, { once: true });
  subscribe(); // immediate if already connected + authenticated
}());
