/**
 * Generic client-side counterpart to the server's spa:if/spa:on Thymeleaf attributes
 * (see IfAttributeProcessor/OnAttributeProcessor): spa:if leaves a data-if="name,..."
 * breadcrumb in the rendered HTML instead of a JS-specific toggle, and spa:on leaves a
 * data-on="name:EventName.field,..." breadcrumb declaring which SSE event (and field)
 * feeds each flag. Visibility.dispatch(eventName, data), called from channel-events.js's
 * generic `on()` wrapper for every SSE event, reads data-on and calls Visibility.apply
 * itself — a new SSE-driven visibility rule is added entirely in the template, never here.
 */
window.Visibility = {
    apply(root, state) {
        root.querySelectorAll("[data-if]").forEach(el => {
            const names = el.dataset.if.split(",");
            // `undefined` (not just a missing key) means unknown too: a setter that merges
            // a partial SSE payload via Object.assign creates the key regardless of whether
            // the event carried it, so hasOwnProperty alone would treat that as "known false".
            const allKnown = names.every(n => state[n] !== undefined);
            if (!allKnown) {
                return;
            }
            el.classList.toggle("hidden", !names.every(n => state[n]));
        });
    },

    // Per-scope flags accumulated across events, keyed by the scope element itself so a
    // fresh server render (fresh elements) starts clean without an explicit reset — the
    // generic replacement for the ad-hoc `let xState = {}` closures each feature used to
    // hand-roll (e.g. the old setSteamConnectedState's steamState).
    _state: new WeakMap(),

    _truthy(value) {
        return Array.isArray(value) ? value.length > 0 : !!value;
    },

    dispatch(eventName, data) {
        document.querySelectorAll("[data-on]").forEach(el => {
            const entries = el.dataset.on.split(",")
                .map(entry => {
                    const sep = entry.indexOf(":");
                    return [entry.slice(0, sep), entry.slice(sep + 1)];
                })
                .filter(([, spec]) => {
                    const eventPart = spec.startsWith("!") ? spec.slice(1) : spec;
                    const dot = eventPart.indexOf(".");
                    const specEventName = dot === -1 ? eventPart : eventPart.slice(0, dot);
                    return specEventName === eventName;
                });
            if (entries.length === 0) {
                return;
            }

            const scope = (data && data.bindingId)
                ? document.querySelector('[data-binding-id="' + data.bindingId + '"]')
                : document;
            if (!scope) {
                return;
            }

            let state = Visibility._state.get(scope);
            if (!state) {
                state = {};
                Visibility._state.set(scope, state);
            }

            for (const [name, spec] of entries) {
                const negatedEvent = spec.startsWith("!");
                const eventPart = negatedEvent ? spec.slice(1) : spec;
                const dot = eventPart.indexOf(".");
                if (dot === -1) {
                    // Bare event name: its occurrence alone is the signal (no field to read).
                    state[name] = !negatedEvent;
                    continue;
                }
                let field = eventPart.slice(dot + 1);
                const negatedField = field.startsWith("!");
                if (negatedField) {
                    field = field.slice(1);
                }
                const truthy = Visibility._truthy(data ? data[field] : undefined);
                state[name] = negatedField ? !truthy : truthy;
            }

            Visibility.apply(scope, state);
        });
    }
};
