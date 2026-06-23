/*
 * /admin/broadcast page driver.
 *
 *  - toggles the per-name fieldset visibility when the `name` dropdown changes
 *  - on submit, collects only the fields belonging to the selected name,
 *    sends a JSON POST to /admin/broadcast/api/send, and surfaces the status
 *  - kept JS-only (no HTMX yet on this page) until the WS-HTMX extension
 *    is wired in Phase 4 onwards; once that is live, swap the form's submit
 *    for `hx-post` and the page will naturally route over the WebSocket
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var form = document.getElementById("broadcast-form");
    var nameSelect = document.getElementById("broadcast-name");
    var statusEl = document.getElementById("broadcast-status");
    if (!form || !nameSelect) return;

    var fieldsets = form.querySelectorAll("fieldset[data-name]");

    function showFieldsFor(name) {
      fieldsets.forEach(function (fs) {
        var match = fs.getAttribute("data-name") === name;
        fs.hidden = !match;
        // Disable inputs in hidden fieldsets so they don't get serialized.
        var inputs = fs.querySelectorAll("input, textarea, select");
        inputs.forEach(function (el) { el.disabled = !match; });
      });
    }

    nameSelect.addEventListener("change", function () {
      showFieldsFor(nameSelect.value);
    });

    form.addEventListener("submit", function (e) {
      e.preventDefault();
      var name = nameSelect.value;
      if (!name) return;

      var payload = { name: name };
      var active = form.querySelector('fieldset[data-name="' + name + '"]');
      if (active) {
        var inputs = active.querySelectorAll("input, textarea, select");
        inputs.forEach(function (el) {
          if (!el.name) return;
          if (el.type === "checkbox") {
            payload[el.name] = el.checked;
          } else if (el.type === "number") {
            var v = el.value === "" ? null : Number(el.value);
            if (v !== null && !Number.isNaN(v)) payload[el.name] = v;
          } else if (el.type === "datetime-local" && el.value) {
            // Local datetime → ISO 8601 with the browser's offset; the API
            // parses Instant from any ISO string so this is acceptable.
            payload[el.name] = new Date(el.value).toISOString();
          } else if (el.value !== "") {
            payload[el.name] = el.value;
          }
        });
      }

      setStatus("Envoi…", false);
      var csrfToken = form.querySelector('input[name="_csrf"]');
      var headers = { "Content-Type": "application/json" };
      if (csrfToken && csrfToken.value) headers["X-CSRF-TOKEN"] = csrfToken.value;
      fetch("/admin/broadcast/api/send", {
        method: "POST",
        headers: headers,
        body: JSON.stringify(payload)
      }).then(function (res) {
        if (res.ok || res.status === 202) {
          setStatus("Diffusion '" + name + "' acceptée.", false);
        } else {
          setStatus("Erreur HTTP " + res.status + " — voir logs serveur.", true);
        }
      }).catch(function (err) {
        setStatus("Erreur réseau : " + err.message, true);
      });
    });

    function setStatus(msg, isError) {
      if (!statusEl) return;
      statusEl.hidden = false;
      statusEl.textContent = msg;
      statusEl.style.color = isError ? "var(--danger, #c33)" : "var(--muted)";
    }
  });
})();
