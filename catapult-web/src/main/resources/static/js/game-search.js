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
 */
window.GameSearch = (function () {
    const MIN_QUERY_LENGTH = 2;
    const DEBOUNCE_MS = 300;

    function tellFailure(status) {
        const channel = window.CatapultChannel;
        if (!channel) return;
        channel.showError(status ? channel.actionFailed(status) : channel.unreachable());
    }

    function attach(inputEl, resultsEl, searchUrl, onSelect, { pick = data => data } = {}) {
        let debounceTimer;
        let latest = 0;

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
                    onSelect(game);
                });
                return item;
            }));
        }

        inputEl.addEventListener("input", () => {
            clearTimeout(debounceTimer);
            const query = ++latest;
            const q = inputEl.value.trim();
            if (q.length < MIN_QUERY_LENGTH) {
                resultsEl.replaceChildren();
                return;
            }
            debounceTimer = setTimeout(() => search(q, query), DEBOUNCE_MS);
        });
    }

    return { attach };
})();
