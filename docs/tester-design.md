# Mika Tester: GitHub issues as a task board (design)

Status: **design only**. Nothing in this document is implemented yet beyond the
`mikatester` build variant (see "Phase 0"). The board watcher is deliberately not built.

Mika Tester (`com.wangzan101.mika.tester`, label "Mika Tester", default MCP port 28765)
is a third side-by-side install of Mika. Its job: other agents (OpenBot, CI, a
developer's coding agent) file a test request as a GitHub issue, the phone picks it
up when it is free, runs the test in an installed app on the hidden display, and
answers on the issue with pass/fail, what it observed, the steps it took and
screenshots. No inbound port, no tunnel: the phone only makes outbound HTTPS calls
to `api.github.com`.

The board is a new **channel** in the existing run model (architecture §2), not a
new execution path. Everything that the agent does still goes through
`TaskService` → `RunAssembler` → `AgentLoop`, with the usual policies.

---

## 1. Issue format

An issue is a task when **all** of these hold:

- it is open and carries the label `mika-task`;
- its author's login is in the owner allowlist (§4);
- its body contains exactly one fenced block whose info string is `mika-task`.

Text outside the block is free-form notes for humans; it is passed to the agent as
"notes from the requester" but is never parsed for fields.

````markdown
Login screen should reject a wrong password.

```mika-task
version: 1
app: com.example.shop            # package to test (required, must be installed)
min_version: 2.3.0               # optional; fail fast if the installed versionName is older
reset: force_stop                # none | force_stop (default). clear_data is not offered in v1
steps:                           # what to do, in order (required, 1..30 items)
  - Open the app and go to Account › Sign in
  - Enter user "qa+1@example.com" and password "wrong-password"
  - Tap Sign in
pass:                            # pass criteria, each judged separately (required, 1..10)
  - An error message about the password is shown
  - The user stays on the sign-in screen
capture: failures                # none | failures (default) | steps | every_action
timeout_min: 15                  # hard limit for the whole run; default 15, max 60
ask_timeout_min: 30              # how long to wait for an answer to a question; default 30, max 240
attendance: reachable            # reachable (default) | unattended (no questions at all)
```
````

Parsing rules (`TesterTaskSpec.parse`, pure Kotlin in `core`):

- The block is a deliberately small YAML subset: `key: scalar` and `key:` followed by
  `- item` lines. No anchors, no flow syntax, no multi-document. Unknown keys →
  task is rejected with a `mika-failed` comment listing them (typos must not be ignored silently).
- `app` must match `^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$`; Mika's own three
  package IDs are refused (a tester run must not drive Mika itself).
- Limits are clamped server-side (on the phone), never trusted from the issue.
- Invalid spec → no run; the issue gets a comment explaining the error and `mika-failed`.

The goal given to the agent is rendered from the spec (app, steps, criteria, notes)
plus a fixed instruction: test only inside `app`, do not change system settings, and end
with one `VERDICT:` line and one line per criterion (`PASS`/`FAIL`/`UNKNOWN` + reason).
The poster (§5) parses that; if it cannot, the result is `UNVERIFIED`, never `PASS`.

## 2. Labels (state machine)

| Label | Meaning | Set by |
|---|---|---|
| `mika-task` | queued, waiting for a phone | requester |
| `mika-in-progress` | claimed and running | phone (removes `mika-task`) |
| `mika-needs-input` | the run asked a question and is waiting (§6) | phone; removed when answered or timed out |
| `mika-done` | finished, verdict PASS | phone (removes `mika-in-progress`) |
| `mika-failed` | finished with FAIL, UNVERIFIED, timeout, error or invalid spec | phone |

```
mika-task ──claim──▶ mika-in-progress ──▶ mika-done
                         │   ▲                 
                   ask   ▼   │ answer/timeout  ──▶ mika-failed
                   mika-needs-input
```

- Re-queue: the requester removes `mika-done`/`mika-failed` and adds `mika-task` again.
  Each attempt is a new run; earlier result comments stay.
- Cancel: closing the issue (or removing `mika-in-progress`) cancels the run
  (`ChannelInput.Stop`); the phone posts a short "cancelled" notice and does not relabel.
- The phone never closes issues; the requester decides.
- The phone creates missing labels once at setup (needs Issues write), with fixed colours.
- Claim protocol (works for one phone; tolerates two by accident): add `mika-in-progress`,
  remove `mika-task`, post a claim comment with a hidden marker
  `<!-- mika:claim device=<installId> run=<runId> -->`, then re-read comments; if an
  earlier claim marker from another device exists, back off silently.

## 3. Phone-side polling

**API.** GitHub REST v3, `Accept: application/vnd.github+json`,
`X-GitHub-Api-Version: 2022-11-28`, OkHttp (already a `core` dependency).

**Token.** A *fine-grained personal access token* restricted to **one repository**,
permissions **Issues: read and write** (Metadata: read is implied). Nothing else.
If screenshots go into the same repo (§5 option A) it also needs **Contents: read and
write**; the recommended setup puts artifacts in a *separate* private repo so the
board token stays Issues-only (see §5). Expiry ≤ 90 days; the settings page shows the
expiry from the `github-authentication-token-expiration` response header and warns
7 days ahead. Stored with the existing encrypted secret store (same as the ElevenLabs
key); never logged, never put into the agent context, never sent to the model.

**Requests.**

- Queue: `GET /repos/{o}/{r}/issues?labels=mika-task&state=open&sort=created&direction=asc&per_page=10`
  with `If-None-Match: <etag>`. A `304 Not Modified` costs no rate limit, so idle polling is cheap.
- Waiting for an answer / cancel: `GET /repos/{o}/{r}/issues/{n}/comments?since=<askTime>`
  and `GET /repos/{o}/{r}/issues/{n}` (state, labels), each with its own ETag.
- The ETag cache is per URL, in memory plus a small file so a restart does not cost a full
  poll. Only `200` with a parseable body replaces the cached etag.
- Pull requests also appear in `/issues`; items with `pull_request` are ignored.

**Cadence.** Default every 2 min while idle, 20 s while a run is waiting for an answer,
never faster than 15 s. Back off ×2 up to 30 min on errors. Honour `Retry-After`,
and when `x-ratelimit-remaining` < 50 sleep until `x-ratelimit-reset`. `401` (revoked or
expired token) stops the watcher and shows a notification; it does not retry.

**When it polls at all** — every condition must hold; checked before each poll:

1. The board is enabled in settings (off by default, and off in the other two builds;
   the setting only exists in Mika Tester).
2. The screen is on and the device is unlocked (`KeyguardManager.isDeviceLocked == false`).
   Locked → pause; resumes on `ACTION_USER_PRESENT`. Mika's remote unlock is **not** used
   by the board.
3. Mika is idle: no run in progress or queued in `TaskCenter`, no takeover active. A board
   task never interrupts the user's own conversation; if the user starts something while a
   board task runs, the board task keeps its own conversation (parallel rules as today).
4. Optional "Wi-Fi only" (default on): `NetworkCapabilities` has `TRANSPORT_WIFI` and is not metered.
5. Optional "only while charging" (default off).

Host: a foreground service in the app (`specialUse`, persistent "Tester board on"
notification with a Pause action). Polling stops when any gate fails, so there is no
wake-lock and Doze is respected. A later optional "nudge" (§9) can trigger an immediate poll.

## 4. Owner allowlist

`OwnerIdentity` for the board is a fixed set of GitHub logins (case-insensitive) entered in
settings, plus the numeric user IDs resolved once via `GET /users/{login}` and stored —
matching is done on **IDs**, so a renamed or re-registered login cannot inherit trust.

- Only issues *authored* by an allowlisted user become tasks.
- Only comments by allowlisted users count as answers.
- Bots/apps (`user.type == "Bot"`) are refused unless their exact login is listed.
- Edits: REST does not say who last edited a body. v1 requires a **private repo where only
  allowlisted users have write access** (documented in the setup screen), and the phone
  snapshots the body hash at claim time; a body changed after claim does not affect the run.
  (Later: GraphQL `issue { editor { login } }` to refuse bodies last edited by a non-owner.)

## 5. Result comment

One comment per run, posted through the Outbox (§7). Visible part:

```markdown
### ✅ PASS — com.example.shop 2.4.1          (or ❌ FAIL / ⚠️ UNVERIFIED / ⏱️ TIMEOUT / 💥 ERROR)

| Criterion | Result | Note |
|---|---|---|
| An error message about the password is shown | PASS | "Incorrect password" under the field |
| The user stays on the sign-in screen | PASS | |

**Observed**
The app showed "Incorrect password. 2 attempts left." and kept the form.

**Steps** (12 actions, 1 m 48 s)
1. Launched com.example.shop
2. Tapped "Account" → "Sign in"
3. Typed into "Email" / "Password" (values hidden)
…

**Screenshots**
[01 sign-in](…/0003.jpg) · [02 after tap](…/0007.jpg)

<sub>Mika Tester 0.1.25-tester · Pixel 8 · Android 15 · run 20261102-101500-ab12cd</sub>
<!-- mika:result {"v":1,"run":"20261102-101500-ab12cd","verdict":"pass","criteria":["pass","pass"],"delivery":"<outboxId>"} -->
```

- The hidden JSON line is the machine-readable result for other agents; they should
  read it instead of scraping the markdown.
- **Steps** come from the run's `events.jsonl` (tool calls, condensed by the same
  summariser as the run record), not from the model's prose. Typed text is shown only as
  "(values hidden)"; the existing `Redactor` is applied to everything.
