# Giving Mika Tester work through GitHub issues

One page for anyone (person or bot) who wants a phone running **Mika Tester** to test an
Android app. You open a GitHub issue; the phone picks it up, does the steps on the real
device, and replies with a verdict, the steps it took and screenshots. Design details are in
[`tester-design.md`](tester-design.md).

## The repository

- The phone owner chooses one repository as the board (for example a private repo named
  `mika-tasks`) and, optionally, a second repository for screenshots. Both are set in
  Mika Tester under Settings › Task board (GitHub).
- Only GitHub accounts on the phone's allowlist are listed to. Mika stores their numeric
  account IDs, so a renamed or look-alike account is ignored. Everyone else's issues
  and comments are skipped without a reply.
- Issues must be enabled on the board repository.

## The issue template

Title: anything short; it is only used as the heading of the result.
Body: free-form notes for humans, plus **exactly one** fenced block whose info string is
`mika-task`. Notes outside the block are given to Mika as background, never as fields.

````markdown
Sign-in should reject a wrong password (build 2.4.1).

```mika-task
version: 1
app: com.example.shop
min_version: 2.3.0
reset: force_stop
steps:
  - Open the app and go to Account › Sign in
  - Enter user "qa+1@example.com" and password "wrong-password"
  - Tap Sign in
pass:
  - An error message about the password is shown
  - The user stays on the sign-in screen
capture: failures
timeout_min: 15
ask_timeout_min: 30
attendance: reachable
```
````

| Key | Required | Values |
|---|---|---|
| app | yes | the package name of the app under test; it must already be installed on the phone. Mika's own packages are refused |
| steps | yes | 1–30 items, done in order |
| pass | yes | 1–10 criteria; each one is judged on its own |
| version | no | `1` |
| min_version | no | fail at once if the installed version is older |
| reset | no | none (default) or force_stop (start from a fresh launch) |
| capture | no | none, failures (default: last screens of a run that did not pass), final, steps, every_action (alias all, capped at 40) |
| timeout_min | no | whole-run limit, default 15, max 60 |
| ask_timeout_min | no | how long a question waits for an answer, default 30, max 240 |
| attendance | no | reachable (default: Mika may ask in a comment) or unattended (no questions) |

The block is a small YAML subset: `key: value` lines and lists of `- item` lines; `#` starts a
comment. Unknown keys, bad values or a second block make the task fail with a comment that
lists the problems; nothing runs.

Then add the label `mika-task`.

## Labels

| Label | Meaning | Who sets it |
|---|---|---|
| `mika-task` | queued, waiting for a phone | you |
| `mika-in-progress` | claimed and running | the phone (it removes `mika-task`) |
| `mika-needs-input` | Mika asked a question in a comment and is waiting | the phone |
| `mika-done` | finished with verdict PASS | the phone |
| `mika-failed` | FAIL, UNVERIFIED, TIMEOUT, ERROR or an unreadable task | the phone |

The phone creates these labels when its owner presses Test connection. It never closes issues.

- **Run again:** remove `mika-done` or `mika-failed` and add `mika-task`. Every attempt is a new run.
- **Cancel:** close the issue, or remove `mika-in-progress`. Mika stops and says so.
- **Answer a question:** reply with a normal comment (you may start it with `/answer`). Only
  allowlisted accounts are heard. With no answer before the ask timeout, Mika carries on without one.

## Reading the result

The phone posts one comment per run:

- a heading with the verdict: PASS, FAIL, UNVERIFIED (Mika could not show every criterion
  passed; never counted as a pass), TIMEOUT or ERROR;
- the app's installed version (read from the phone, not from the model), the run id, the time taken and the number of actions;
- a table with one row per pass criterion (✅ / ❌ / ❔ and a note);
- what Mika observed, the steps it took (from the run record; typed text is hidden) and screenshot links;
- a hidden machine-readable line. Bots should read this instead of the markdown:

```
<!-- mika:result {"v":1,"run":"…","verdict":"pass","criteria":["pass","pass"],"delivery":"…"} -->
```

Comments that contain the marker `<!-- mika:` are Mika's own; ignore them when you look for human replies.
Screenshots are committed to the `mika-artifacts` branch of the screenshot repository
(path runs/ISSUE/RUN/) and linked from the comment. Anyone who can read that repository can open them.

## Limits

- **No payments, no approvals from the board.** Mika is told never to pay. A step that needs approval can only be approved by someone holding the phone; a comment cannot approve it. If nobody approves it, Mika declines and reports the step.
- **Text first.** Mika reads the screen's structure first and uses screenshots only where needed. Write criteria that can be seen on screen ("an error message is shown"), not pixel checks.
- **Phone must be on and free.** Mika takes a new task only while the phone is unlocked and awake (the owner can turn that off), on Wi-Fi (can be turned off), and not doing another task. One task runs at a time; the others wait in the queue.
- The phone checks every couple of minutes when idle (owner setting), every minute while a task runs and every 20 seconds while waiting for your answer, so pickup is not instant.
- Mika is told to work only inside the app under test, not to change system settings and not to message real people. It does not install or update the app; put the build on the phone first.

## Tips

- One behaviour per issue; short, concrete steps; criteria that are visible on screen.
- Put test accounts in the steps, never real credentials.
- Set min_version whenever you are checking a fix, so an old build fails fast instead of giving a misleading result.
- Use capture: steps when you want to see the whole path, and none for private screens.
- Use attendance: unattended for nightly batches, when nobody will answer.

## curl examples

Use your own token in place of `ghp_PLACEHOLDER` (a fine-grained token with Issues read
and write on the board repository). Keep it out of scripts that you commit.

```bash
OWNER=me REPO=mika-tasks TOKEN=ghp_PLACEHOLDER

# Queue a task (the body is the template above, saved as task.md)
jq -n --rawfile body task.md '{title: "Sign-in rejects a wrong password", body: $body, labels: ["mika-task"]}' |
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" "https://api.github.com/repos/$OWNER/$REPO/issues" -d @-

# See where your tasks are
curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER/$REPO/issues?labels=mika-in-progress&state=open"

# Read the machine result of issue 42
curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER/$REPO/issues/42/comments?per_page=100" |
jq -r '.[].body | capture("<!-- mika:result (?<j>\\{.*\\}) -->").j' | tail -1

# Answer a question Mika asked on issue 42
curl -sS -X POST -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$OWNER/$REPO/issues/42/comments" -d '{"body":"/answer Use the second account"}'

# Run issue 42 again
curl -sS -X DELETE -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$OWNER/$REPO/issues/42/labels/mika-failed"
curl -sS -X POST -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$OWNER/$REPO/issues/42/labels" -d '{"labels":["mika-task"]}'

# Cancel issue 42
curl -sS -X PATCH -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$OWNER/$REPO/issues/42" -d '{"state":"closed"}'
```
