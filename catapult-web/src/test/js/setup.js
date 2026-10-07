import { afterEach, beforeEach, vi } from "vitest";

/*
 * The scripts under test are classic browser scripts: they register listeners on document and
 * window at load time and publish globals. Each test reloads them (see load() in helpers.js), so
 * listeners from the previous test are tracked here and removed, and the DOM is reset.
 */
const registered = [];

// Every browser has CSS.escape; jsdom doesn't. Enough of it for attribute selectors.
globalThis.CSS ??= {};
CSS.escape ??= value => String(value).replace(/[^a-zA-Z0-9_-]/g, ch => `\\${ch}`);

for (const target of [document, window]) {
    const add = target.addEventListener.bind(target);
    target.addEventListener = (type, listener, options) => {
        registered.push([target, type, listener, options]);
        add(type, listener, options);
    };
}

beforeEach(() => {
    document.head.innerHTML = "";
    document.body.innerHTML = "";
    history.replaceState(null, "", "/");
    globalThis.mdui = { setTheme: vi.fn(), setColorScheme: vi.fn() };
});

afterEach(() => {
    while (registered.length) {
        const [target, type, listener, options] = registered.pop();
        target.removeEventListener(type, listener, options);
    }
    for (const name of ["Visibility", "CatapultChannel", "CatapultCsrf", "CatapultSpa", "GameSearch", "Catapult"]) {
        delete window[name];
    }
    vi.unstubAllGlobals();
    vi.useRealTimers();
});
