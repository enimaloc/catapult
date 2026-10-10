import { beforeEach, describe, expect, it } from "vitest";
import { load } from "./helpers.js";

beforeEach(async () => {
    await load("suggest");
});

describe("closest", () => {
    it("suggests the nearest candidate, ignoring case", () => {
        expect(CatapultSuggest.closest("SetStudioModeEnable", ["GetVersion", "SetStudioModeEnabled"]))
            .toBe("SetStudioModeEnabled");
        expect(CatapultSuggest.closest("chat_feed_paus", ["CHAT_FEED_PAUSE", "CHAT_FEED_UNPAUSE"]))
            .toBe("CHAT_FEED_PAUSE");
    });

    it("suggests nothing far off", () => {
        expect(CatapultSuggest.closest("ExplodeEverything", ["GetVersion", "GetSceneList"])).toBeNull();
        expect(CatapultSuggest.closest("anything", [])).toBeNull();
    });
});
