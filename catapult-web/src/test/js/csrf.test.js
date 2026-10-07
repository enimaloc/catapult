import { beforeEach, describe, expect, it, vi } from "vitest";
import { load, response } from "./helpers.js";

beforeEach(async () => {
    for (const cookie of document.cookie.split("; ")) {
        document.cookie = `${cookie.split("=")[0]}=; expires=Thu, 01 Jan 1970 00:00:00 GMT`;
    }
    await load("csrf");
});

describe("readCookie", () => {
    it("returns the decoded value of the named cookie", () => {
        document.cookie = "other=1";
        document.cookie = "XSRF-TOKEN=a%20b";

        expect(CatapultCsrf.readCookie("XSRF-TOKEN")).toBe("a b");
    });

    it("does not match a cookie whose name only ends with the requested one", () => {
        document.cookie = "MY-XSRF-TOKEN=nope";

        expect(CatapultCsrf.readCookie("XSRF-TOKEN")).toBeNull();
    });
});

describe("postJson", () => {
    it("posts JSON with the XSRF token header", async () => {
        document.cookie = "XSRF-TOKEN=tok";
        const fetchMock = vi.fn(async () => response(204));
        vi.stubGlobal("fetch", fetchMock);

        const result = await CatapultCsrf.postJson("/x", { a: 1 });

        expect(result.status).toBe(204);
        expect(fetchMock).toHaveBeenCalledWith("/x", {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": "tok" },
            body: JSON.stringify({ a: 1 }),
        });
    });

    it("sends an empty token and an empty object when there is none", async () => {
        const fetchMock = vi.fn(async () => response(204));
        vi.stubGlobal("fetch", fetchMock);

        await CatapultCsrf.postJson("/x");

        const [, options] = fetchMock.mock.calls[0];
        expect(options.headers["X-XSRF-TOKEN"]).toBe("");
        expect(options.body).toBe("{}");
    });
});
