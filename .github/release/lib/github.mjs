// Thin GitHub client for the release scripts (REST + the GraphQL mutations REST lacks).
// Pulls are normalized to {number, nodeId, title, body, state, draft, merged, mergeCommitSha, autoMerge, base,
// head, headSha, headRepo, labels};
// commits to {sha, subject, message, author, parents} (author: GitHub login or null; parents: SHAs).

const API = "https://api.github.com";

export function createGitHub({ token, owner, repo, fetch = globalThis.fetch }) {
  if (!token) throw new Error("A GitHub token is required");
  const headers = {
    Accept: "application/vnd.github+json",
    Authorization: `Bearer ${token}`,
    "X-GitHub-Api-Version": "2022-11-28",
  };

  async function request(method, path, body) {
    const response = await fetch(`${API}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      const error = new Error(`${method} ${path}: ${response.status} ${await response.text()}`);
      error.status = response.status;
      throw error;
    }
    return response.status === 204 ? null : response.json();
  }

  async function graphql(query, variables) {
    const { data, errors } = await request("POST", "/graphql", { query, variables });
    if (errors?.length) throw new Error(`GraphQL: ${errors.map((e) => e.message).join("; ")}`);
    return data;
  }

  const repoPath = `/repos/${owner}/${repo}`;
  const toPull = (p) => ({
    number: p.number,
    nodeId: p.node_id,
    title: p.title,
    body: p.body ?? "",
    state: p.state,
    draft: p.draft,
    merged: Boolean(p.merged ?? p.merged_at), // list endpoints only carry merged_at
    mergeCommitSha: p.merge_commit_sha ?? null,
    autoMerge: Boolean(p.auto_merge),
    base: p.base.ref,
    head: p.head.ref,
    headSha: p.head.sha,
    headRepo: p.head.repo?.full_name ?? null,
    labels: (p.labels ?? []).map((label) => label.name),
  });
  const toIssue = (i) => ({
    number: i.number,
    nodeId: i.node_id,
    title: i.title,
    body: i.body ?? "",
    isPull: Boolean(i.pull_request),
  });
  const toCommit = (c) => ({
    sha: c.sha,
    subject: c.commit.message.split("\n")[0],
    message: c.commit.message,
    author: c.author?.login ?? null,
    parents: c.parents.map((parent) => parent.sha),
  });

  return {
    /** Pulls (into `base` when given; open by default), from `head` when given; most recently updated first. */
    async listPulls({ base, head, state = "open" } = {}) {
      const query = new URLSearchParams({
        state,
        ...(base && { base }),
        per_page: "100",
        sort: "updated",
        direction: "desc",
      });
      if (head) query.set("head", `${owner}:${head}`);
      return (await request("GET", `${repoPath}/pulls?${query}`)).map(toPull);
    },

    async getPull(number) {
      return toPull(await request("GET", `${repoPath}/pulls/${number}`));
    },

    async createPull({ labels = [], ...fields }) {
      const pull = toPull(await request("POST", `${repoPath}/pulls`, fields));
      if (labels.length) await request("POST", `${repoPath}/issues/${pull.number}/labels`, { labels });
      return pull;
    },

    async updatePull(number, fields) {
      await request("PATCH", `${repoPath}/pulls/${number}`, fields);
    },

    async convertToDraft(pull) {
      await graphql(
        "mutation($id: ID!) { convertPullRequestToDraft(input: {pullRequestId: $id}) { clientMutationId } }",
        { id: pull.nodeId },
      );
    },

    async disableAutoMerge(pull) {
      await graphql(
        "mutation($id: ID!) { disablePullRequestAutoMerge(input: {pullRequestId: $id}) { clientMutationId } }",
        { id: pull.nodeId },
      );
    },

    /** Auto-merge with a merge commit, only while the head is still `expectedHeadOid`. */
    async enableAutoMerge(pull, { expectedHeadOid, commitHeadline }) {
      await graphql(
        "mutation($id: ID!, $oid: GitObjectID!, $headline: String!) { enablePullRequestAutoMerge(input: " +
          "{pullRequestId: $id, mergeMethod: MERGE, expectedHeadOid: $oid, commitHeadline: $headline}) " +
          "{ clientMutationId } }",
        { id: pull.nodeId, oid: expectedHeadOid, headline: commitHeadline },
      );
    },

    /** Merges now with a merge commit, only if the head is still `expectedHeadOid`. */
    async mergePull(pull, { expectedHeadOid, commitHeadline }) {
      await graphql(
        "mutation($id: ID!, $oid: GitObjectID!, $headline: String!) { mergePullRequest(input: " +
          "{pullRequestId: $id, mergeMethod: MERGE, expectedHeadOid: $oid, commitHeadline: $headline}) " +
          "{ clientMutationId } }",
        { id: pull.nodeId, oid: expectedHeadOid, headline: commitHeadline },
      );
    },

    /** GraphQL `mergeStateStatus` (CLEAN, BLOCKED, UNSTABLE, BEHIND, DIRTY, UNKNOWN…). */
    async getMergeState(pull) {
      const data = await graphql(
        "query($id: ID!) { node(id: $id) { ... on PullRequest { mergeStateStatus } } }",
        { id: pull.nodeId },
      );
      return data.node.mergeStateStatus;
    },

    /** A text file at `ref`: its decoded content and blob SHA. */
    async getFile(path, ref) {
      const encoded = path.split("/").map(encodeURIComponent).join("/");
      const file = await request("GET", `${repoPath}/contents/${encoded}?ref=${encodeURIComponent(ref)}`);
      return { text: Buffer.from(file.content, "base64").toString("utf8"), sha: file.sha };
    },

    /**
     * Commits `text` to `path` as a child of exactly `parent`, then fast-forwards `branch` to it.
     * Returns the commit SHA, or null when the branch no longer points at `parent` (moved meanwhile).
     */
    async commitFile({ branch, parent, path, text, message }) {
      const { tree } = await request("GET", `${repoPath}/git/commits/${parent}`);
      const newTree = await request("POST", `${repoPath}/git/trees`, {
        base_tree: tree.sha,
        tree: [{ path, mode: "100644", type: "blob", content: text }],
      });
      const created = await request("POST", `${repoPath}/git/commits`, {
        message,
        tree: newTree.sha,
        parents: [parent],
      });
      try {
        await request("PATCH", `${repoPath}/git/refs/heads/${branch.split("/").map(encodeURIComponent).join("/")}`, {
          sha: created.sha,
          force: false,
        });
      } catch (error) {
        if (error.status === 422) return null; // not a fast-forward: someone pushed after `parent`
        throw error;
      }
      return created.sha;
    },

    async comment(number, body) {
      await request("POST", `${repoPath}/issues/${number}/comments`, { body });
    },

    /** Commits on `head` not on `base`, oldest first. */
    async compareCommits(base, head) {
      const commits = [];
      for (let page = 1; ; page++) {
        const path = `${repoPath}/compare/${encodeURIComponent(base)}...${encodeURIComponent(head)}`;
        const result = await request("GET", `${path}?per_page=100&page=${page}`);
        commits.push(...result.commits.map(toCommit));
        if (result.commits.length === 0 || commits.length >= result.total_commits) return commits;
      }
    },

    /** The commit SHA a tag points at (annotated tags dereferenced), or null when it does not exist. */
    async getTag(name) {
      let ref;
      try {
        ref = await request("GET", `${repoPath}/git/ref/tags/${encodeURIComponent(name)}`);
      } catch (error) {
        if (error.status === 404) return null;
        throw error;
      }
      let object = ref.object;
      while (object.type === "tag") object = (await request("GET", `${repoPath}/git/tags/${object.sha}`)).object;
      return object.sha;
    },

    /** Creates the annotated tag `tag` on commit `sha`, with `message`. */
    async createTag({ tag, sha, message }) {
      const created = await request("POST", `${repoPath}/git/tags`, { tag, message, object: sha, type: "commit" });
      await request("POST", `${repoPath}/git/refs`, { ref: `refs/tags/${tag}`, sha: created.sha });
    },

    /**
     * The open issues (pull requests excluded) labelled `label` whose body holds `marker`, lowest number
     * first, each with `pinned` (whether it is among the repository's pinned issues).
     */
    async findIssuesByLabel(label, marker) {
      const query = new URLSearchParams({ state: "open", labels: label, per_page: "100" });
      const issues = (await request("GET", `${repoPath}/issues?${query}`))
        .map(toIssue)
        .filter((i) => !i.isPull && i.body.includes(marker))
        .sort((x, y) => x.number - y.number);
      if (issues.length === 0) return [];
      const data = await graphql(
        "query($owner: String!, $repo: String!) { repository(owner: $owner, name: $repo) " +
          "{ pinnedIssues(first: 3) { nodes { issue { number } } } } }",
        { owner, repo },
      );
      const pinned = new Set(data.repository.pinnedIssues.nodes.map((n) => n.issue.number));
      return issues.map((i) => ({ ...i, pinned: pinned.has(i.number) }));
    },

    async closeIssue(number) {
      await request("PATCH", `${repoPath}/issues/${number}`, { state: "closed", state_reason: "not_planned" });
    },

    async createIssue({ title, body, labels = [] }) {
      return toIssue(await request("POST", `${repoPath}/issues`, { title, body, labels }));
    },

    async updateIssue(number, fields) {
      await request("PATCH", `${repoPath}/issues/${number}`, fields);
    },

    async pinIssue(issue) {
      await graphql("mutation($id: ID!) { pinIssue(input: {issueId: $id}) { clientMutationId } }", {
        id: issue.nodeId,
      });
    },

    /** The `limit` most recent published GitHub Releases, newest first. */
    async listReleases(limit = 2) {
      const releases = await request("GET", `${repoPath}/releases?per_page=${Math.min(limit + 10, 100)}`);
      return releases
        .filter((r) => !r.draft)
        .slice(0, limit)
        .map((r) => ({ tag: r.tag_name, name: r.name || r.tag_name, url: r.html_url, prerelease: r.prerelease }));
    },

    async listTags() {
      const tags = [];
      for (let page = 1; ; page++) {
        const batch = await request("GET", `${repoPath}/tags?per_page=100&page=${page}`);
        tags.push(...batch.map((t) => t.name));
        if (batch.length < 100) return tags;
      }
    },
  };
}
