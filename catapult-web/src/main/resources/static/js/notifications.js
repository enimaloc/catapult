(function () {
  var bell = document.getElementById('notif-bell');
  if (!bell) return;
  bell.hidden = false;

  var btn = document.getElementById('notif-bell-btn');
  var dropdown = document.getElementById('notif-dropdown');
  var list = document.getElementById('notif-list');
  var badge = document.getElementById('notif-badge');
  var markAll = document.getElementById('notif-mark-all');
  var unread = 0;

  function setUnread(n) {
    unread = Math.max(0, n);
    if (unread === 0) { badge.hidden = true; }
    else { badge.hidden = false; badge.textContent = unread > 99 ? '99+' : String(unread); }
  }

  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_-]/g, function (c) { return '\\' + c.charCodeAt(0).toString(16) + ' '; });
  }

  function prependNotif(n) {
    if (list.querySelector('li[data-id="' + cssEscape(n.id) + '"]')) return; // idempotent
    var li = document.createElement('li');
    li.dataset.id = n.id;
    li.className = 'sev-' + (n.severity || 'INFO').toLowerCase();
    // Trust contract: n.bodyHtml is HTML pre-sanitized server-side by jsoup Safelist.basic()
    // (see NotificationRenderer). All other dynamic fields MUST go through textContent.
    // Do NOT replace bodyHtml with textContent — markdown formatting would be lost.
    var titleEl = document.createElement('strong');
    titleEl.textContent = n.title;
    var bodyEl = document.createElement('div');
    bodyEl.className = 'body';
    bodyEl.innerHTML = n.bodyHtml; // intentional: pre-sanitized
    li.appendChild(titleEl);
    li.appendChild(bodyEl);
    if (n.ctaUrl && n.ctaLabel && /^https?:\/\//.test(n.ctaUrl)) {
      var a = document.createElement('a');
      a.className = 'cta';
      a.href = n.ctaUrl; a.rel = 'noopener'; a.textContent = n.ctaLabel;
      li.appendChild(a);
    }
    var readBtn = document.createElement('button');
    readBtn.dataset.action = 'read';
    readBtn.type = 'button';
    readBtn.textContent = '✓';
    li.appendChild(readBtn);
    list.prepend(li);
  }

  function populateSnapshot(data) {
    while (list.firstChild) list.removeChild(list.firstChild);
    (data.items || []).forEach(prependNotif);
    setUnread(data.unreadCount || 0);
  }

  function markReadInDom(notifId) {
    var li = list.querySelector('li[data-id="' + cssEscape(notifId) + '"]');
    if (li) li.classList.add('read');
  }

  function markAllReadInDom() {
    list.querySelectorAll('li').forEach(function (li) { li.classList.add('read'); });
  }

  function removeFromDom(notifId) {
    var li = list.querySelector('li[data-id="' + cssEscape(notifId) + '"]');
    if (li) li.remove();
  }

  function onEvent(msg) {
    if (!msg) return;
    switch (msg.name) {
      case 'notification.snapshot':     populateSnapshot(msg.data); break;
      case 'notification.created':      prependNotif(msg.data); setUnread(unread + 1); break;
      case 'notification.read.changed': markReadInDom(msg.data.notificationId); setUnread(msg.data.unreadCount); break;
      case 'notification.all.read':     markAllReadInDom(); setUnread(0); break;
      case 'notification.deleted':      removeFromDom(msg.data.notificationId);
                                        if (typeof msg.data.unreadCount === 'number') setUnread(msg.data.unreadCount);
                                        break;
    }
  }

  btn.addEventListener('click', function () { dropdown.hidden = !dropdown.hidden; });
  markAll.addEventListener('click', function () {
    if (window.catapultWs) window.catapultWs.command('notification.markAll');
  });
  list.addEventListener('click', function (ev) {
    var b = ev.target.closest('button[data-action="read"]');
    if (!b) return;
    var id = b.parentElement.dataset.id;
    if (window.catapultWs) window.catapultWs.command('notification.read', { notificationId: id });
  });

  function subscribe() {
    if (window.catapultWs) window.catapultWs.subscribe('notifications.user', onEvent);
  }
  document.addEventListener('ws:auth.ok', subscribe);
}());