- App version comes from `PackageManager` at run start, not from the agent.
- Device line: model, Android version, Mika build. No serial, no account names.

**Screenshots.** Source is the run record (`files/runs/<id>/screens/NNNN.jpg`, 720 px,
JPEG q70, ~50–150 KB). Selection by `capture`: `none`; `failures` (default: last 3 screens
plus the screen referenced by each FAIL/UNKNOWN criterion); `steps` (one per step boundary);
`every_action` (capped at 40). Upload options:

- **A. `mika-artifacts` branch via the Git Data API** (recommended). One commit per run:
  `POST /git/blobs` per image, `POST /git/trees`, `POST /git/commits`, `PATCH /git/refs/heads/mika-artifacts`
  (orphan branch created on first use). Path `runs/<issue>/<runId>/NNNN.jpg`. Linked as
  `https://github.com/{o}/{r}/blob/mika-artifacts/<path>?raw=true`, which renders inline for
  anyone who can read the (private) repo.
- **B. Contents API**, `PUT /repos/{o}/{r}/contents/<path>` per file (one commit each).
  Simpler; used as the fallback when the Git Data calls fail.
- **Where:** default is a *separate* private artifacts repo (e.g. `{o}/{r}-mika-artifacts`)
  with its own fine-grained token, Contents read/write only. That keeps the board token
  Issues-only, and a leaked artifacts token cannot touch the issues. Using the same repo
  is allowed but the settings page states that the token then needs Contents write, which
  also allows pushing to every branch of that repo.
