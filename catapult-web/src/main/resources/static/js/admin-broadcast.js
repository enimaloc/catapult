/*
 * /admin/broadcast page driver.
 *
 *  - toggles the per-name fieldset visibility when the `name` dropdown changes
 *  - on submit, collects only the fields belonging to the selected name,
 *    sends the payload via admin.broadcast.send WS action, and surfaces status
 */
(function () {
  "use strict";

  document.addEventListener("DOMContentLoaded", function () {
    var form = document.getElementById("broadcast-form");
    var nameSelect = document.getElementById("broadcast-name");
    var channelSelect = document.getElementById("broadcast-channel");
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

      var channel = channelSelect ? channelSelect.value : "events.global";
      var data = {};
      var active = form.querySelector('fieldset[data-name="' + name + '"]');
      if (active) {
        var inputs = active.querySelectorAll("input, textarea, select");
        inputs.forEach(function (el) {
          if (!el.name) return;
          if (el.type === "checkbox") {
            data[el.name] = el.checked;
          } else if (el.type === "number") {
            var v = el.value === "" ? null : Number(el.value);
            if (v !== null && !Number.isNaN(v)) data[el.name] = v;
          } else if (el.type === "datetime-local" && el.value) {
            // Local datetime → ISO 8601 with the browser's offset; the API
            // parses Instant from any ISO string so this is acceptable.
            data[el.name] = new Date(el.value).toISOString();
          } else if (el.value !== "") {
            data[el.name] = el.value;
          }
        });
      }

      setStatus("Envoi…", false);
      catapultWs.request("admin.broadcast.send", { channel: channel, name: name, data: data })
        .then(function (resp) {
          setStatus("Diffusion '" + name + "' acceptée.", false);
        }, function (err) {
          var code = err && err.error && err.error.code ? err.error.code : (err && err.code ? err.code : "?");
          var msg = err && err.error && err.error.message ? err.error.message : (err && err.message ? err.message : "Erreur inconnue");
          setStatus("Erreur " + code + " : " + msg, true);
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
