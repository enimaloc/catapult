(function () {
  var tbody = document.querySelector('#notif-table tbody');
  var dialog = document.getElementById('new-notif');
  if (!dialog) return;
  var form = dialog.querySelector('form');
  var audienceSel = form.elements.audience;
  var targetedRow = form.querySelector('.targeted');

  document.getElementById('open-new').addEventListener('click', function () { dialog.showModal(); });
  document.getElementById('cancel').addEventListener('click', function () { dialog.close(); form.reset(); });
  audienceSel.addEventListener('change', function () {
    targetedRow.hidden = audienceSel.value !== 'TARGETED';
  });

  document.getElementById('send').addEventListener('click', function (ev) {
    ev.preventDefault();
    var params = {
      title: form.elements.title.value,
      body: form.elements.body.value,
      severity: form.elements.severity.value,
      ctaUrl: form.elements.ctaUrl.value || null,
      ctaLabel: form.elements.ctaLabel.value || null,
      expiresAt: form.elements.expiresAt.value ? new Date(form.elements.expiresAt.value).toISOString() : null,
      targetUserId: audienceSel.value === 'TARGETED' ? form.elements.targetUserId.value || null : null
    };
    catapultWs.request('admin.notification.create', params).then(function (resp) {
      dialog.close();
      form.reset();
      load();
    }, function (err) {
      alert('Échec : ' + (err && err.message ? err.message : JSON.stringify(err)));
    });
  });

  function load() {
    catapultWs.request('admin.notification.list', { page: 0, size: 20 }).then(function (resp) {
      var content = resp.result && resp.result.content ? resp.result.content : [];
      while (tbody.firstChild) tbody.removeChild(tbody.firstChild);
      content.forEach(function (n) {
        var tr = document.createElement('tr');
        [n.title, n.severity, n.audience, n.createdAt, n.expiresAt || ''].forEach(function (val) {
          var td = document.createElement('td');
          td.textContent = val != null ? val : '';
          tr.appendChild(td);
        });
        var tdActions = document.createElement('td');
        var del = document.createElement('button');
        del.dataset.id = n.id;
        del.dataset.action = 'delete';
        del.textContent = 'Supprimer';
        tdActions.appendChild(del);
        tr.appendChild(tdActions);
        tbody.appendChild(tr);
      });
    });
  }

  tbody.addEventListener('click', function (ev) {
    var btn = ev.target.closest('button[data-action="delete"]');
    if (!btn) return;
    if (!confirm('Supprimer ?')) return;
    catapultWs.request('admin.notification.delete', { notificationId: btn.dataset.id }).then(function () {
      load();
    }, function (err) {
      alert('Erreur : ' + (err && err.message ? err.message : JSON.stringify(err)));
    });
  });

  document.addEventListener('ws:auth.ok', load);
}());
