(function () {
  var tbody = document.querySelector('#config-table tbody');
  var search = document.querySelector('#config-search');
  var tabs = Array.from(document.querySelectorAll('.config-tab'));
  var currentModule = 'api';

  tabs.forEach(function (t) {
    t.addEventListener('click', function () {
      if (t.dataset.module === currentModule) return;
      currentModule = t.dataset.module;
      tabs.forEach(function (other) {
        var active = other.dataset.module === currentModule;
        other.classList.toggle('active', active);
        other.setAttribute('aria-selected', active ? 'true' : 'false');
      });
      load();
    });
  });

  function load() {
    catapultWs.request('admin.config.catalog', { module: currentModule })
      .then(function (resp) {
        var entries = resp.result || [];
        render(entries);
      }, function () {
        tbody.innerHTML = '';
        var tr = document.createElement('tr');
        var td = document.createElement('td');
        td.colSpan = 4;
        td.textContent = 'Erreur de chargement';
        tr.appendChild(td);
        tbody.appendChild(tr);
      });
  }

  function render(entries) {
    var q = (search.value || '').toLowerCase();
    tbody.innerHTML = '';
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      if (q && !e.key.toLowerCase().includes(q)) continue;
      var tr = document.createElement('tr');

      var tdKey = document.createElement('td');
      tdKey.textContent = e.key;
      if (e.restartRequired) {
        var warn = document.createElement('span');
        warn.className = 'badge warn';
        warn.title = 'Redémarrage requis';
        warn.textContent = ' ⚠';
        tdKey.appendChild(warn);
      }
      if (e.taboo) {
        var lock = document.createElement('span');
        lock.title = 'Verrouillé';
        lock.textContent = ' 🔒';
        tdKey.appendChild(lock);
      }

      var tdValue = document.createElement('td');
      var code = document.createElement('code');
      code.textContent = e.secret ? '••••••••' : (e.value != null ? e.value : '');
      tdValue.appendChild(code);

      var tdSource = document.createElement('td');
      tdSource.textContent = e.source;
      if (e.overridden) {
        var badge = document.createElement('span');
        badge.className = 'badge ok';
        badge.textContent = ' override';
        tdSource.appendChild(badge);
      }

      var tdActions = document.createElement('td');
      if (!e.taboo) {
        var editBtn = document.createElement('button');
        editBtn.dataset.action = 'edit';
        editBtn.dataset.key = e.key;
        editBtn.textContent = 'Éditer';
        tdActions.appendChild(editBtn);
      }
      if (e.overridden) {
        var resetBtn = document.createElement('button');
        resetBtn.dataset.action = 'reset';
        resetBtn.dataset.key = e.key;
        resetBtn.textContent = 'Réinitialiser';
        tdActions.appendChild(resetBtn);
      }

      tr.appendChild(tdKey);
      tr.appendChild(tdValue);
      tr.appendChild(tdSource);
      tr.appendChild(tdActions);
      tbody.appendChild(tr);
    }
  }

  tbody.addEventListener('click', function (ev) {
    var btn = ev.target.closest('button');
    if (!btn) return;
    var key = btn.dataset.key;
    if (btn.dataset.action === 'reset') {
      if (!confirm('Réinitialiser ' + key + ' ?')) return;
      catapultWs.request('admin.config.reset', { module: currentModule, key: key })
        .then(function () {
          load();
        }, function (err) {
          var msg = err && err.error && err.error.message ? err.error.message : 'Erreur inconnue';
          alert('Erreur : ' + msg);
        });
    } else if (btn.dataset.action === 'edit') {
      var value = prompt('Nouvelle valeur pour ' + key + ' :');
      if (value === null) return;
      catapultWs.request('admin.config.set', { module: currentModule, key: key, value: value })
        .then(function () {
          load();
        }, function (err) {
          var code = err && err.error && err.error.code ? err.error.code : '?';
          alert('Échec: ' + code);
        });
    }
  });

  search.addEventListener('input', load);
  document.addEventListener('ws:auth.ok', load);
}());
