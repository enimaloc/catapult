(async function () {
  const tbody = document.querySelector('#config-table tbody');
  const search = document.querySelector('#config-search');

  async function load() {
    const res = await fetch('/api/admin/config', { credentials: 'same-origin' });
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
      await fetch(`/api/admin/config/${encodeURIComponent(key)}`, {
        method: 'DELETE',
        credentials: 'same-origin'
      });
      load();
    } else if (btn.dataset.action === 'edit') {
      const value = prompt(`Nouvelle valeur pour ${key} :`);
      if (value === null) return;
      const res = await fetch(`/api/admin/config/${encodeURIComponent(key)}`, {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
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