- GitHub's browser-only "user-attachments" upload is not a public API and is not used.
- Retention: the phone keeps the last 50 runs on the branch by rewriting the branch
  as a single new commit when over the limit (it is an orphan, force-update is fine there).
  Upload failure never blocks the verdict comment; it says "screenshots not uploaded: <reason>".

## 6. `ask_user`, confirmations and attendance

Runs from the board use `RunOrigin.Channel` with the attendance from the spec:

| Spec `attendance` | Provenance | Questions | Confirmations | Payment |
|---|---|---|---|---|
| `reachable` (default) | `Channel` + `Reachable` | comment + `mika-needs-input`, wait `ask_timeout_min: N` minutes | never (channel has `confirms = false`) → treated as "no" | denied (`ApprovalPolicy`: not `Ui`) |
| `unattended` | `Channel` + `Unattended` | tools removed by `RunAssembler` | removed | denied |

- **Input ask** (`Delivery.Ask` kind Input): the phone posts
  `**Mika needs input:** <question>` with a hidden `<!-- mika:ask id=<askId> run=<runId> -->`
  marker, adds `mika-needs-input`, and switches to fast comment polling. The **first**
  comment after the ask by an allowlisted user becomes `ChannelInput.Text(replyToAskId = askId)`.
  A reply that starts with `/answer` is taken verbatim after the prefix; any other
  allowlisted comment counts too (agents tend not to use prefixes). Comments that arrive while
  no ask is open are ignored — they never start a new task (unlike the local channel, where an
  unrelated message cancels the ask and starts a new task, see `ChannelGateway`).
