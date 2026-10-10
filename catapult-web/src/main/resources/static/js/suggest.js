/**
 * "Did you mean…?" for the protocol clients (obs.js, twitchat.js): the known name closest to a
 * mistyped one.
 */
window.CatapultSuggest = (function () {
    /** Case-insensitive Levenshtein distance. */
    function distance(a, b) {
        a = a.toLowerCase();
        b = b.toLowerCase();
        let previous = Array.from({ length: b.length + 1 }, (_, j) => j);
        for (let i = 1; i <= a.length; i++) {
            const current = [i];
            for (let j = 1; j <= b.length; j++) {
                current[j] = Math.min(previous[j] + 1, current[j - 1] + 1,
                    previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
            }
            previous = current;
        }
        return previous[b.length];
    }

    /** The candidate closest to name, when near enough to be a typo of it; null otherwise. */
    function closest(name, candidates) {
        let best = null;
        let bestDistance = Infinity;
        for (const candidate of candidates) {
            const d = distance(name, candidate);
            if (d < bestDistance) {
                best = candidate;
                bestDistance = d;
            }
        }
        return bestDistance <= Math.max(2, Math.floor(name.length / 4)) ? best : null;
    }

    return { distance, closest };
})();
