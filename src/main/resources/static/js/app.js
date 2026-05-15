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

    _gameSearchTimers[resultsId] = setTimeout(() => {
        fetch('/api/games/search?q=' + encodeURIComponent(q))
            .then(r => r.json())
            .then(data => {
                results.replaceChildren();
                if (!data.length) { results.style.display = 'none'; return; }
                data.forEach(game => {
                    const li = document.createElement('li');
                    li.textContent = game.name;
                    li.addEventListener('click', () => {
                        document.getElementById(gameIdId).value = game.id;
                        document.getElementById(gameNameId).value = game.name;
                        input.value = game.name;
                        results.style.display = 'none';
                    });
                    results.appendChild(li);
                });
                results.style.display = 'block';
            })
            .catch(() => { results.style.display = 'none'; });
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

function setTheme(name) {
    if (name === 'dark') {
        document.documentElement.removeAttribute('data-theme');
        localStorage.removeItem('theme');
    } else {
        document.documentElement.setAttribute('data-theme', name);
        localStorage.setItem('theme', name);
    }
    document.querySelectorAll('.theme-btn').forEach(function(b) {
        b.classList.toggle('active', b.dataset.theme === name);
    });
}

function initThemeUI() {
    var current = localStorage.getItem('theme') || 'dark';
    document.querySelectorAll('.theme-btn').forEach(function(b) {
        b.classList.toggle('active', b.dataset.theme === current);
    });
}

document.addEventListener('DOMContentLoaded', initThemeUI);
