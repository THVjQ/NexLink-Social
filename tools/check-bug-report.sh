#!/usr/bin/env bash
# docs/social/38-bug-reports.md §38.10 — the two lessons, asserted.
#
# Both were paid for once in the sibling product and neither is obvious:
#
#   1. A reporting path that fails must SAY so. SOS POS's GitHub link returned
#      404 while the reporter saw a success screen, so reports were silently
#      never filed.
#   2. Adding the alert must not break the create. In SOS POS the route added to
#      send the alert email stamped a column the model did not have, and Prisma
#      rejected every insert: reporting a bug stopped working entirely, in the
#      commit that added notifications about it.
#
# Plus §38.3's allowlist, which is the CareConnect trap — a field that is
# silently dropped loses the whole report and returns success.
#
# Runs the real service against a scratch database on a high port. No network,
# no Willard, no containers.
set -uo pipefail
cd "$(dirname "$0")/.."

SVC=infra/social-bugs/social-bugs
PORT=${PORT:-8777}
TMP=$(mktemp -d)
trap 'kill "${PID:-0}" 2>/dev/null; rm -rf "$TMP"' EXIT

fail=0
pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=1; }

post() {
  curl -s -o "$TMP/body" -w '%{http_code}' -X POST \
    "http://127.0.0.1:$PORT/api/report" \
    -H 'Content-Type: application/json' -d "$1"
}

echo "§38 bug-report checks"
echo

# ── The alert path is poisoned on purpose ───────────────────────────────────
# ALERT_QUEUE points at a directory, so every open() for append raises. §38.10
# #2: the report must still save, and the caller must still see 201.
PORT=$PORT DB_PATH="$TMP/bugs.db" ALERT_QUEUE="$TMP" \
  python3 "$SVC" > "$TMP/svc.log" 2>&1 &
PID=$!

for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
  sleep 0.25
done
if ! curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  bad "service did not start — see $TMP/svc.log"
  cat "$TMP/svc.log"
  exit 1
fi

code=$(post '{"what_happened":"the alert path is broken on purpose"}')
ref=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("id",""))' "$TMP/body" 2>/dev/null)
if [ "$code" = "201" ] && [ -n "$ref" ]; then
  pass "a report saves when the alert path raises (§38.10 #2)"
else
  bad "alert failure cost the report: http=$code body=$(cat "$TMP/body")"
fi

# And it must be readable back, i.e. actually committed rather than just acked.
if [ -n "$ref" ] && curl -sf "http://127.0.0.1:$PORT/api/report/$ref" \
     | grep -q "the alert path is broken on purpose"; then
  pass "that report is committed and readable by its reference (§38.5)"
else
  bad "report $ref was acknowledged but cannot be read back"
fi

# ── §38.3 is an allowlist ───────────────────────────────────────────────────
# An unknown key is a 400. Accepting and dropping it is how CareConnect lost 25
# shift reports, and how a room id would arrive here one day.
code=$(post '{"what_happened":"x","room_id":"!r:srv","notes":"body"}')
if [ "$code" = "400" ] && grep -q "unknown field" "$TMP/body"; then
  pass "unknown fields are rejected, not silently dropped (§38.3, §38.7.2)"
else
  bad "unknown field accepted — http=$code body=$(cat "$TMP/body")"
fi

# An empty report is a 400 with words, never a success screen over nothing.
code=$(post '{"what_happened":"   "}')
if [ "$code" = "400" ] && grep -qi "cannot be empty" "$TMP/body"; then
  pass "an empty report is refused with a readable reason (§38.10 #1)"
else
  bad "empty report not refused properly — http=$code body=$(cat "$TMP/body")"
fi

# ── The lookup must not become an identity oracle ───────────────────────────
# §38.5: a guessed code reveals one stranger's bug text and NO identity. If
# mxid ever appears in the public lookup this check fails.
post '{"what_happened":"carries an mxid","mxid":"@someone:nexlink.thvjq.com.au"}' >/dev/null
ref2=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("id",""))' "$TMP/body" 2>/dev/null)
if [ -n "$ref2" ]; then
  if curl -sf "http://127.0.0.1:$PORT/api/report/$ref2" | grep -q "someone"; then
    bad "the public lookup leaked the reporter's MXID (§38.5)"
  else
    pass "the public lookup carries no identity (§38.5)"
  fi
else
  bad "could not file the MXID-carrying report to test the lookup"
fi

echo
if [ "$fail" = 0 ]; then
  echo "  all §38 checks passed"
else
  echo "  §38 checks FAILED"
fi
exit "$fail"
