import test from "node:test";
import assert from "node:assert/strict";
import { confirm, dueSources, run } from "../schedule.mjs";
import { promote } from "../promote.mjs";
import { activeSchedule, formatParis, NO_SCHEDULE, parseScheduleDate, scheduleMarker } from "../lib/schedule.mjs";
import { buildBody, scheduledText, setScheduledText } from "../lib/pr-body.mjs";

const OWNER = "enimaloc";
const REPO = "catapult";
const APP = "catapult-release";
const BOT = `${APP}[bot]`;
const sha = (c) => c.repeat(40);
const AT = new Date("2026-11-02T17:00:00Z"); // 18:00 in Paris
const bodyWith = (text) => setScheduledText(buildBody({ changelog: "", botEnabled: true, seen: [] }), text);
const said = (body, author = BOT) => ({ author, body, createdAt: "t", updatedAt: "t" });

function fakeGitHub({ pull = {}, comments = [], after = [], failOn = null } = {}) {
  const calls = [];
  const state = {
    number: 7,
    title: "chore(release): v1.0.0-BETA.97",
    body: bodyWith(""),
    state: "open",
    draft: true,
    base: "beta",
    head: "dev",
    headSha: sha("a"),
    headRepo: `${OWNER}/${REPO}`,
    ...pull,
  };
  return {
    calls,
    state,
    comments: [...comments],
    async getPull() {
      return state;
    },
    async listPulls({ base, head }) {
      return state.base === base && state.head === head ? [state] : [];
    },
    async listComments() {
      return this.comments;
    },
    async comment(number, body) {
      calls.push(["comment", body]);
      this.comments.push(said(body));
    },
    async updatePull(number, fields) {
      calls.push(["updatePull", fields]);
      Object.assign(state, fields);
    },
    async compareCommits(base, head) {
      calls.push(["compare", base, head]);
      return after;
    },
    async markReady() {
      calls.push(["markReady"]);
      if (failOn === "markReady") throw new Error("markReady exploded");
      state.draft = false;
    },
  };
}
const lastComment = (gh) => gh.calls.filter(([name]) => name === "comment").at(-1)?.[1];
const commented = (gh) => gh.calls.some(([name]) => name === "comment");
const edit = (gh, text, previous = "", sender = OWNER, now = new Date("2026-11-01T00:00:00Z")) => {
  gh.state.body = bodyWith(text);
  return confirm({
    gh,
    event: { action: "edited", number: 7, sender, previousBody: bodyWith(previous) },
    owner: OWNER,
    repo: REPO,
    appSlug: APP,
    now,
  });
};
const start = (gh, now, ready = async () => "auto-merge") =>
  run({ gh, source: "dev", target: "beta", owner: OWNER, repo: REPO, appSlug: APP, now, ready });

test("a Paris date is read in winter and in summer time", () => {
  assert.equal(parseScheduleDate("2026-11-02 18:00").toISOString(), "2026-11-02T17:00:00.000Z");
  assert.equal(parseScheduleDate("2026-07-02 18:00").toISOString(), "2026-07-02T16:00:00.000Z");
  assert.equal(formatParis(AT), "2026-11-02 18:00");
});

test("a malformed, impossible, skipped or repeated date is refused", () => {
  for (const text of ["demain", "2026-11-02", "2026-02-31 10:00", "2026-11-02 25:00", "2026-03-29 02:30", "2026-10-25 02:30"]) {
    assert.equal(parseScheduleDate(text), null, text);
  }
});

test("only the App's last, untouched marker makes a schedule active", () => {
  const marker = scheduleMarker(AT, sha("a"));
  assert.deepEqual(activeSchedule([said(marker)], APP), { at: AT, head: sha("a") });
  assert.equal(activeSchedule([said(marker, OWNER)], APP), null);
  assert.equal(activeSchedule([said(marker), said(NO_SCHEDULE)], APP), null);
  assert.equal(activeSchedule([{ ...said(marker), updatedAt: "later" }], APP), null);
  assert.equal(activeSchedule([], APP), null);
});