- Timeout: `UserExchange` returns "no answer"; the run continues or gives up as it would
  for MCP runs; the label is removed and the result notes the unanswered question.
- **Confirm ask** (sensitive actions): `ChannelGateway` only accepts confirmations on a channel
  with `confirms = true`; the board is `false`, so the run sees "not confirmed". A comment
  saying "yes" can never approve a risky step. That is intentional: issue text is easy to forge
  relative to the phone owner's tap.
- **Takeover** (`HandToUser`): posted as a notice ("needs a human on the phone: …"); the run
  waits the ask timeout like today's reachable runs, then fails. Nobody at the issue can do it.
- Payment and anything that `ApprovalPolicy` treats as spending is always denied for
  `Channel` origin regardless of the global ask/autonomous switch — the tester must not
  rely on the autonomous early return in `ConfirmDecision.judge` (roadmap known issue 1);
  phase 4 adds an explicit `origin == Channel && channel is board` check before it.

## 7. Plugging into the core run model

```
GitHubBoard (core/queue/github)        TaskQueue impl: REST, ETag cache, claim, comments, labels
      │ implements
TaskQueue  ── used by ──▶ QueueChannel : Channel   (core/run, generic, pure JVM)
                              │ start(sink): ChannelInput.Text(new task) / Text(replyToAskId) / Stop
                              ▼
                       ChannelGateway(owners = BoardOwners(allowlist), activeAsk, route)
                              │ route.newTask(peer, text)
                              ▼
          TaskService.startChannel(goal, threadId, RunProvenance(Channel, attendance))
                              │ AgentLoop … Delivery.Ask / Progress / Result / Notice
                              ▼
          Outbox (FileOutboxStore) ──▶ QueueChannel.send(peer, delivery) ──▶ ResultPoster
```

- `Channel.id` = `github:{owner}/{repo}`; `ChannelPeer(channelId, peerId = "<userId>",
  threadId = "gh-<owner>-<repo>-<issue>")`. The thread id doubles as the Mika conversation id,
  so the run shows up as its own conversation in the app (read-only, marked "Tester board").
- `confirms = false`; `isAlive` = gates of §3 hold and the last poll succeeded.
- `Delivery.Result` → result comment + label swap; `Delivery.Ask` → §6; `Delivery.Progress`
  → at most one "status" comment per run, edited in place (`PATCH /issues/comments/{id}`),
  throttled to once per minute; `Delivery.Notice` → plain comment.
- **Outbox and uncertain sends.** As everywhere, a delivery is recorded before sending and an
  `Uncertain` send is never retried blindly. The board can do better than chat channels:
  each comment carries `<!-- mika:delivery <outboxId> -->`, so after a crash the channel lists
  the issue's comments and marks the delivery `Delivered` if the marker exists, otherwise it
  may resend once. Label changes are idempotent and simply re-applied.
- Artifacts are read through an app-side `RunArtifacts` (by run id → `events.jsonl`, screens),
  the same code that phase 1 exposes on the remote API.
- `QueueChannel` and `TaskQueue` are generic so a later queue (e.g. GitLab issues, a shared
  JSON file) only adds a client.

