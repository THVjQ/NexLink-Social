# §38 — Bug reports and user feedback

> **Confidence:** `DURABLE`
> The shape is settled by two existing constraints rather than chosen: §2.8 #7
> forbids the identifiers a conventional bug report carries, and §6.1 of the
> Privacy Policy forbids an operator surface on the public internet. §38.3 and
> §38.6 are those two constraints applied.

---

## 38.1 The gap this closes

Before this chapter there was **no way for a user to report a fault.** That was
not an oversight in one place; it was three deliberate decisions that happened
to leave nothing behind:

| Surface | State | Why |
|---|---|---|
| Android app | Settings → About had Terms, Privacy Policy, Buy me a coffee — nothing else | never built |
| Element Web | `bug_report_endpoint_url: null`, `UIFeature.feedback: false` | §20.3 — rageshake uploads logs carrying room IDs, user IDs and key material, which §2.8 #7 forbids |
| Matrix | `reportContent` exists and reaches the operator | it reports **people**, not faults (§31.3) |

So a user who hit a bug had the contact address in the policy documents and
nothing else. In practice that means faults were not reported at all, and the
operator's only view of the product's health was the metrics in §27 — which show
that something is wrong, never what the person was trying to do when it went
wrong.

**Turning the rageshake back on is not the answer.** §20.3 was right. This
chapter builds the narrow thing §2.8 #7 permits instead.

---

## 38.2 The shape, and where it comes from

This is modelled on the bug-reporting system in SOS POS, a sibling product, for
one specific reason recorded there: its first version linked to a prefilled
GitHub issue, the repository was private, and every reporter got a 404. **Reports
were silently never filed and the reporter could not tell.** The fix was a
database table and an operator page inside the product.

That reasoning applies harder here. A shop employee might plausibly have a
GitHub account; a messenger user has no reason to, and the entire product
proposition is that they need not identify themselves to anyone to use it.

**Therefore: no external issue tracker anywhere in the reporting path, ever.**
Not a link, not a fallback, not a "power users can also…". This is an invariant
of this chapter and the reason it exists.

### 38.2.1 What is kept from that design

| SOS POS | Here |
|---|---|
| `BugReport` table | `reports` table (§38.4) |
| `kind` = bug / feature | kept — a fault to chase now and a wish for later read completely differently in a list |
| `status` + `admin_note` | kept |
| `reply_seen`, so a reply shows once | replaced by the reference code (§38.5) — there is no account to fan out to |
| operator page listing everything in one place | kept, LAN-only (§38.6) |
| alert on create, inside a catch that can never fail the report | kept, and see §38.8 |

### 38.2.2 What is deliberately different

Three things, each forced:

1. **The automatic context is much thinner** (§38.3). SOS POS attaches the error
   code, incident id, page path, store and reporter — "the three things a
   reporter cannot see". The direct equivalents here are the MXID, the room ID
   and the event ID, and those are precisely what §2.8 #7 forbids.
2. **The reporter may not be able to sign in** (§38.5). A large share of real
   reports are "it will not let me log in". A surface that requires a session
   filters out the reports that matter most.
3. **There is no web application to add a page to.** SOS POS is one Next.js app
   with a database already attached. Here it is a new service, and §38.7 keeps
   it the smallest one that can work.

---

## 38.3 What a report may carry — the binding list

§2.8 #7: *"crash reports, logs and analytics carry no message content, room IDs,
user IDs or key material."* A bug report is all three of those things at once, so
the field list is an allowlist and anything not on it is a defect.

**Carried automatically:**

| Field | Example | Why it is safe |
|---|---|---|
| App version | `0.1.2 (3)` | identifies the build; no user in it |
| Android version | `16 (API 36)` | platform faults are version-shaped |
| Device model | `SM-G990E` | §16.2.1's lesson: the handset matters |
| Screen | `conversation`, `settings` | a stable symbolic name, never a room or user id |
| Kind | `bug` or `feature` | triage |

**Carried only if the reporter types it:** what happened, and what they expected
instead.

**Carried only if the reporter ticks an unticked box:** their MXID.

**Never carried, by construction:**

- Room IDs, event IDs, device IDs, access tokens, key material.
- Message content, in any form, including in a screenshot. There is no
  attachment field (§38.3.2).
- Logs. There is no log upload. This is the rageshake decision, held.
- The reporter's IP address — seen by the service in the act of receiving the
  request, used for rate limiting in memory, and **never written to the
  database** (§38.4.1).

### 38.3.1 The MXID box, and its honest consequence