test("the schedule line is added once and keeps the rest of the body", () => {
  const old = "Rappel\n\n<!-- changelog:start -->\nx\n<!-- changelog:end -->\n";
  const added = setScheduledText(old, "2026-11-02 18:00");
  assert.equal(scheduledText(added), "2026-11-02 18:00");
  assert.ok(added.indexOf("Sortie programmée") < added.indexOf("changelog:start"));
  assert.equal(setScheduledText(added, ""), "Rappel\n\nSortie programmée :\n\n<!-- changelog:start -->\nx\n<!-- changelog:end -->\n");
  assert.equal(scheduledText(bodyWith("")), "");
});

test("the owner's date is confirmed on the current head", async () => {
  const gh = fakeGitHub();
  assert.equal(await edit(gh, "2026-11-02 18:00"), "confirmed");
  assert.deepEqual(activeSchedule(gh.comments, APP), { at: AT, head: sha("a") });
});

test("another account's edit confirms nothing", async () => {
  const gh = fakeGitHub();
  assert.equal(await edit(gh, "2026-11-02 18:00", "", "someone"), "ignored");
  assert.equal(commented(gh), false);
});

test("an edit leaving the date unchanged does nothing", async () => {
  const gh = fakeGitHub();
  assert.equal(await edit(gh, "demain", "demain"), "ignored");
  assert.equal(commented(gh), false);
});

test("a title edit does nothing", async () => {
  const gh = fakeGitHub({ pull: { body: bodyWith("2026-11-02 18:00") } });
  const event = { action: "edited", number: 7, sender: OWNER };
  assert.equal(await confirm({ gh, event, owner: OWNER, repo: REPO, appSlug: APP }), "ignored");
});

test("an unreadable or past date also cancels the previous schedule", async () => {
  for (const text of ["2026-11-3 18:00", "2020-01-01 10:00"]) {
    const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
    assert.equal(await edit(gh, text, "2026-11-02 18:00"), "refused");
    assert.equal(activeSchedule(gh.comments, APP), null);
  }
});

test("a date on a PR out of draft is refused", async () => {
  const gh = fakeGitHub({ pull: { draft: false } });
  assert.equal(await edit(gh, "2026-11-02 18:00"), "refused");
  assert.equal(activeSchedule(gh.comments, APP), null);
});

test("removing the date cancels the schedule, without noise when there is none", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  assert.equal(await edit(gh, "", "2026-11-02 18:00"), "cancelled");
  assert.equal(activeSchedule(gh.comments, APP), null);
  const idle = fakeGitHub();
  assert.equal(await edit(idle, "", "2026-11-02 18:00"), "ignored");
  assert.equal(commented(idle), false);
});

test("the owner moving the PR in or out of draft cancels the schedule", async () => {
  for (const action of ["ready_for_review", "converted_to_draft"]) {
    const gh = fakeGitHub({ pull: { body: bodyWith("2026-11-02 18:00") }, comments: [said(scheduleMarker(AT, sha("a")))] });
    const event = { action, number: 7, sender: OWNER };
    assert.equal(await confirm({ gh, event, owner: OWNER, repo: REPO, appSlug: APP }), "cancelled");
    assert.equal(activeSchedule(gh.comments, APP), null);
    assert.equal(scheduledText(gh.state.body), "");
  }
});

test("the App's own draft changes never cancel", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  const event = { action: "converted_to_draft", number: 7, sender: BOT };
  assert.equal(await confirm({ gh, event, owner: OWNER, repo: REPO, appSlug: APP }), "ignored");
});

test("a release is due only once its date has come", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  assert.deepEqual(await dueSources({ gh, appSlug: APP, now: new Date(AT - 1) }), []);
  assert.deepEqual(await dueSources({ gh, appSlug: APP, now: AT }), ["dev"]);
});

test("before the date, the run does nothing", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  assert.equal(await start(gh, new Date(AT - 1)), "waiting");
  assert.deepEqual(gh.calls, []);
});

test("at the date, the PR leaves draft and takes the owner's path", async () => {
  const gh = fakeGitHub({ pull: { body: bodyWith("2026-11-02 18:00") }, comments: [said(scheduleMarker(AT, sha("a")))] });
  let args;
  const result = await start(gh, new Date(+AT + 60000), async (a) => {
    args = a;
    return "auto-merge";
  });
  assert.equal(result, "auto-merge");
  assert.deepEqual(args.pr, { number: 7, headSha: sha("a") });
  assert.equal(args.sender, OWNER);
  assert.equal(gh.state.draft, false);
  assert.equal(scheduledText(gh.state.body), "");
  assert.doesNotMatch(lastComment(gh), /retard/);
});