## 8. Security risks

| Risk | Mitigation |
|---|---|
| Phone token leaks (backup, logs, root) | fine-grained, one repo, Issues only, short expiry; encrypted store; excluded from backup; never in agent context or logs |
| Someone else files or edits an issue (prompt injection with the owner's phone) | ID-based allowlist on author and answer comments; private repo with only owners as writers; body hash frozen at claim |
| Content inside the tested app injects instructions | same as today: screen text is observation, not instruction; run limited to `app` (phase 4 adds an app allowlist so launching other apps fails) |
| The run uses the owner's real accounts in other apps | test only the named package; recommend a dedicated test profile / accounts on the tester phone |
| Destructive or outward actions (send, delete, post) | confirmations impossible on this channel → treated as no; payment always denied; spec cannot raise policy |
| Screenshots leak personal data to GitHub | only of the tested app; `capture: none` available; private artifacts repo; `FLAG_SECURE` screens come out black and are not retried |
| Typed secrets in steps (test passwords) | step list hides typed values; result comment never echoes values from `steps` |
| Unlocked phone running unattended | runs only while already unlocked and idle; never uses remote unlock; Pause in the notification; user activity in Mika takes priority |
| Flooding the queue / rate limits | ≤ 1 run at a time, daily cap (default 30 runs), clamp timeouts, ETag 304 polling, backoff |
| Duplicate execution (two phones, crash) | claim marker + re-read; outbox delivery markers; re-queue only by label |
| Mika Tester confusion with the other builds | separate package, port, label, data; the board setting exists only in this build |

## 9. Phased commit plan

0. **Done:** `mikatester` build type (AGP forbids build types starting with `test`),
   `mika_cli.py --package`, `build-tester-apk.yml` → `tester-latest/mika-tester.apk`, this document.
1. **Artifacts endpoints.** `RunArtifacts` over `RunStore`; remote API
   `GET /api/v1/tasks/{id}/steps` (redacted step list) and `GET /api/v1/tasks/{id}/screens/{n}`
   (JPEG), run id in the task snapshot; CLI `task steps` / `task screens --out DIR`. Tests:
   `RemoteTaskApi` unit tests, `:core:cliTest`.
2. **QueueChannel core.** `TaskQueue` interface, `QueueChannel : Channel`, `TesterTaskSpec.parse`,
   goal renderer, verdict parser, result formatter (markdown + hidden JSON). Pure JVM tests with a
   fake queue: new task → `Text`, ask → reply mapping, stray comments ignored, close → `Stop`,
   confirm never accepted.
3. **GitHub board client.** `core/queue/github/GitHubBoard : TaskQueue` on OkHttp; ETag cache;
   claim protocol; labels; comments with markers; Git Data / Contents upload. Tests with
   `MockWebServer` (fake HTTP): 304 path, rate-limit headers and `Retry-After`, 401 stop,
   pagination, PR items skipped, allowlist by ID, claim race, delivery-marker reconciliation.
4. **App service + settings.** Foreground service with the gates of §3, `TaskService.startChannel`
   with `RunOrigin.Channel`, explicit payment/confirm guard, target-app allowlist for the run,
   settings page only in the tester build (repo, tokens, allowlist, interval, Wi-Fi only, charging
   only, default capture, artifacts repo, daily cap), strings in `values`, `values-zh`,
   `values-b+zh+Hant` (en / zh-Hans / zh-Hant).
5. **Result poster.** Wire Outbox deliveries to comments/labels, screenshot selection and upload,
   status comment editing, crash reconciliation; architecture §17 and roadmap entry.
6. **Later / optional.** Immediate-poll nudge (remote API `POST /api/v1/board/poll` over the
   existing token, or ntfy/UnifiedPush); GraphQL editor check; `min_version` install hints;
   a small reference script for requesting agents in `tools/`.

Non-goals for v1: installing the app under test (do it with adb or by hand), multiple phones
sharing one board by design, GitHub App authentication, webhooks into the phone.
