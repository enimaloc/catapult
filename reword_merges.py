#!/usr/bin/env python3
"""Reword merge commits to conventional commit format and fix their dates."""
import subprocess
import sys
import os
import json

REPO = "/home/enimaloc/dev/IdeaProjects/catapult"
GITLAB_REMOTE = "origin"
GITLAB_REPO = "enimaloc/catapult"

MSG_MAP = {
    "Merge branch 'feat/persistence-flyway' into 'master'": "feat: add persistence with PostgreSQL and Flyway migrations",
    "Merge branch 'feat/auth-oauth2' into 'master'": "feat: add OAuth2 authentication (Twitch/Steam/Discord)",
    "Merge branch 'feat/game-getters' into 'master'": "feat: add game detection via Steam/Discord getters",
    "Merge branch 'feat/scheduler-events' into 'master'": "feat: add scheduler and game state events",
    "Merge branch 'feat/twitch-binding-igdb' into 'master'": "feat: add Twitch category binding and IGDB integration",
    "Merge branch 'feat/chat-commands' into 'master'": "feat: add Twitch chat command system (!game, !setgame)",
    "Merge branch 'feat/ui-thymeleaf' into 'master'": "feat: add Thymeleaf web UI (login, dashboard, settings, bindings)",
    "Merge branch 'feat/docker' into 'master'": "feat: add Docker containerization with multi-stage local build",
    "Merge branch 'feat/optional-providers' into 'master'": "feat: make Steam/Discord providers optional with IGDB fallback",
    "Merge branch 'fix/flyway-css' into 'master'": "fix: add app.css, fix Flyway location and explicit bean startup order",
    "Merge branch 'fix/twitch-auth' into 'master'": "fix: fix Twitch user info endpoint and OAuth2 login debugging",
    "Merge branch 'feat/igdb-redo' into 'master'": "feat: rewrite IGDB integration with app-level token, DB cache and CCL pipeline",
    "Merge branch 'feat/steam-xbox-battlenet' into 'master'": "feat: add Steam integration and Xbox/BattleNet OAuth2 stubs",
    "Merge branch 'feat/activity-log' into 'master'": "feat: add per-user activity log with SSE streaming",
    "Merge branch 'feat/admin-ccl-management' into 'master'": "feat: add admin CCL management with IGDB content descriptor sync",
    "Merge branch 'feat/twitch-token-refresh' into 'master'": "feat: add Twitch token refresh",
    "Merge branch 'feat/remove-xbox-battlenet' into 'master'": "refactor: remove Xbox and BattleNet game getters",
    "Merge branch 'feat/twitch-eventsub' into 'master'": "feat: add Twitch EventSub with live stream detection and pending bindings",
    "Merge branch 'feat/twitch-event-spy' into 'master'": "feat: add twitch-event-spy CLI for real-time event logging",
    "Merge branch 'feat/css-theme' into 'master'": "feat: add CSS theme system with Nord, Dracula, Catppuccin and Tokyo Night themes",
    "Merge branch 'feat/admin-impersonation' into 'master'": "feat: add admin account impersonation via SwitchUserFilter and members management",
    "Merge branch 'feat/twitch-chat' into 'master'": "feat: add Twitch chat service with IRC and EventSub implementations",
    "Merge branch 'feat/twitch-category-cache' into 'master'": "feat: add Twitch category cache with DB-backed search and prewarm",
    "Merge branch 'feat/no-game-settings' into 'master'": "feat: add no-game category and CCL settings",
    "Merge branch 'feat/igdb-search' into 'master'": "feat: add IGDB game search endpoint with autocomplete integration",
    "Merge branch 'feat/i18n' into 'master'": "feat: add ICU4J i18n with EN/FR translations across all templates",
    "Merge branch 'feat/template-layout' into 'master'": "refactor: add shared template layout and migrate all pages",
    "Merge branch 'feat/mock-web' into 'master'": "feat: add mock-web profile for local development without OAuth2",
    "Merge branch 'feat/ab-testing' into 'master'": "feat: add A/B testing engine with Thymeleaf dialect, overrides and rollout",
    "Merge branch 'feat/public-pages' into 'master'": "feat: add footer with version info, GDPR privacy page and public landing page",
    "Merge branch 'feat/channels' into 'master'": "feat: add multi-channel support with owner/mod access control",
    "Merge branch 'feat/incomplete-fallback' into 'master'": "feat: apply incomplete binding fallback game when binding is INCOMPLETE",
    "Merge branch 'feat/prometheus' into 'master'": "feat: add Micrometer Prometheus metrics for bindings and platform connections",
    "Merge branch 'feat/experiment-providers' into 'master'": "feat: add ExperimentProvider abstraction with Unleash and GrowthBook backends",
    "Merge branch 'chore/ci' into 'master'": "chore: set up GitLab CI with Sonarqube, Jacoco and Docker Java 21",
}