The box is unticked, matching the report-consent pattern §31.3.2 already
establishes. The copy must state the consequence plainly, because it is real and
it is not obvious:

> Include my username so you can reply
> *Without it this report is anonymous — which also means there is no way to
> reach you if we need more detail.*

A reporter who declines still gets the reference code (§38.5), so declining
costs them the conversation but not the answer.

### 38.3.2 No screenshots, and why that is the right call

A screenshot of a messaging app is message content. Accepting attachments would
mean accepting message content into a database the operator can read, which
falsifies §9.6.1's acceptance screen — the one place the product tells users
what the operator can and cannot see.

The cost is real: some faults are much easier to show than describe. The
mitigation is §38.3's `screen` field plus the reporter's own words, and the
operator asking for detail through Matrix when the MXID was included. **If this
is ever revisited, it is a §2.1 decision reversal and belongs in §2.8, not in a
pull request.**

---

## 38.4 The store

**SQLite, one file, at `/mnt/Pool1-MAIN/social/bugs/bugs.db`.**

Postgres was the obvious choice and is the wrong one here. The service is one
table, written a few times a week by humans typing prose, and read by one
person. Against that, a Postgres container costs a second database to operate,
back up and patch, and §23's backup story would have to grow a second arm.

The file sits on `Pool1-MAIN`, which means it inherits the 15-minute recursive
snapshot and the replication to `Pool2-BACKUP` that every dataset there gets —
a shorter RPO than the nightly `pg_dump` in §23.3 that protects Synapse. Nothing
extra to configure and nothing extra to forget.

```sql
CREATE TABLE reports (
  id            TEXT PRIMARY KEY,   -- the reference code, §38.5
  kind          TEXT NOT NULL DEFAULT 'bug',   -- bug | feature
  what_happened TEXT NOT NULL,
  expected      TEXT,
  app_version   TEXT,
  android_version TEXT,
  device_model  TEXT,
  screen        TEXT,
  mxid          TEXT,               -- NULL unless the reporter ticked the box
  source        TEXT NOT NULL,      -- app | web
  status        TEXT NOT NULL DEFAULT 'open',  -- open | investigating | fixed | wont_fix
  operator_note TEXT,
  created       TEXT NOT NULL,
  updated       TEXT NOT NULL
);
```

`status` and `operator_note` are written only by the operator. The reporter's
own text is never edited in place — a report is evidence about a moment.

### 38.4.1 The IP address is not in that table

Deliberately, and worth stating because every other service in §27 logs it. The
Privacy Policy allows IP retention for 28 days for abuse handling; a bug report
is not abuse handling, and a database the operator reads for product reasons is
the wrong place for connection records. Rate limiting (§38.7.2) holds IPs in
memory only, and the service logs no request lines.

---

## 38.5 The reference code — the reply loop without identity

Every report gets a short random code, shown to the reporter once:

> **Saved. Your reference is `R-7KQ4M2`.**
> Keep it if you want to check back — `nexlink.thvjq.com.au/report/R-7KQ4M2`
> shows the status and any reply.

This is the part that replaces SOS POS's `reply_seen`, and it does a job that
column could not do here: **it closes the loop for an anonymous reporter.** They
can read the status and the operator's reply without an account, without an
MXID, and without the operator knowing who they are.

The code is 6 characters from a 29-character unambiguous alphabet (no O/0,
I/1/L, U/V, so a code read off a phone and typed into a browser survives the
trip) — about 2^29 of space. That is not a secret worth attacking, since it
protects one person's own bug report, but it is far beyond guessing at the rate
limit in §38.7.2. Codes are
single-purpose: the lookup returns the report's own fields and nothing else, so a
guessed code reveals one stranger's bug description and no identity.

---

## 38.6 The operator page is LAN-only

The Privacy Policy §6.1 commits to *"administrative access restricted to the
Operator, over an authenticated private channel, never exposed to the public
internet"*, and §21.4 blocks `/_synapse/admin` for the same reason.

So the operator page gets **no Cloudflare route and no nginx location**. It
answers on `http://192.168.0.10:8066/ops` from the LAN and nowhere else. There
is no login form, because there is no path to it from outside — adding a password
would create the impression that exposing it later is safe.

**The consequence is deliberate: the operator cannot triage from a phone away
from home.** That is the correct trade for a service whose own policy makes this
promise. §38.9 lists the alternatives if that ever stops being acceptable; all
of them involve adding authentication first.

### 38.6.1 What is public

Two paths only, both on the existing hostname via the catch-all route, so
**no Cloudflare dashboard change is needed** — the constraint §26.2.3 and
§17.3.1 already work around:

| Path | Method | Purpose |
|---|---|---|
| `/report/` | GET | the web report form, for someone who cannot open the app |
| `/report/R-xxxxxx` | GET | status and reply for one code |
| `/bugs/api/report` | POST | accepts a report from the app or the form |

---

## 38.7 The service

`infra/social-bugs/social-bugs` — Python 3, standard library only, `python:3.12-alpine`,
port **8066**. The same shape and the same reasoning as `social-deletion`: small
enough to read in one sitting, no framework to track for CVEs, no dependency
resolution at deploy time.

### 38.7.1 Why not static, like the deletion page

§32.3's deletion page is static because deactivation is a normal client
operation the user can authenticate themselves. A bug report has no such luck:
something has to write it down, and that something cannot be the reporter's own
browser. Hence a backend, and hence it is kept tiny.

### 38.7.2 Abuse protection

The POST endpoint is unauthenticated by necessity (§38.2.2 #2), which makes it
the only unauthenticated write surface in the deployment. Three limits, all in
the service:

1. **Rate limit**: 5 reports per IP per hour, token bucket, in memory. Returns
   429 and a plain message, never a silent drop — §38.10's first lesson.
2. **Body cap**: 8 KB. Enough for several paragraphs, far short of a paste
   attack.
3. **Field cap**: the allowlist in §38.3. Unknown keys are **rejected**, not
   ignored — a field that is silently dropped is how CareConnect lost 25 shift
   reports, and the same trap is one `INSERT` away here.

In-memory state means a restart forgives everyone, which is the right failure
direction for a reporting channel.

---

## 38.8 Alerting

A report that sits in a list until somebody looks at the list is, for anything
urgent, the same as not reported. So the service alerts on create — and
**everything about alerting is inside a catch that cannot reach the response.**
A report that fails to save because nobody could be told about it is worse in
every way than one that saves quietly.

The service cannot send the mail itself: it lives in a container and `midclt`
does not exist in there. So it appends one JSON line per report to
`alerts-pending`, and `bug-alert` on the host drains that queue on a 15-minute
TrueNAS cron job (id 6). The queue entry carries the reference and the kind, and
the mail says *go and read it* rather than quoting a user's words into an email —
widening §38.3 by way of a notification would be the same mistake in a different
place.

**Mail works, verified 2026-10-02** — `mail.config` is `luca@reiflers.ch` via
`smtp.protonmail.ch:587` with STARTTLS, and a real `bug-alert run` delivered.
Note this reverses what §27.6 and earlier drafts of this chapter said: mail had
never been configured on Willard as of 2026-09-13, and the Proton SMTP
submission that makes it possible is a paid-plan feature. If it stops working,
587 is the only usable port — 465 times out and 25 is unreachable, both blocked
upstream by Starlink.

`bug-alert` degrades the right way if that changes: it checks `mail.config`
first, and on an unconfigured or failing send it says so, **keeps the queue**,
and truncates it only past 500 entries. Nothing is lost while mail is broken,
and the operator page is always the channel that does not depend on it.

---

## 38.9 What is explicitly not built

- **Automatic crash capture.** It would carry a stack trace, and a stack trace
  from this app carries room and event identifiers in frame arguments. §2.8 #7.
- **Attachments and screenshots.** §38.3.2.
- **An in-app list of my past reports.** The reference code does the job without
  the app needing to authenticate against a second service, and a list keyed on
  MXID would mean storing the MXID always rather than on request.
- **A public operator page**, however well authenticated. §38.6.
- **Triage from outside the LAN.** If this becomes necessary the order is:
  authentication first, then a Cloudflare route, then Cloudflare Access in front
  — the same sequence §22.9 sets out for the Crafty panel. Not one of those
  steps may be skipped because the page "is not very sensitive"; it contains
  users' own words about what they were doing.

---

## 38.10 Lessons imported from the sibling product

Both were paid for once already and neither is obvious:

1. **A reporting path that fails must say so.** SOS POS's GitHub link returned
   404 and the reporter saw a success screen. Every failure mode here — rate
   limit, validation, service down — returns a message the reporter can read,
   and the app surfaces it rather than showing a green tick.
2. **Adding the alert must not break the create.** In SOS POS, the route added
   to send the alert email stamped `created_by` on a model that had no such
   column, and Prisma rejected every insert: *reporting a bug stopped working
   entirely, in the commit that added notifications about it.* Here the insert
   and the alert are separate statements, the alert runs after the row is
   committed, and `tools/check-bug-report.sh` asserts a report still saves when
   the alert path raises.
