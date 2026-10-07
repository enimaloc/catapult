/**
 * Debounced search-as-you-type: queries `searchUrl?q=` once the input holds at least
 * two characters and lists each result as a clickable mdui-list-item.
 *
 * `pick` extracts the result array from the response body, for endpoints that wrap
 * it (e.g. DTDD's `{ results: [...] }`).
 */
window.GameSearch = (function () {
    const MIN_QUERY_LENGTH = 2;
    const DEBOUNCE_MS = 300;

    function attach(inputEl, resultsEl, searchUrl, onSelect, { pick = data => data } = {}) {
        let debounceTimer;
        inputEl.addEventListener("input", () => {
            clearTimeout(debounceTimer);
            const q = inputEl.value.trim();
            if (q.length < MIN_QUERY_LENGTH) {
                resultsEl.replaceChildren();
                return;
            }
            debounceTimer = setTimeout(async () => {
                const response = await fetch(`${searchUrl}?q=${encodeURIComponent(q)}`);
                const games = pick(await response.json());
                resultsEl.replaceChildren(...games.map(game => {
                    const item = document.createElement("mdui-list-item");
                    item.textContent = game.name;
                    item.addEventListener("click", () => {
                        resultsEl.replaceChildren();
                        inputEl.value = game.name;
                        onSelect(game);
                    });
                    return item;
                }));
            }, DEBOUNCE_MS);
        });
    }

    return { attach };
})();
