// Thin GitHub client for the release scripts (REST + the GraphQL mutations REST lacks).
// Pulls are normalized to {number, nodeId, title, body, draft, autoMerge, base, head};
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
  });
  const toCommit = (c) => ({
    sha: c.sha,
    subject: c.commit.message.split("\n")[0],
    message: c.commit.message,
    author: c.author?.login ?? null,
    parents: c.parents.map((parent) => parent.sha),
  });

  return {
    async listPulls({ base, head }) {
      const query = new URLSearchParams({ state: "open", base, head: `${owner}:${head}`, per_page: "100" });
      return (await request("GET", `${repoPath}/pulls?${query}`)).map(toPull);
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
