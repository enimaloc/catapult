/**
 * Stream title fields (mdui-text-field[data-title-highlight]): the variables Catapult replaces, {{GAME}},
 * {{CATEGORY}}, {{TITLE}}, {{ORIGINAL_TITLE}}, {{DROP_NAME}} and {{DROP_END}} written exactly, shown in the primary
 * color, and a ternary's syntax ({{COND ? [[…]] : [[…]]}}) in the tertiary one; anything else, another way of writing
 * them included, as plain text, as it will be sent. A ternary is read as catapult-api's TitleTemplate reads it: known
 * conditions, !, && before ||, parentheses, a run of ] closing a branch on its last two, ternaries nested in a branch;
 * one that doesn't read through stays plain, up to its matching }}.
 *
 * An <input> can't color part of its text: the field's own text is made transparent (its caret, selection and
 * scrolling stay the input's) and a copy of it, colored, is laid over it, out of the pointer's way. The copy goes in
 * the field's shadow root, where the input is, so it sits exactly on it; it follows the typing, the scrolling and the
 * values set by the page's scripts. Until it is in place (or without it, as in a test) the field shows its text as
 * usual.
 */
window.CatapultTitleHighlight = (function () {
    const VARIABLES = ["GAME", "CATEGORY", "TITLE", "ORIGINAL_TITLE", "DROP_NAME", "DROP_END"];
    const CONDITIONS = ["DROP_ENABLED", "HAS_GAME", "IS_CUSTOM", "IS_INCOMPLETE", "IS_NO_GAME", "HAS_TW", "IS_MATURE"];
    const SELECTOR = "mdui-text-field[data-title-highlight]";
    // On the field itself: wired once, whichever copy of this script (a reloaded one) sees it
    const ATTACHED = Symbol.for("catapult.titleHighlight");

    /** `text` cut into its plain, variable and ternary syntax parts, in order: [{ text, variable, syntax? }]. */
    function segments(text) {
        const parts = [];
        const push = (part, kind) => {
            if (!part) return;
            const last = parts.at(-1);
            if (kind === "plain" && last && !last.variable && !last.syntax) last.text += part;
            else if (kind === "syntax") parts.push({ text: part, variable: false, syntax: true });
            else parts.push({ text: part, variable: kind === "variable" });
        };
        cut(text ?? "", push);
        return parts;
    }

    /** `text`'s parts given to `push`, as TitleTemplate.expand walks it. */
    function cut(text, push) {
        let i = 0;
        while (i < text.length) {
            const open = text.indexOf("{{", i);
            if (open < 0) {
                push(text.slice(i), "plain");
                return;
            }
            push(text.slice(i, open), "plain");
            const close = text.indexOf("}}", open + 2);
            const ternary = ternaryAt(text, open);
            let failed;
            if (close >= 0 && VARIABLES.includes(text.slice(open + 2, close))) {
                push(text.slice(open, close + 2), "variable");
                i = close + 2;
            } else if (ternary) {
                push(text.slice(open, ternary.whenTrue[0]), "syntax");
                cut(text.slice(ternary.whenTrue[0], ternary.whenTrue[1] - 2), push);
                push(text.slice(ternary.whenTrue[1] - 2, ternary.whenFalse[0]), "syntax");
                cut(text.slice(ternary.whenFalse[0], ternary.whenFalse[1] - 2), push);
                push(text.slice(ternary.whenFalse[1] - 2, ternary.end), "syntax");
                i = ternary.end;
            } else if ((failed = failedTernaryEnd(text, open)) > 0) {
                push(text.slice(open, failed), "plain");
                i = failed;
            } else {
                push("{", "plain");
                i = open + 1;
            }
        }
    }

    /** {{COND ? [[a]] : [[b]]}} at `open`: its branches' [text start, end after ]] ] and its end; null otherwise. */
    function ternaryAt(text, open) {
        const question = text.indexOf("?", open + 2);
        if (question < 0 || !isCondition(text.slice(open + 2, question))) return null;
        const whenTrue = branch(text, skipSpaces(text, question + 1));
        if (!whenTrue) return null;
        const colon = skipSpaces(text, whenTrue[1]);
        if (text[colon] !== ":") return null;
        const whenFalse = branch(text, skipSpaces(text, colon + 1));
        if (!whenFalse) return null;
        const close = skipSpaces(text, whenFalse[1]);
        return text.startsWith("}}", close) ? { whenTrue, whenFalse, end: close + 2 } : null;
    }

    /** [[…]] at `at`, nested pairs counted, a run of ] closing on its last two: [text start, end after ]]], or null. */
    function branch(text, at) {
        if (!text.startsWith("[[", at)) return null;
        let depth = 1;
        let i = at + 2;
        while (i < text.length) {
            if (text.startsWith("[[", i)) {
                depth++;
                i += 2;
            } else if (text.startsWith("]]", i)) {
                depth--;
                i += 2;
                if (depth === 0) {
                    while (text[i] === "]") i++;
                    return [at + 2, i];
                }
            } else {
                i++;
            }
        }
        return null;
    }

    function skipSpaces(text, i) {
        while (text[i] === " ") i++;
        return i;
    }

    /** Whether `text` reads as a condition: or := and ("||" and)*, and := not ("&&" not)*, not := "!" not | atom. */
    function isCondition(text) {
        let at = 0;
        const spaces = () => { while (text[at] === " ") at++; };
        const take = token => {
            spaces();
            if (!text.startsWith(token, at)) return false;
            at += token.length;
            return true;
        };
        const not = () => {
            if (take("!")) return not();
            if (take("(")) return or() && take(")");
            spaces();
            const start = at;
            while (at < text.length && /[A-Z_]/.test(text[at])) at++;
            return CONDITIONS.includes(text.slice(start, at));
        };
        const and = () => {
            let ok = not();
            while (ok && take("&&")) ok = not();
            return ok;
        };
        const or = () => {
            let ok = and();
            while (ok && take("||")) ok = and();
            return ok;
        };
        const ok = or();
        spaces();
        return ok && at === text.length;
    }

    /** The end of the {{…}} at `open` when written as a ternary (a ? and a [[) that doesn't read through; else -1. */
    function failedTernaryEnd(text, open) {
        let depth = 0;
        let i = open;
        while (i < text.length - 1) {
            if (text.startsWith("{{", i)) {
                depth++;
                i += 2;
            } else if (text.startsWith("}}", i)) {
                depth--;
                i += 2;
                if (depth === 0) {
                    const span = text.slice(open, i);
                    return span.indexOf("?") > 0 && span.includes("[[") ? i : -1;
                }
            } else {
                i++;
            }
        }
        return -1;
    }

    /** The mirror's content for `text`: text nodes, a variable in a colored span. */
    function fill(mirror, text) {
        const line = document.createElement("span");
        line.style.whiteSpace = "pre";
        for (const part of segments(text)) {
            if (!part.variable && !part.syntax) {
                line.append(part.text);
                continue;
            }
            const span = document.createElement("span");
            span.className = part.syntax ? "title-highlight-syntax" : "title-highlight-variable";
            span.style.color = part.syntax ? "rgb(var(--mdui-color-tertiary))" : "rgb(var(--mdui-color-primary))";
            span.textContent = part.text;
            line.append(span);
        }
        mirror.replaceChildren(line);
        return line;
    }

    /** Lays the mirror on `input`, with its font and padding, the text scrolled as the input's is. */
    function place(field, input, mirror, line) {
        const style = getComputedStyle(input);
        const host = field.getBoundingClientRect();
        const box = input.getBoundingClientRect();
        Object.assign(mirror.style, {
            left: `${box.left - host.left}px`,
            top: `${box.top - host.top}px`,
            width: `${box.width}px`,
            height: `${box.height}px`,
            paddingLeft: style.paddingLeft,
            paddingRight: style.paddingRight,
            font: style.font,
            letterSpacing: style.letterSpacing,
            textAlign: style.textAlign,
            opacity: field.disabled ? "0.38" : "1",
        });
        line.style.transform = `translateX(${-input.scrollLeft}px)`;
    }

    /** Wires `field` once its input is rendered; a field without a shadow root (not upgraded) is left as is. */
    async function attach(field) {
        if (field[ATTACHED]) return;
        field[ATTACHED] = true;
        await customElements.whenDefined?.("mdui-text-field");
        await field.updateComplete;
        const input = field.shadowRoot?.querySelector("input");
        if (!input) return;

        const mirror = document.createElement("div");
        mirror.className = "title-highlight";
        Object.assign(mirror.style, {
            position: "absolute",
            pointerEvents: "none",
            overflow: "hidden",
            boxSizing: "border-box",
            display: "flex",
            alignItems: "center",
            color: "rgb(var(--mdui-color-on-surface))",
        });
        mirror.setAttribute("aria-hidden", "true");
        field.shadowRoot.append(mirror);

        let line = fill(mirror, field.value);
        const render = () => {
            line = fill(mirror, field.value);
            place(field, input, mirror, line);
        };
        const scroll = () => place(field, input, mirror, line);

        // A value set by a script (a live event, a form filled anew) fires no input event: its setter tells it.
        const accessor = valueAccessor(field);
        if (accessor) {
            Object.defineProperty(field, "value", {
                configurable: true,
                get() { return accessor.get.call(this); },
                set(value) {
                    accessor.set.call(this, value);
                    this.updateComplete.then(render);
                },
            });
        }
        field.addEventListener("input", render);
        input.addEventListener("scroll", scroll);
        ["keyup", "click", "select", "focus", "blur"].forEach(type => input.addEventListener(type, scroll));
        if (window.ResizeObserver) new ResizeObserver(scroll).observe(field);
        new MutationObserver(scroll).observe(field, { attributes: true, attributeFilter: ["disabled"] });

        render();
        field.classList.add("title-highlighted");
    }

    /** The `value` accessor `field` inherits from its class. */
    function valueAccessor(field) {
        for (let proto = Object.getPrototypeOf(field); proto; proto = Object.getPrototypeOf(proto)) {
            const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
            if (descriptor?.get && descriptor.set) return descriptor;
        }
        return null;
    }

    function scan(root) {
        if (root.matches?.(SELECTOR)) attach(root);
        root.querySelectorAll?.(SELECTOR).forEach(attach);
    }

    // The fields come with a page, a tab built once picked (lazy.js) or a row a live event adds: all are added nodes.
    new MutationObserver(records => records.forEach(record => record.addedNodes.forEach(node => {
        if (node.nodeType === Node.ELEMENT_NODE) scan(node);
    }))).observe(document.documentElement, { childList: true, subtree: true });
    scan(document);

    return { segments };
})();
