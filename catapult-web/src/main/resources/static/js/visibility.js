/**
 * Generic client-side counterpart to the server's spa:if/spa:on/spa:value/spa:in
 * Thymeleaf attributes (see IfAttributeProcessor/OnAttributeProcessor/
 * ValueAttributeProcessor/InAttributeProcessor): spa:if leaves a data-if="name,..."
 * breadcrumb instead of a JS-specific toggle, and spa:on/spa:value/spa:in leave
 * data-on="name:EventName.field,..." / data-value="property:EventName.field,..." /
 * data-in="property:EventName.arrayField,..." breadcrumbs declaring which SSE event
 * (and field) feeds each flag or DOM property. Visibility.dispatch(eventName, data),
 * called from channel-events.js's generic `on()` wrapper for every SSE event, reads all
 * three and drives Visibility.apply / direct property assignment / array-membership
 * checks itself — a new SSE-driven visibility rule or property sync is added entirely
 * in the template, never here.
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

    // Resolves one spec ("EventName.field" / "EventName.!field" / "EventName" /
    // "!EventName", each optionally suffixed with "|default") against an event payload.
    // A bare event name (no field) resolves to whether the event fired in that
    // (non-)negated form; a field resolves to its raw value, boolean-negated when the
    // field itself is prefixed with "!" — negating a field only makes sense as a
    // boolean, so that direction always returns a boolean, but a plain (non-negated)
    // field is returned verbatim (string, number, ...) so spa:value can assign it to a
    // DOM property as-is. A field can also carry "=value" or "=value1/value2/..." (spa:
    // switch's case matching): resolves to whether the field's raw value equals any of
    // the listed literals, negatable the same way as a plain field. A trailing
    // "|default" substitutes that literal string whenever the resolved value is falsy
    // (matching the `value || "default"` fallback every plain JS setter already used for
    // an absent/empty field).
    _resolve(data, spec) {
        const pipe = spec.indexOf("|");
        const hasDefault = pipe !== -1;
        const defaultValue = hasDefault ? spec.slice(pipe + 1) : undefined;
        const core = hasDefault ? spec.slice(0, pipe) : spec;

        const negatedEvent = core.startsWith("!");
        const eventPart = negatedEvent ? core.slice(1) : core;
        const dot = eventPart.indexOf(".");
        let resolved;
        if (dot === -1) {
            resolved = !negatedEvent;
        } else {
            let field = eventPart.slice(dot + 1);
            const negatedField = field.startsWith("!");
            if (negatedField) {
                field = field.slice(1);
            }
            const eq = field.indexOf("=");
            if (eq !== -1) {
                const expected = field.slice(eq + 1).split("/");
                field = field.slice(0, eq);
                const value = data ? data[field] : undefined;
                const matches = expected.includes(value);
                resolved = negatedField ? !matches : matches;
            } else {
                const value = data ? data[field] : undefined;
                resolved = negatedField ? !Visibility._truthy(value) : value;
            }
        }
        return (hasDefault && !resolved) ? defaultValue : resolved;
    },

    // Entries of `el`'s data-${attr} whose spec names this event.
    _entriesFor(el, attr, eventName) {
        return el.dataset[attr].split(",")
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
    },

    // Whether this element should react to this event payload at all: an element with no
    // [data-binding-id] ancestor is page-level and always included (e.g. #channel-current-
    // game, even though GameChangedEvent's bindingId exists for the binding row's own
    // highlight, not for it); an element inside a binding row only reacts when that row's
    // id matches the payload's bindingId, so one row's event never updates another's.
    _included(el, data) {
        const row = el.closest("[data-binding-id]");
        if (!row) {
            return true;
        }
        return !!data && row.dataset.bindingId === data.bindingId;
    },

    dispatch(eventName, data) {
        const scopedStates = new Map();
        document.querySelectorAll("[data-on]").forEach(el => {
            if (!Visibility._included(el, data)) {
                return;
            }
            const entries = Visibility._entriesFor(el, "on", eventName);
            if (entries.length === 0) {
                return;
            }
            const scope = el.closest("[data-binding-id]") || document;
            let state = scopedStates.get(scope);
            if (!state) {
                state = Visibility._state.get(scope);
                if (!state) {
                    state = {};
                    Visibility._state.set(scope, state);
                }
                scopedStates.set(scope, state);
            }
            for (const [name, spec] of entries) {
                state[name] = Visibility._truthy(Visibility._resolve(data, spec));
            }
        });
        scopedStates.forEach((state, scope) => Visibility.apply(scope, state));

        document.querySelectorAll("[data-value]").forEach(el => {
            if (!Visibility._included(el, data)) {
                return;
            }
            Visibility._entriesFor(el, "value", eventName).forEach(([property, spec]) => {
                el[property] = Visibility._resolve(data, spec);
            });
        });

        document.querySelectorAll("[data-in]").forEach(el => {
            if (!Visibility._included(el, data)) {
                return;
            }
            Visibility._entriesFor(el, "in", eventName).forEach(([property, spec]) => {
                const dot = spec.indexOf(".");
                const field = dot === -1 ? null : spec.slice(dot + 1);
                const array = field && data ? data[field] : undefined;
                el[property] = Array.isArray(array) && array.includes(el.value);
            });
        });
    }
};
