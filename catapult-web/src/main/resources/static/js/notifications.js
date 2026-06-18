(function () {
  const bell = document.getElementById('notif-bell');
  const btn = document.getElementById('notif-bell-btn');
  const dropdown = document.getElementById('notif-dropdown');
  const list = document.getElementById('notif-list');
  const badge = document.getElementById('notif-badge');
  const markAll = document.getElementById('notif-mark-all');

  if (!bell) return;
  bell.hidden = false;

  let unread = 0;
  let backoff = 1000;

  function setUnread(n) {
    unread = Math.max(0, n);
    if (unread === 0) { badge.hidden = true; }
    else { badge.hidden = false; badge.textContent = unread > 99 ? '99+' : String(unread); }
  }

  function prependNotif(n) {
    const li = document.createElement('li');
    li.dataset.id = n.id;
    li.className = `sev-${(n.severity || 'INFO').toLowerCase()}`;
    // Trust contract: n.bodyHtml is HTML pre-sanitized server-side by jsoup Safelist.basic()
    // (see NotificationRenderer). All other dynamic fields MUST go through textContent.
    // Do NOT replace bodyHtml with textContent — markdown formatting would be lost.
    const titleEl = document.createElement('strong');
    titleEl.textContent = n.title;
    const bodyEl = document.createElement('div');
    bodyEl.className = 'body';
    bodyEl.innerHTML = n.bodyHtml; // intentional: pre-sanitized
    li.appendChild(titleEl);
    li.appendChild(bodyEl);
    if (n.ctaUrl && n.ctaLabel && /^https?:\/\//.test(n.ctaUrl)) {
      const a = document.createElement('a');
      a.className = 'cta';
      a.href = n.ctaUrl;
      a.rel = 'noopener';
      a.textContent = n.ctaLabel;
      li.appendChild(a);
    }
    const readBtn = document.createElement('button');
    readBtn.dataset.action = 'read';
    readBtn.type = 'button';
    readBtn.textContent = '✓';
    li.appendChild(readBtn);
    list.prepend(li);
  }

  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
  function authHeaders(extra) {
    const h = Object.assign({}, extra || {});
    if (csrfToken) h[csrfHeader] = csrfToken;
    return h;
  }

  async function loadInitial() {
    try {
      const [listRes, countRes] = await Promise.all([
        fetch('/notifications/api/list?page=0&size=10', { credentials: 'same-origin' }),
        fetch('/notifications/api/unread-count', { credentials: 'same-origin' })
      ]);
      if (countRes.ok) setUnread(parseInt(await countRes.text(), 10) || 0);
      if (listRes.ok) {
        const page = await listRes.json();
        while (list.firstChild) list.removeChild(list.firstChild);
        for (const n of (page.content || [])) prependNotif(n);
      }
    } catch (_) { /* anonymous or backend down — keep bell hidden state untouched */ }
  }

  function connect() {
    const src = new EventSource('/notifications/api/stream');
    src.addEventListener('notification', (ev) => {
      try {
        const n = JSON.parse(ev.data);
        prependNotif(n);
        setUnread(unread + 1);
      } catch (_) {}
    });
    src.onopen = () => { backoff = 1000; };
    src.onerror = () => {
      src.close();
      setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, 30000);
    };
  }

  btn.addEventListener('click', () => { dropdown.hidden = !dropdown.hidden; });
  markAll.addEventListener('click', async () => {
    await fetch('/notifications/api/read-all', { method: 'POST', credentials: 'same-origin', headers: authHeaders() });
    setUnread(0);
  });
  list.addEventListener('click', async (ev) => {
    const b = ev.target.closest('button[data-action="read"]');
    if (!b) return;
    const id = b.parentElement.dataset.id;
    await fetch(`/notifications/api/${encodeURIComponent(id)}/read`, { method: 'POST', credentials: 'same-origin', headers: authHeaders() });
    setUnread(unread - 1);
    b.parentElement.classList.add('read');
  });

  loadInitial().then(connect);
})();
