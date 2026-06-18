(function () {
  const tbody = document.querySelector('#notif-table tbody');
  const dialog = document.getElementById('new-notif');
  const form = dialog.querySelector('form');
  const audienceSel = form.elements.audience;
  const targetedRow = form.querySelector('.targeted');
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';

  function authHeaders(extra) {
    const h = Object.assign({}, extra || {});
    if (csrfToken) h[csrfHeader] = csrfToken;
    return h;
  }

  document.getElementById('open-new').addEventListener('click', () => dialog.showModal());
  audienceSel.addEventListener('change', () => {
    targetedRow.hidden = audienceSel.value !== 'TARGETED';
  });

  document.getElementById('send').addEventListener('click', async (ev) => {
    ev.preventDefault();
    const body = {
      title: form.elements.title.value,
      body: form.elements.body.value,
      severity: form.elements.severity.value,
      ctaUrl: form.elements.ctaUrl.value || null,
      ctaLabel: form.elements.ctaLabel.value || null,
      expiresAt: form.elements.expiresAt.value ? new Date(form.elements.expiresAt.value).toISOString() : null,
      targetUserId: form.elements.audience.value === 'TARGETED' ? form.elements.targetUserId.value : null,
    };
    const res = await fetch('/admin/notifications/api/create', {
      method: 'POST', credentials: 'same-origin',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(body),
    });
    if (!res.ok) { alert(`Échec: ${res.status}`); return; }
    dialog.close();
    form.reset();
    load();
  });

  async function load() {
    const res = await fetch('/admin/notifications/api/list?page=0&size=20', { credentials: 'same-origin' });
    if (!res.ok) return;
    const data = await res.json();
    while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
    for (const n of data.content || []) {
      const tr = document.createElement('tr');
      [n.title, n.severity, n.audience, n.createdAt, n.expiresAt || ''].forEach(val => {
        const td = document.createElement('td');
        td.textContent = val ?? '';
        tr.appendChild(td);
      });
      const tdActions = document.createElement('td');
      const del = document.createElement('button');
      del.dataset.id = n.id;
      del.dataset.action = 'delete';
      del.textContent = 'Supprimer';
      tdActions.appendChild(del);
      tr.appendChild(tdActions);
      tbody.appendChild(tr);
    }
  }

  tbody.addEventListener('click', async (ev) => {
    const btn = ev.target.closest('button[data-action="delete"]');
    if (!btn) return;
    if (!confirm('Supprimer ?')) return;
    await fetch(`/admin/notifications/api/${encodeURIComponent(btn.dataset.id)}`, {
      method: 'DELETE', credentials: 'same-origin', headers: authHeaders()
    });
    load();
  });

  load();
})();
