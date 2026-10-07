import { beforeEach, describe, expect, it } from "vitest";
import { load } from "./helpers.js";

describe("theme bootstrap", () => {
    beforeEach(() => {
        localStorage.clear();
    });

    it("defaults to the dark theme seeded from the Catapult primary", async () => {
        await load("catapult");

        expect(mdui.setTheme).toHaveBeenCalledWith("dark");
        expect(mdui.setColorScheme).toHaveBeenCalledWith("#8b5cf6");
    });

    it("honours a saved light theme", async () => {
        localStorage.setItem("catapult-theme", "light");
        await load("catapult");

        expect(mdui.setTheme).toHaveBeenCalledWith("light");
    });

    it("falls back to dark for any other saved value", async () => {
        localStorage.setItem("catapult-theme", "auto");
        await load("catapult");

        expect(mdui.setTheme).toHaveBeenCalledWith("dark");
    });
});
