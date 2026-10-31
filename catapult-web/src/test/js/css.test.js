/* catapult.css: static checks on rules a browser would otherwise drop silently. */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const css = readFileSync(resolve(process.cwd(), "src/main/resources/static/css/catapult.css"), "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "");

/** Every "property: value" declaration of the stylesheet. */
function declarations() {
    return css.split(/[;{}]/)
        .map(part => part.trim())
        .filter(part => /^[-a-z]+\s*:/.test(part) && !part.includes("@"))
        .map(part => {
            const colon = part.indexOf(":");
            return { property: part.slice(0, colon).trim(), value: part.slice(colon + 1).trim() };
        });
}

describe("catapult.css", () => {
    it("wraps mdui colour tokens, which are RGB triplets, in rgb()", () => {
        const bare = declarations()
            // Copying a token into another token keeps it a triplet: fine.
            .filter(({ property }) => !property.startsWith("--mdui-color-"))
            .filter(({ value }) => /(?<!rgb\()var\(--mdui-color-/.test(value))
            .map(({ property, value }) => `${property}: ${value}`);
        expect(bare).toEqual([]);
    });
});