def run(cmd, check=True, capture_output=False, env=None, input=None):
    if isinstance(cmd, str):
        cmd = cmd.split()
    result = subprocess.run(cmd, cwd=REPO, capture_output=capture_output, text=True, env=env, input=input)
    if check and result.returncode != 0:
        print(f"ERROR: {cmd}\nSTDOUT: {result.stdout}\nSTDERR: {result.stderr}")
        sys.exit(1)
    return result

def unprotect_master():
    run(["glab", "api", "--method", "DELETE",
         f"projects/{GITLAB_REPO.replace('/', '%2F')}/protected_branches/master"],
        capture_output=True, check=False)

def protect_master():
    run(["glab", "api", "--method", "POST",
         f"projects/{GITLAB_REPO.replace('/', '%2F')}/protected_branches",
         "--field", "name=master",
         "--field", "push_access_level=40",
         "--field", "merge_access_level=40"],
        capture_output=True, check=False)

# 1. Build mapping: original_hash -> {new_msg, new_date}
print("Scanning merge commits...")
overrides = {}

log = run(["git", "log", "--format=%H %P", "master"], capture_output=True).stdout.strip()
for line in log.splitlines():
    parts = line.split()
    h = parts[0]
    parents = parts[1:]
    if len(parents) < 2:
        continue  # not a merge commit
    msg = run(["git", "log", "--format=%s", "-1", h], capture_output=True).stdout.strip()
    if msg not in MSG_MAP:
        continue
    new_msg = MSG_MAP[msg]
    # Use date of second parent (last commit of the merged feature branch)
    date = run(["git", "log", "--format=%aI", "-1", parents[1]], capture_output=True).stdout.strip()
    overrides[h] = {"msg": new_msg, "date": date}
    print(f"  {h[:8]}  [{date[:10]}]  {new_msg}")

print(f"\n{len(overrides)} merge commits to reword.")

# 2. Write override JSON to repo (filter-branch scripts can read it)
override_path = os.path.join(REPO, ".merge_overrides.json")
with open(override_path, "w") as f:
    json.dump(overrides, f)

# 3. Write the msg-filter Python script
msg_filter_path = os.path.join(REPO, ".filter_msg.py")
with open(msg_filter_path, "w") as f:
    f.write(f"""\
import sys, json, os
overrides = json.load(open("{override_path}"))
h = os.environ.get("GIT_COMMIT", "")
msg = sys.stdin.read()
sys.stdout.write(overrides[h]["msg"] if h in overrides else msg)
""")

# 4. Write the env-filter shell snippet
env_filter_path = os.path.join(REPO, ".filter_env.sh")
with open(env_filter_path, "w") as f:
    f.write(f"""\
_override=$(python3 -c "
import json, os, sys
d = json.load(open('{override_path}'))
h = os.environ.get('GIT_COMMIT', '')
if h in d:
    dt = d[h]['date']
    print('GIT_AUTHOR_DATE=' + repr(dt))
    print('GIT_COMMITTER_DATE=' + repr(dt))
")
eval "$_override"
""")

# 5. Run git filter-branch
print("\nRunning git filter-branch (this rewrites all commits)...")
env = os.environ.copy()
env["FILTER_BRANCH_SQUELCH_WARNING"] = "1"
result = run(
    ["git", "filter-branch", "-f",
     "--env-filter", f". {env_filter_path}",
     "--msg-filter", f"python3 {msg_filter_path}",
     "master"],
    capture_output=False, check=True, env=env
)
print("filter-branch complete.")

# 6. Clean up temp files
for p in [override_path, msg_filter_path, env_filter_path]:
    try:
        os.remove(p)
    except FileNotFoundError:
        pass

# 7. Force-push
print("\nForce-pushing to origin/master...")
unprotect_master()
run(["git", "push", GITLAB_REMOTE, "master", "--force"])
protect_master()

print("\nDone!")
run(["git", "log", "--oneline", "--merges", "-5"])