test("a second run does not start the release twice", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  let starts = 0;
  const ready = async () => ++starts;
  await start(gh, new Date(+AT + 60000), ready);
  gh.state.draft = true; // re-drafted by a refusal of onReady
  assert.equal(await start(gh, new Date(+AT + 120000), ready), "none");
  assert.equal(starts, 1);
});

test("a late release still starts and says so with both times", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  const now = new Date(+AT + 3 * 24 * 3600 * 1000);
  assert.equal(await start(gh, now), "auto-merge");
  assert.match(lastComment(gh), /retard.*2026-11-02 18:00.*2026-11-05 18:00/);
});

test("a delay within the hour says nothing about being late", async () => {
  const gh = fakeGitHub({ comments: [said(scheduleMarker(AT, sha("a")))] });
  await start(gh, new Date(+AT + 3600 * 1000));
  assert.doesNotMatch(lastComment(gh), /retard/);
});

test("human commits after the confirmed head cancel at the date", async () => {
  const human = { sha: sha("b"), subject: "fix: late", message: "fix: late", author: OWNER, parents: [sha("a")] };
  const gh = fakeGitHub({ pull: { headSha: sha("b") }, comments: [said(scheduleMarker(AT, sha("a")))], after: [human] });
  assert.equal(await start(gh, AT, async () => assert.fail("must not start")), "cancelled");
  assert.match(lastComment(gh), /bbbbbbb fix: late/);
  assert.equal(activeSchedule(gh.comments, APP), null);
  assert.equal(gh.state.draft, true);
});

test("the bot's own commits after the confirmed head do not cancel", async () => {
  const bump = { sha: sha("b"), subject: "chore(release): bump version to v1", message: "", author: BOT, parents: [sha("a")] };
  const gh = fakeGitHub({ pull: { headSha: sha("b") }, comments: [said(scheduleMarker(AT, sha("a")))], after: [bump] });
  assert.equal(await start(gh, AT), "auto-merge");
});

test("a PR already out of draft only loses its schedule", async () => {
  const gh = fakeGitHub({ pull: { draft: false }, comments: [said(scheduleMarker(AT, sha("a")))] });
  assert.equal(await start(gh, AT, async () => assert.fail("must not start")), "already-ready");
  assert.equal(activeSchedule(gh.comments, APP), null);
});

test("a failed exit from draft is reported and publishes nothing", async () => {
  const gh = fakeGitHub({ failOn: "markReady", comments: [said(scheduleMarker(AT, sha("a")))] });
  await assert.rejects(start(gh, AT, async () => assert.fail("must not start")), /markReady exploded/);
  assert.match(lastComment(gh), /rien n'est publié/);
});

test("a promotion refresh with new commits cancels the schedule and warns", async () => {
  const human = { sha: sha("b"), subject: "fix: new", message: "fix: new", author: OWNER, parents: [sha("a")] };
  const gh = fakeGitHub({ pull: { body: bodyWith("2026-11-02 18:00") }, comments: [said(scheduleMarker(AT, sha("a")))] });
  gh.listTags = async () => ["v0.0.0", "v1.0.0-BETA.96"];
  gh.compareCommits = async () => [human];
  await promote({ gh, source: "dev", target: "beta", owner: OWNER, repo: REPO, appSlug: APP });
  assert.equal(activeSchedule(gh.comments, APP), null);
  assert.equal(scheduledText(gh.state.body), "");
  assert.match(lastComment(gh), /annulée[\s\S]*bbbbbbb fix: new/);
});

test("a promotion refresh without a schedule keeps a typed line and adds it when missing", async () => {
  const human = { sha: sha("b"), subject: "fix: new", message: "fix: new", author: OWNER, parents: [sha("a")] };
  const gh = fakeGitHub({ pull: { body: "<!-- changelog:start -->\n<!-- changelog:end -->\n- [x] <!-- changelog-bot --> b\n" } });
  gh.listTags = async () => ["v0.0.0", "v1.0.0-BETA.96"];
  gh.compareCommits = async () => [human];
  await promote({ gh, source: "dev", target: "beta", owner: OWNER, repo: REPO, appSlug: APP });
  assert.match(gh.state.body, /Sortie programmée :/);
  assert.equal(commented(gh), false);
});
