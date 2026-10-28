// Thin GitHub client for the release scripts (REST + the GraphQL mutations REST lacks).
// Pulls are normalized to {number, nodeId, title, body, draft, autoMerge, base, head, headSha, labels};
// commits to {sha, subject, message, author, parents} (author: GitHub login or null; parents: SHAs).

const API = "https://api.github.com";

export function createGitHub({ token, owner, repo }) {
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
    if (!response.ok) throw new Error(`${method} ${path}: ${response.status} ${await response.text()}`);
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
    draft: p.draft,
    autoMerge: Boolean(p.auto_merge),
    base: p.base.ref,
    head: p.head.ref,
    headSha: p.head.sha,
    labels: (p.labels ?? []).map((label) => label.name),
  });
  const toCommit = (c) => ({
    sha: c.sha,
    subject: c.commit.message.split("\n")[0],
    message: c.commit.message,
    author: c.author?.login ?? null,
    parents: c.parents.map((parent) => parent.sha),
  });

  return {
    /** Open pulls into `base`, from `head` when given. */
    async listPulls({ base, head }) {
      const query = new URLSearchParams({ state: "open", base, per_page: "100" });
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

    /** A text file at `ref`: its decoded content and blob SHA. */
    async getFile(path, ref) {
      const file = await request("GET", `${repoPath}/contents/${path}?ref=${encodeURIComponent(ref)}`);
      return { text: Buffer.from(file.content, "base64").toString("utf8"), sha: file.sha };
    },

    /** Commits `text` to `path` on `branch` (`sha` = the replaced blob); returns the commit SHA. */
    async updateFile({ path, branch, text, sha, message }) {
      const content = Buffer.from(text, "utf8").toString("base64");
      const result = await request("PUT", `${repoPath}/contents/${path}`, { message, content, sha, branch });
      return result.commit.sha;
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
