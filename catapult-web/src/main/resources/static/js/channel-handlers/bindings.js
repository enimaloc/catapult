/*
 * channel-handlers/bindings.js — client-side row renderer for the bindings table.
 *
 * Exposes window.CatapultBindings.renderBindingRow(b) that reconstructs a
 * <tbody data-binding-id="…"> from a BindingDto payload (as delivered by the
 * binding.upserted WS event), matching the structure of
 * templates/fragments/bindings.html.
 *
 * blockedCcls context is read from the page-level variable channelBlockedCcls
 * injected by app.html so that badge styling stays consistent with the
 * server-rendered rows.
 */
(function (root) {
  "use strict";

  function el(tag, attrs, children) {
    var n = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        if (k === "dataset") {
          Object.assign(n.dataset, attrs.dataset);
        } else if (k === "class") {
          n.className = attrs[k];
        } else {
          n.setAttribute(k, attrs[k]);
        }
      });
    }
    (children || []).forEach(function (c) {
      n.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
    });
    return n;
  }

  function renderBindingRow(b) {
    var u = (typeof channelUsername !== "undefined") ? channelUsername : "";
    var blockedCcls = (typeof channelBlockedCcls !== "undefined" && Array.isArray(channelBlockedCcls))
        ? channelBlockedCcls : [];

    var tbody = document.createElement("tbody");
    tbody.setAttribute("data-binding-id", b.id);

    // Column 1: source type badge + source name
    var sourceBadge = el("span", { "class": "badge" }, [b.sourceType || ""]);
    var sourceNameSpan = el("span", null, [b.sourceName || ""]);
    var td1 = el("td", null, [sourceBadge, document.createTextNode(" "), sourceNameSpan]);

    // Column 2: Twitch game name (or em-dash placeholder)
    var td2 = b.twitchGameName
        ? el("td", null, [el("span", null, [b.twitchGameName])])
        : el("td", null, [el("span", { "class": "text-muted" }, ["—"])]);

    // Column 3: CCL badges
    var cclSpans = (b.ccls || []).map(function (ccl) {
      var isBlocked = blockedCcls.indexOf(ccl) >= 0;
      return el("span", {
        "class": "badge " + (isBlocked ? "badge-warning" : "badge-ccl"),
        "title": isBlocked ? "⚠️ CCL blocked" : ""
      }, [ccl]);
    });
    var td3 = el("td", null, cclSpans);

    // Column 4: status badge
    var isIncomplete = b.status === "INCOMPLETE";
    var td4 = el("td", null, [
      el("span", { "class": "badge " + (isIncomplete ? "badge-warning" : "badge-default") }, [b.status || ""])
    ]);

    // Column 5: CCL-enabled toggle form
    var cclTogglePath = "/channels/" + encodeURIComponent(u) +
        "/bindings/" + encodeURIComponent(b.id) + "/ccl-toggle";
    var cclCheckbox = el("input", { type: "checkbox", name: "enabled" });
    if (b.cclEnabled) cclCheckbox.checked = true;
    cclCheckbox.addEventListener("change", function () { this.form.dispatchEvent(new Event("submit")); });
    var cclToggleForm = el("form", {
      action: cclTogglePath, method: "post",
      "data-ws-method": "POST", "data-ws-path": cclTogglePath, "data-ws-swap": "none"
    }, [
      el("label", { "class": "toggle-label" }, [
        cclCheckbox,
        el("span", { "class": "toggle-slider" })
      ])
    ]);
    var td5 = el("td", null, [cclToggleForm]);

    // Column 6: ignored toggle form
    var ignoredTogglePath = "/channels/" + encodeURIComponent(u) +
        "/bindings/" + encodeURIComponent(b.id) + "/ignored-toggle";
    var ignoredCheckbox = el("input", { type: "checkbox", name: "ignored" });
    if (b.ignored) ignoredCheckbox.checked = true;
    ignoredCheckbox.addEventListener("change", function () { this.form.dispatchEvent(new Event("submit")); });
    var ignoredToggleForm = el("form", {
      action: ignoredTogglePath, method: "post",
      "data-ws-method": "POST", "data-ws-path": ignoredTogglePath, "data-ws-swap": "none"
    }, [
      el("label", { "class": "toggle-label" }, [
        ignoredCheckbox,
        el("span", { "class": "toggle-slider" })
      ])
    ]);
    var td6 = el("td", null, [ignoredToggleForm]);

    // Column 7: actions — edit button and delete form
    var deletePath = "/channels/" + encodeURIComponent(u) +
        "/bindings/" + encodeURIComponent(b.id) + "/delete";
    var deleteForm = el("form", {
      action: deletePath, method: "post",
      "data-ws-method": "POST", "data-ws-path": deletePath, "data-ws-swap": "none",
      style: "display:inline"
    }, [
      el("button", { type: "submit", "class": "btn btn-sm btn-danger" }, ["Supprimer"])
    ]);
    var editBtn = el("button", { "class": "btn btn-sm btn-outline", "data-id": b.id }, ["Modifier"]);
    editBtn.addEventListener("click", function () {
      if (typeof toggleEdit === "function") toggleEdit(this.dataset.id);
    });
    var td7 = el("td", null, [editBtn, document.createTextNode(" "), deleteForm]);

    var tr = el("tr", null, [td1, td2, td3, td4, td5, td6, td7]);
    tbody.appendChild(tr);

    // Register data-ws-* bindings on the new forms so ws-actions.js handles submit.
    if (window.catapultWsActions && typeof window.catapultWsActions.bind === "function") {
      window.catapultWsActions.bind(tbody);
    }

    return tbody;
  }

  root.CatapultBindings = { renderBindingRow: renderBindingRow };
}(window));
