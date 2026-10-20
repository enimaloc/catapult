/**
 * Debounced search-as-you-type: queries `searchUrl?q=` once the input holds at least
 * two characters and lists each result as a clickable mdui-list-item.
 *
 * Only the latest query's answer is listed: one that comes back after a newer query was typed
 * (or after the field went under two characters) is dropped. A refused or failed search empties
 * the list and is told like a failed action.
 *
 * `pick` extracts the result array from the response body, for endpoints that wrap
 * it (e.g. DTDD's `{ results: [...] }`).
 *
 * attach() returns the field's selection: the game last picked, else `initial` (the one the page
 * was rendered with, `{ id, name }`), null once the field is emptied. Given an `initial`, the
 * field holds a game rather than a search: text typed but never picked is no game, so when the
 * field is left it shows the selection back, and a save never pairs a typed name with another
 * game's id.
 */
window.GameSearch = (function () {
    const MIN_QUERY_LENGTH = 2;
    const DEBOUNCE_MS = 300;

    function tellFailure(status) {
        const channel = window.CatapultChannel;
        if (!channel) return;
        channel.showError(status ? channel.actionFailed(status) : channel.unreachable());
    }

    function attach(inputEl, resultsEl, searchUrl, onSelect, { pick = data => data, initial } = {}) {
        let debounceTimer;
        let latest = 0;
        let selected = initial?.id ? { id: initial.id, name: initial.name ?? "" } : null;

        async function search(q, query) {
            let games;
            try {
                const response = await fetch(`${searchUrl}?q=${encodeURIComponent(q)}`);
                if (!response.ok) {
                    if (query === latest) {
                        resultsEl.replaceChildren();
                        tellFailure(response.status);
                    }
                    return;
                }
                games = pick(await response.json());
            } catch {
                if (query === latest) {
                    resultsEl.replaceChildren();
                    tellFailure(0);
                }
                return;
            }
            if (query !== latest) return;
            resultsEl.replaceChildren(...(Array.isArray(games) ? games : []).map(game => {
                const item = document.createElement("mdui-list-item");
                item.textContent = game.name;
                item.addEventListener("click", () => {
                    resultsEl.replaceChildren();
                    inputEl.value = game.name;
                    selected = { id: game.id, name: game.name };
                    onSelect(game);
                });
                return item;
            }));
        }

        inputEl.addEventListener("input", () => {
            clearTimeout(debounceTimer);
            const query = ++latest;
            const q = inputEl.value.trim();
            if (q === "") selected = null;
            if (q.length < MIN_QUERY_LENGTH) {
                resultsEl.replaceChildren();
                return;
            }
            debounceTimer = setTimeout(() => search(q, query), DEBOUNCE_MS);
        });

        if (initial !== undefined) {
            inputEl.addEventListener("change", () => {
                if (inputEl.value.trim() !== "") inputEl.value = selected?.name ?? "";
            });
        }

        return { selected: () => selected };
    }

    return { attach };
})();
