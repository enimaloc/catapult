import test from "node:test";
import assert from "node:assert/strict";
import { createGitHub } from "../lib/github.mjs";

const sha = (c) => c.repeat(40);

// Answers each "METHOD path" from `routes` (404 otherwise) and records the requests.
function fakeFetch(routes) {
  const requests = [];
  const fetch = async (url, { method }) => {
    const key = `${method} ${new URL(url).pathname}${new URL(url).search}`;
    requests.push(key);
    const found = key in routes;
    return {
      ok: found,
      status: found ? 200 : 404,
      json: async () => routes[key],
      text: async () => (found ? JSON.stringify(routes[key]) : "Not Found"),
    };
  };
  return { fetch, requests };
}

const client = (fetch) => createGitHub({ token: "t", owner: "enimaloc", repo: "catapult", fetch });
const REPO = "/repos/enimaloc/catapult";

test("getTag_missingTag_isNull", async () => {
  const { fetch } = fakeFetch({});
  assert.equal(await client(fetch).getTag("v1.0.0"), null);
});

test("getTag_lightweightTag_givesItsCommit", async () => {
  const { fetch } = fakeFetch({ [`GET ${REPO}/git/ref/tags/v1.0.0`]: { object: { type: "commit", sha: sha("c") } } });
  assert.equal(await client(fetch).getTag("v1.0.0"), sha("c"));
});

test("getTag_annotatedTag_isDereferencedToItsCommit", async () => {
  const { fetch, requests } = fakeFetch({
    [`GET ${REPO}/git/ref/tags/v1.0.0`]: { object: { type: "tag", sha: sha("t") } },
    [`GET ${REPO}/git/tags/${sha("t")}`]: { object: { type: "commit", sha: sha("c") } },
  });
  assert.equal(await client(fetch).getTag("v1.0.0"), sha("c"));
  assert.equal(requests.length, 2);
});

test("getTag_otherError_isThrown", async () => {
  const fetch = async () => ({ ok: false, status: 500, text: async () => "boom" });
  await assert.rejects(client(fetch).getTag("v1.0.0"), /500 boom/);
});

test("listPulls_closed_normalizesMergedFromMergedAt", async () => {
  const raw = (number, mergedAt) => ({
    number,
    node_id: `PR_${number}`,
    title: "t",
    body: null,
    state: "closed",
    draft: false,
    merged_at: mergedAt,
    merge_commit_sha: sha("m"),
    base: { ref: "beta" },
    head: { ref: "dev", sha: sha("h"), repo: { full_name: "enimaloc/catapult" } },
    labels: [],
  });
  const { fetch } = fakeFetch({
    [`GET ${REPO}/pulls?state=closed&base=beta&per_page=100&sort=updated&direction=desc`]: [
      raw(7, "2026-10-28T10:00:00Z"),
      raw(6, null),
    ],
  });
  const pulls = await client(fetch).listPulls({ base: "beta", state: "closed" });
  assert.deepEqual(pulls.map((p) => [p.number, p.merged, p.mergeCommitSha]), [
    [7, true, sha("m")],
    [6, false, sha("m")],
  ]);
});

test("findIssuesByLabel_excludesPullsAndFlagsPinned", async () => {
  const issue = (number, body, extra = {}) => ({ number, node_id: `I_${number}`, title: "t", body, ...extra });
  const query = new URLSearchParams({ state: "open", labels: "release", per_page: "100" });
  const routes = {
    [`GET ${REPO}/issues?${query}`]: [
      issue(9, "x <!-- m -->"),
      issue(4, "<!-- m -->", { pull_request: {} }),
      issue(3, "<!-- m -->"),
      issue(2, "other"),
    ],
    "POST /graphql": { data: { repository: { pinnedIssues: { nodes: [{ issue: { number: 9 } }] } } } },
  };
  const { fetch } = fakeFetch(routes);
  const found = await client(fetch).findIssuesByLabel("release", "<!-- m -->");
  assert.deepEqual(found.map((i) => [i.number, i.pinned]), [[3, false], [9, true]]);
});

test("listReleases_skipsDraftsAndKeepsTheNewest", async () => {
  const release = (tag, draft = false) => ({
    tag_name: tag,
    name: "",
    html_url: `https://example/${tag}`,
    prerelease: false,
    draft,
  });
  const { fetch } = fakeFetch({
    [`GET ${REPO}/releases?per_page=12`]: [release("v3", true), release("v2"), release("v1"), release("v0")],
  });
  const releases = await client(fetch).listReleases(2);
  assert.deepEqual(releases.map((r) => r.tag), ["v2", "v1"]);
  assert.equal(releases[0].name, "v2");
});
