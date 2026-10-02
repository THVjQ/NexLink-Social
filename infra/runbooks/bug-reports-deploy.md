# Deploying the bug-report service — §38

**Status: NOT DEPLOYED.** Everything below is written, tested locally and
staged. Nothing is live: there is no `nexlink-social-bugs` app, no nginx
location, and no cron job. This file is the one reviewed step that changes that.

Read [§38](../../docs/social/38-bug-reports.md) first — particularly §38.6,
because step 3 is where it would be easy to publish the operator page by
accident.

## What is already on Willard

Inert files, copied 2026-10-02. Nothing reads them yet:

| Path | What |
|---|---|
| `/mnt/Pool1-MAIN/social/bugs-app/social-bugs` | the service |
| `/mnt/Pool1-MAIN/social/bugs-page/index.html` | the public form |
| `/mnt/Pool1-MAIN/social/bugs-tools/bug-alert` | the host-side notifier |
| `/mnt/Pool1-MAIN/social/bugs/` | empty — the SQLite file is created on first start |

To remove the staging entirely: `sudo rm -rf /mnt/Pool1-MAIN/social/bugs
/mnt/Pool1-MAIN/social/bugs-app /mnt/Pool1-MAIN/social/bugs-page
/mnt/Pool1-MAIN/social/bugs-tools`. Nothing else has been touched.

---

## Step 0 — re-sync the staged files

They were copied before the last two edits (the hardened rate-limit address),
so push them again rather than assuming:

```bash
cd ~/NexLink-Social/infra/social-bugs
scp social-bugs willard-lan:/tmp/social-bugs
scp page/index.html willard-lan:/tmp/report-index.html
scp bug-alert willard-lan:/tmp/bug-alert
ssh willard-lan 'sudo install -m 0644 /tmp/social-bugs /mnt/Pool1-MAIN/social/bugs-app/social-bugs'
ssh willard-lan 'sudo install -m 0644 /tmp/report-index.html /mnt/Pool1-MAIN/social/bugs-page/index.html'
ssh willard-lan 'sudo install -m 0755 /tmp/bug-alert /mnt/Pool1-MAIN/social/bugs-tools/bug-alert'
```

## Step 1 — create the app

Port **8066**. Confirmed free on 2026-10-02: 8060 synapse, 8061 element-web,
8062 sygnal, 8063 lk-jwt, 8064 synapse metrics, 8065 element-call.

```bash
scp infra/social-bugs/app.json willard-lan:/tmp/bugs-app.json
ssh willard-lan 'sudo bash -c "midclt call -j app.create \"\$(cat /tmp/bugs-app.json)\""'
```

Then confirm it is actually healthy rather than merely present — **a custom app
whose image has no `curl` sits in DEPLOYING forever while working fine**, which
is how `nexlink-social-push` went months without ever being healthy. The
healthcheck in `app.json` is therefore a `python3` probe, not curl:

```bash
ssh willard-lan 'sudo midclt call app.query | jq ".[] | select(.name==\"nexlink-social-bugs\") | {name,state}"'
ssh willard-lan 'curl -s http://127.0.0.1:8066/health'          # {"ok": true}
ssh willard-lan 'curl -s http://127.0.0.1:8066/ops | head -c 200'
```

## Step 2 — mount the page into Element Web

The page is served by element-web's nginx, so its container needs the mount.
Round-trip `app.config` → edit → `app.update`, and **strip the
middleware-injected blocks first** or the payload is ~300 KB and sudo fails with
`argv mismatch`:

```bash
ssh willard-lan 'sudo midclt call app.config nexlink-social-web \
  | jq "del(.ix_context,.ix_certificates,.ix_certificate_authorities,.ix_volumes)" \
  > /tmp/ew.json'
```

Add to `services["social-element-web"].volumes`:

```json
{"type": "bind", "source": "/mnt/Pool1-MAIN/social/bugs-page", "target": "/report-page", "read_only": true}
```

## Step 3 — the nginx template — READ §38.6 BEFORE EDITING

`infra/element-web/default.conf.template` in this repo is now the source of
truth and already contains the change. It adds exactly two locations:

- `location /report` → the static form, from `/report-page/`
- `location /bugs/api/` → proxy to `192.168.0.10:8066/api/`

**There is deliberately no `location /ops`.** That absence is the entire
mechanism keeping the operator page off the public internet, because the
Cloudflare catch-all route sends everything that is not `/_matrix` to this
nginx. Adding one publishes a page carrying users' own words about what they
were doing, with no authentication in front of it.

