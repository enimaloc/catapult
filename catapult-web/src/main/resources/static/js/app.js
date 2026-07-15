const _gameSearchTimers = {};

function gameSearch(event) {
    const input = event.target;
    const resultsId = input.dataset.resultsId;
    const gameIdId = input.dataset.gameidField;
    const gameNameId = input.dataset.gamenameField;
    const q = input.value.trim();
    const results = document.getElementById(resultsId);

    clearTimeout(_gameSearchTimers[resultsId]);
    if (q.length < 2) { results.style.display = 'none'; return; }

    const renderGames = data => {
        results.replaceChildren();
        if (!data.length) { results.style.display = 'none'; return; }
        data.forEach(game => {
            const li = document.createElement('li');
            if (game.boxArtUrl) {
                const img = document.createElement('img');
                img.src = game.boxArtUrl.replace('{width}', '30').replace('{height}', '40');
                img.alt = '';
                li.appendChild(img);
            }
            const span = document.createElement('span');
            span.textContent = game.name;
            li.appendChild(span);
            li.addEventListener('click', () => {
                document.getElementById(gameIdId).value = game.id;
                document.getElementById(gameNameId).value = game.name;
                input.value = game.name;
                results.style.display = 'none';
            });
            results.appendChild(li);
        });
        results.style.display = 'block';
    };

    _gameSearchTimers[resultsId] = setTimeout(() => {
        const channelId = input.dataset.channelId;
        const wsSearch = input.dataset.wsSearch;
        if (!window.catapultWs) { results.style.display = 'none'; return; }
        if (channelId) {
            catapultWs.request('search.twitch.categories', { channelId, q, limit: 10 })
                .then(resp => {
                    if (!resp.ok || !Array.isArray(resp.result)) { results.style.display = 'none'; return; }
                    renderGames(resp.result);
                })
                .catch(() => { results.style.display = 'none'; });
        } else if (wsSearch === 'game') {
            catapultWs.request('search.game', { q, limit: 10 })
                .then(resp => {
                    if (!resp.ok || !Array.isArray(resp.result)) { results.style.display = 'none'; return; }
                    renderGames(resp.result);
                })
                .catch(() => { results.style.display = 'none'; });
        } else {
            results.style.display = 'none';
        }
    }, 300);
}

document.addEventListener('click', e => {
    document.querySelectorAll('.game-results').forEach(el => {
        if (!el.previousElementSibling?.contains(e.target)) el.style.display = 'none';
    });
});

function toggleEdit(id) {
    const row = document.getElementById('edit-row-' + id);
    row.style.display = row.style.display === 'none' ? 'table-row' : 'none';
}

function toggleTwEdit(id) {
    const row = document.getElementById('tw-row-' + id);
    if (row) row.style.display = row.style.display === 'none' ? 'table-row' : 'none';
}

function isEditing() {
    return Array.from(document.querySelectorAll('.edit-row'))
        .some(r => r.style.display === 'table-row');
}

function setTheme(name) {
    if (name === 'dark') {
        document.documentElement.removeAttribute('data-theme');
        localStorage.removeItem('theme');
    } else {
        document.documentElement.setAttribute('data-theme', name);
        localStorage.setItem('theme', name);
    }
    document.querySelectorAll('.theme-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.theme === name);
    });
}

function initThemeUI() {
    const current = localStorage.getItem('theme') || 'dark';
    document.querySelectorAll('.theme-btn').forEach(b => {
        b.classList.toggle('active', b.dataset.theme === current);
    });
}

document.addEventListener('DOMContentLoaded', initThemeUI);

function toggleNavMenu() {
    const nav = document.querySelector('.nav-links--main');
    if (nav) nav.classList.toggle('open');
}

window.addEventListener('resize', function () {
    if (window.innerWidth > 768) {
        const nav = document.querySelector('.nav-links--main');
        if (nav) nav.classList.remove('open');
    }
});

function toggleDropdown(id) {
    document.querySelectorAll('.dropdown-menu').forEach(m => {
        if (m.id !== id) m.classList.remove('open');
    });
    document.getElementById(id).classList.toggle('open');
}

document.addEventListener('click', e => {
    if (!e.target.closest('.dropdown')) {
        document.querySelectorAll('.dropdown-menu').forEach(m => m.classList.remove('open'));
    }
});

function confirmDelete(btn) {
    return confirm(btn.dataset.confirm);
}

function openMigrateModal(btn) {
    const sourceId = btn.dataset.sourceId;
    const sourceName = btn.dataset.sourceName;
    const modal = document.getElementById('migrate-modal');
    const title = document.getElementById('migrate-modal-title');
    const form = document.getElementById('migrate-form');
    const select = document.getElementById('migrate-target');

    form.action = '/admin/members/' + sourceId + '/migrate';
    title.textContent = (title.dataset.prefix || '') + ' ' + sourceName;

    Array.from(select.options).forEach(opt => { opt.hidden = opt.value === sourceId; });
    const first = Array.from(select.options).find(o => !o.hidden);
    if (first) select.value = first.value;

    document.querySelectorAll('.dropdown-menu').forEach(m => m.classList.remove('open'));
    modal.style.display = 'flex';
}

document.addEventListener('DOMContentLoaded', () => {
    const migrateModal = document.getElementById('migrate-modal');
    if (migrateModal) {
        migrateModal.addEventListener('click', e => {
            if (e.target === e.currentTarget) e.currentTarget.style.display = 'none';
        });
    }
});

// Used by the invite panel (standalone /invite page and the channel-page Invitations tab).
function copyInviteLink() {
    const input = document.getElementById('invite-url-input');
    if (!input) return;
    navigator.clipboard.writeText(input.value).catch(function () {
        input.select();
        document.execCommand('copy');
    });
}
