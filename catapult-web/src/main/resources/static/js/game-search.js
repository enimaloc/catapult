window.GameSearch = (function () {
    function attach(inputEl, resultsEl, searchUrl, onSelect) {
        let debounceTimer;
        inputEl.addEventListener("input", () => {
            clearTimeout(debounceTimer);
            const q = inputEl.value.trim();
            if (q.length < 2) {
                resultsEl.innerHTML = "";
                return;
            }
            debounceTimer = setTimeout(async () => {
                const response = await fetch(`${searchUrl}?q=${encodeURIComponent(q)}`);
                const results = await response.json();
                resultsEl.innerHTML = "";
                results.forEach(game => {
                    const item = document.createElement("mdui-list-item");
                    item.textContent = game.name;
                    item.addEventListener("click", () => {
                        onSelect(game);
                        resultsEl.innerHTML = "";
                        inputEl.value = game.name;
                    });
                    resultsEl.appendChild(item);
                });
            }, 300);
        });
    }

    return { attach };
})();
