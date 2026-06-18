(async function () {
  const tbody = document.querySelector('#config-table tbody');
  const search = document.querySelector('#config-search');
  const tabs = Array.from(document.querySelectorAll('.config-tab'));
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  let currentModule = 'api';

  function authHeaders(extra) {
    const h = Object.assign({}, extra || {});
    if (csrfToken) h[csrfHeader] = csrfToken;
    return h;
  }

  tabs.forEach(t => t.addEventListener('click', () => {
    if (t.dataset.module === currentModule) return;
    currentModule = t.dataset.module;
    tabs.forEach(other => {
      const active = other.dataset.module === currentModule;
      other.classList.toggle('active', active);
      other.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    load();
  }));

  async function load() {
    const res = await fetch(`/admin/config/api/catalog?module=${encodeURIComponent(currentModule)}`, { credentials: 'same-origin' });
    if (!res.ok) {
      tbody.innerHTML = '';
      const tr = document.createElement('tr');
      const td = document.createElement('td');
      td.colSpan = 4;
      td.textContent = 'Erreur de chargement';
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }
    const entries = await res.json();
    render(entries);
  }

  function render(entries) {
    const q = (search.value || '').toLowerCase();
    tbody.innerHTML = '';
    for (const e of entries) {
      if (q && !e.key.toLowerCase().includes(q)) continue;
      const tr = document.createElement('tr');

      const tdKey = document.createElement('td');
      tdKey.textContent = e.key;
      if (e.restartRequired) {
        const warn = document.createElement('span');
        warn.className = 'badge warn';
        warn.title = 'Redémarrage requis';
        warn.textContent = ' ⚠';
        tdKey.appendChild(warn);
      }
      if (e.taboo) {
        const lock = document.createElement('span');
        lock.title = 'Verrouillé';
        lock.textContent = ' 🔒';
        tdKey.appendChild(lock);
      }

      const tdValue = document.createElement('td');
      const code = document.createElement('code');
      code.textContent = e.secret ? '••••••••' : (e.value ?? '');
      tdValue.appendChild(code);

      const tdSource = document.createElement('td');
      tdSource.textContent = e.source;
      if (e.overridden) {
        const badge = document.createElement('span');
        badge.className = 'badge ok';
        badge.textContent = ' override';
        tdSource.appendChild(badge);
      }

      const tdActions = document.createElement('td');
      if (!e.taboo) {
        const editBtn = document.createElement('button');
        editBtn.dataset.action = 'edit';
        editBtn.dataset.key = e.key;
        editBtn.textContent = 'Éditer';
        tdActions.appendChild(editBtn);
      }
      if (e.overridden) {
        const resetBtn = document.createElement('button');
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

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button');
    if (!btn) return;
    const key = btn.dataset.key;
    if (btn.dataset.action === 'reset') {
      if (!confirm(`Réinitialiser ${key} ?`)) return;
      await fetch(`/admin/config/api/${encodeURIComponent(key)}?module=${encodeURIComponent(currentModule)}`, {
        method: 'DELETE',
        credentials: 'same-origin',
        headers: authHeaders()
      });
      load();
    } else if (btn.dataset.action === 'edit') {
      const value = prompt(`Nouvelle valeur pour ${key} :`);
      if (value === null) return;
      const res = await fetch(`/admin/config/api/${encodeURIComponent(key)}?module=${encodeURIComponent(currentModule)}`, {
        method: 'PUT',
        credentials: 'same-origin',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify({ value })
      });
      if (!res.ok) {
        alert(`Échec: ${res.status}`);
        return;
      }
      load();
    }
  });

  search.addEventListener('input', load);
  load();
})();