```bash
ssh willard-lan 'sudo md5sum /mnt/Pool1-MAIN/social/element-web/nginx/default.conf.template'
# 879380e8744010c48f9b39b4200bc675 = the pre-change file this repo copy was taken from.
# A different hash means someone edited it on the box; merge rather than overwrite.
scp infra/element-web/default.conf.template willard-lan:/tmp/ew-nginx.conf
ssh willard-lan 'sudo install -m 0644 /tmp/ew-nginx.conf \
  /mnt/Pool1-MAIN/social/element-web/nginx/default.conf.template'
```

Then apply the volume change from step 2, which also restarts nginx:

```bash
ssh willard-lan 'sudo bash -c "midclt call -j app.update nexlink-social-web \"\$(cat /tmp/ew.json)\""'
```

The template is mounted over `/etc/nginx/templates/`, **not** over
`conf.d/default.conf` — the image generates that file at startup with envsubst,
and a read-only mount over it stops the container booting at all.

## Step 4 — verify, publicly and negatively

Both halves matter. The second is the one that is easy to skip:

```bash
# the form and the API are reachable
curl -sI https://nexlink.thvjq.com.au/report/ | head -1
curl -s -X POST https://nexlink.thvjq.com.au/bugs/api/report \
  -H 'Content-Type: application/json' \
  -d '{"what_happened":"deploy smoke test","source":"web"}'
# -> {"id": "R-XXXXXX"}; then read it back:
curl -s https://nexlink.thvjq.com.au/bugs/api/report/R-XXXXXX

# the reference-code URL form resolves to the page (the try_files fallback)
curl -sI https://nexlink.thvjq.com.au/report/R-XXXXXX | head -1   # expect 200

# THE OPERATOR PAGE MUST NOT BE PUBLIC
curl -s -o /dev/null -w '%{http_code}\n' https://nexlink.thvjq.com.au/ops
# expect 404 — anything else means step 3 went wrong, and the fix is to remove
# the location, not to add a password.
```

Delete the smoke-test report afterwards from the operator page (set it
`wont_fix`) or directly:

```bash
ssh willard-lan 'sudo sqlite3 /mnt/Pool1-MAIN/social/bugs/bugs.db \
  "DELETE FROM reports WHERE what_happened = \"deploy smoke test\";"'
```

## Step 5 — the alert cron job

As a TrueNAS cron job via the API, so it lives in the config database and
survives an update — not as host crontab:

```bash
ssh willard-lan 'sudo midclt call cronjob.create "{
  \"user\": \"root\",
  \"command\": \"/mnt/Pool1-MAIN/social/bugs-tools/bug-alert run\",
  \"description\": \"NexLink Social bug-report alerts (§38.8)\",
  \"schedule\": {\"minute\": \"*/15\", \"hour\": \"*\", \"dom\": \"*\", \"month\": \"*\", \"dow\": \"*\"},
  \"enabled\": true,
  \"stdout\": false,
  \"stderr\": false
}"'
```

**It will not deliver anything yet, and that is expected.** Willard's
`mail.config` has an empty `fromemail` and `outgoingserver` with `smtp: false`,
so every mail path on the box is dead. `bug-alert` detects that, says so, and
keeps the queue. Check with:

```bash
ssh willard-lan 'sudo /mnt/Pool1-MAIN/social/bugs-tools/bug-alert status'
```

Configuring Credentials → Email (**587 with STARTTLS** — 465 and 25 are blocked
upstream) switches delivery on with no code change. Until then the operator page
is the real channel.

## Step 6 — the app

The Android half compiles and passes the invariants but has **not been run on a
handset** — no device was attached when it was written, and §34 is emphatic that
reading is not verification. Before shipping it:

1. `./gradlew :social:assembleDebug`, install, open Settings → About → Report a
   problem.
2. File one report of each kind, with the username box ticked and unticked.
3. Confirm the reference code shows, and that the code resolves on the web page.
4. Force a failure — stop the app, or point it at a dead host — and confirm the
   screen says so instead of showing a success state (§38.10 #1).
5. `tools/a11y-audit.py <serial>`, scrolling first: uiautomator clips bounds to
   the viewport and reports a good 48dp control at a scroll edge as 19dp.

## Rollback

```bash
ssh willard-lan 'sudo midclt call -j app.delete nexlink-social-bugs'
```

Then restore the nginx template from `git show HEAD~1:infra/element-web/default.conf.template`
(or the hash above), re-apply `app.update` on `nexlink-social-web`, and delete
the cron job. The SQLite file under `/mnt/Pool1-MAIN/social/bugs/` survives a
delete and holds the reports — it is on `Pool1-MAIN`, so it is also in the
15-minute snapshots and replicated to `Pool2-BACKUP`.
