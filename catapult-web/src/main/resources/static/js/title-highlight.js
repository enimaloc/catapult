/**
 * Stream title fields (mdui-text-field[data-title-highlight]): the variables Catapult replaces, {{GAME}}, {{TITLE}}
 * and {{ORIGINAL_TITLE}} written exactly, shown in the primary color; anything else, another way of writing them
 * included, as plain text, as it will be sent.
 *
 * An <input> can't color part of its text: the field's own text is made transparent (its caret, selection and
 * scrolling stay the input's) and a copy of it, colored, is laid over it, out of the pointer's way. The copy goes in
 * the field's shadow root, where the input is, so it sits exactly on it; it follows the typing, the scrolling and the
 * values set by the page's scripts. Until it is in place (or without it, as in a test) the field shows its text as
 * usual.
 */
window.CatapultTitleHighlight = (function () {
    const VARIABLE = /\{\{(?:GAME|TITLE|ORIGINAL_TITLE)}}/g;
    const SELECTOR = "mdui-text-field[data-title-highlight]";
    // On the field itself: wired once, whichever copy of this script (a reloaded one) sees it
    const ATTACHED = Symbol.for("catapult.titleHighlight");

    /** `text` cut into its plain and variable parts, in order: [{ text, variable }]. */
    function segments(text) {
        const parts = [];
        let last = 0;
        for (const match of (text ?? "").matchAll(VARIABLE)) {
            if (match.index > last) parts.push({ text: text.slice(last, match.index), variable: false });
            parts.push({ text: match[0], variable: true });
            last = match.index + match[0].length;
        }
        if (last < (text ?? "").length) parts.push({ text: text.slice(last), variable: false });
        return parts;
    }

    /** The mirror's content for `text`: text nodes, a variable in a colored span. */
    function fill(mirror, text) {
        const line = document.createElement("span");
        line.style.whiteSpace = "pre";
        for (const part of segments(text)) {
            if (!part.variable) {
                line.append(part.text);
                continue;
            }
            const variable = document.createElement("span");
            variable.className = "title-highlight-variable";
            variable.style.color = "rgb(var(--mdui-color-primary))";
            variable.textContent = part.text;
            line.append(variable);
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
