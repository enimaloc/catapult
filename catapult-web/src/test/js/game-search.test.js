import { beforeEach, describe, expect, it, vi } from "vitest";
import { html, load, response } from "./helpers.js";

let input;
let results;
let fetchMock;

function type(value) {
    input.value = value;
    input.dispatchEvent(new Event("input"));
}

beforeEach(async () => {
    vi.useFakeTimers();
    html(`<input id="q"><ul id="results"><li>stale</li></ul>`);
    input = document.getElementById("q");
    results = document.getElementById("results");
    fetchMock = vi.fn(async () => response(200, [{ id: "1", name: "Doom" }, { id: "2", name: "Doom II" }]));
    vi.stubGlobal("fetch", fetchMock);
    await load("game-search");
});

describe("GameSearch.attach", () => {
    it("clears the results without searching below two characters", async () => {
        GameSearch.attach(input, results, "/search", vi.fn());

        type("d");
        await vi.runAllTimersAsync();

        expect(fetchMock).not.toHaveBeenCalled();
        expect(results.children).toHaveLength(0);
    });

    it("debounces typing and searches the trimmed query once", async () => {
        GameSearch.attach(input, results, "/search", vi.fn());

        type("do");
        await vi.advanceTimersByTimeAsync(100);
        type(" doom ");
        await vi.advanceTimersByTimeAsync(299);
        expect(fetchMock).not.toHaveBeenCalled();

        await vi.advanceTimersByTimeAsync(1);
        expect(fetchMock).toHaveBeenCalledOnce();
        expect(fetchMock).toHaveBeenCalledWith("/search?q=doom");
    });

    it("url-encodes the query", async () => {
        GameSearch.attach(input, results, "/search", vi.fn());

        type("a&b c");
        await vi.runAllTimersAsync();

        expect(fetchMock).toHaveBeenCalledWith("/search?q=a%26b%20c");
    });

    it("lists one item per result", async () => {
        GameSearch.attach(input, results, "/search", vi.fn());

        type("doom");
        await vi.runAllTimersAsync();

        expect([...results.children].map(li => [li.tagName, li.textContent]))
            .toEqual([["MDUI-LIST-ITEM", "Doom"], ["MDUI-LIST-ITEM", "Doom II"]]);
    });

    it("on pick: clears the list, fills the input and hands the game over", async () => {
        const onSelect = vi.fn();
        GameSearch.attach(input, results, "/search", onSelect);
        type("doom");
        await vi.runAllTimersAsync();

        results.children[1].click();

        expect(onSelect).toHaveBeenCalledWith({ id: "2", name: "Doom II" });
        expect(input.value).toBe("Doom II");
        expect(results.children).toHaveLength(0);
    });

    it("reads the result array through `pick` for wrapped responses", async () => {
        fetchMock.mockResolvedValueOnce(response(200, { results: [{ name: "Wrapped" }] }));
        GameSearch.attach(input, results, "/search", vi.fn(), { pick: data => data.results });

        type("wrap");
        await vi.runAllTimersAsync();

        expect(results.textContent).toBe("Wrapped");
    });
});
