#!/usr/bin/env bash
# End-to-end test for how the launcher treats the version pinned in ./pom.xml.
# The pin comes from whatever repo was cloned, so a path-like value must be refused
# before anything is exec'd, fetched or deleted. Uses a fake `java` on PATH that
# records its arguments, so it needs no build and no network.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
step() { echo -e "${YELLOW}▸${NC} $*"; }
pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*" >&2; exit 1; }

# Fake bundled toolchain: only the jar's file name matters (bundled_version).
HOME_FAKE="$WORK/home"
mkdir -p "$HOME_FAKE/lib"
touch "$HOME_FAKE/lib/brace-0.1.10.jar"

# Pre-cached toolchain for the SNAPSHOT pin, so nothing needs downloading.
CACHE="$WORK/cache"
mkdir -p "$CACHE/0.1.11-SNAPSHOT/lib"

# A directory a traversal pin would reach, holding a classpath that "runs attacker code".
mkdir -p "$WORK/evil/lib"

# Fake java: record that it ran, and with what.
FAKEBIN="$WORK/bin"
mkdir -p "$FAKEBIN"
cat > "$FAKEBIN/java" <<EOF
#!/bin/sh
echo "\$@" >> "$WORK/java.calls"
EOF
chmod +x "$FAKEBIN/java"

PROJECT="$WORK/project"
mkdir -p "$PROJECT"

pin() {
    printf '<project><properties>\n  <brace.version>%s</brace.version>\n</properties></project>\n' "$1" \
        > "$PROJECT/pom.xml"
}

# run_brace <args...>: runs the launcher in the project; sets RC and OUT.
run_brace() {
    rm -f "$WORK/java.calls"
    RC=0
    OUT=$(cd "$PROJECT" && PATH="$FAKEBIN:$PATH" BRACE_HOME="$HOME_FAKE" BRACE_CACHE="$CACHE" \
        BRACE_RELEASE_BASE="file://$WORK/no-such-releases" "$REPO/bin/brace" "$@" 2>&1) || RC=$?
}

step "Valid pins still resolve and exec"
for v in 0.1.10 v0.1.10; do
    pin "$v"
    run_brace status
    [[ $RC -eq 0 ]] || fail "pin '$v' was refused: $OUT"
    grep -q -- "-Dbrace.home=$HOME_FAKE " "$WORK/java.calls" || fail "pin '$v' did not use the bundled toolchain"
done
pin 0.1.11-SNAPSHOT
run_brace status
[[ $RC -eq 0 ]] || fail "pin '0.1.11-SNAPSHOT' was refused: $OUT"
grep -q -- "-Dbrace.home=$CACHE/0.1.11-SNAPSHOT " "$WORK/java.calls" || fail "SNAPSHOT pin did not use the cached toolchain"
pass "0.1.10, v0.1.10 and 0.1.11-SNAPSHOT resolve"

step "'brace version' reports a valid pin"
pin v0.1.10
run_brace version
[[ $RC -eq 0 ]] || fail "version failed: $OUT"
grep -q -- "-Dbrace.pinned=0.1.10 " "$WORK/java.calls" || fail "version did not pass the pin"
pass "version passes the pin"

step "Path-like and malformed pins are refused without exec'ing anything"
BAD=(
    "../evil"
    "../../../../../../../../../../../proc/self/cwd"
    "../../$WORK/evil"
    "0.1.10/../../evil"
    "0.1.10/x"
    "0.1"
    "latest"
    "0.1.10;touch$WORK/pwned"
)
for v in "${BAD[@]}"; do
    pin "$v"
    for cmd in status version; do
        run_brace "$cmd"
        [[ $RC -ne 0 ]] || fail "pin '$v' was accepted for '$cmd'"
        [[ ! -e "$WORK/java.calls" ]] || fail "pin '$v' still exec'd java for '$cmd'"
        grep -q "Invalid <brace.version>" <<<"$OUT" || fail "no clear error for pin '$v': $OUT"
    done
done
[[ ! -e "$WORK/pwned" ]] || fail "a pin was executed as a command"
[[ -d "$WORK/evil/lib" ]] || fail "a traversal pin deleted a directory outside the cache"
pass "bad pins error out, nothing exec'd or deleted"

step "The error names the offending value"
pin "../evil"
run_brace status
grep -q '\.\./evil' <<<"$OUT" || fail "error does not name the value: $OUT"
pass "value is named"

step "No pin and no pom.xml still fall back to the bundled toolchain"
rm -f "$PROJECT/pom.xml"
run_brace status
[[ $RC -eq 0 ]] || fail "no pom.xml should fall back to bundled: $OUT"
grep -q -- "-Dbrace.home=$HOME_FAKE " "$WORK/java.calls" || fail "did not fall back to bundled"
printf '<project></project>\n' > "$PROJECT/pom.xml"
run_brace status
[[ $RC -eq 0 ]] || fail "pom without a pin should fall back to bundled: $OUT"
pass "fallback unchanged"

step "install.sh refuses a path-like BRACE_VERSION or release tag_name"
INSTALL="$WORK/install"
RC=0
OUT=$(BRACE_DIR="$INSTALL" BRACE_VERSION="../../evil" sh "$REPO/install.sh" 2>&1) || RC=$?
[[ $RC -ne 0 ]] || fail "install.sh accepted BRACE_VERSION=../../evil"
grep -q "Invalid brace version" <<<"$OUT" || fail "no clear error for bad BRACE_VERSION: $OUT"
printf '{"tag_name":"v../../evil"}\n' > "$WORK/latest.json"
RC=0
OUT=$(BRACE_DIR="$INSTALL" BRACE_LATEST_URL="file://$WORK/latest.json" sh "$REPO/install.sh" 2>&1) || RC=$?
[[ $RC -ne 0 ]] || fail "install.sh accepted a traversal tag_name"
grep -q "Invalid brace version" <<<"$OUT" || fail "no clear error for bad tag_name: $OUT"
[[ ! -e "$INSTALL" ]] || fail "install.sh created $INSTALL before validating"
pass "install.sh validates both inputs before touching disk"

step "Toolchain downloads must be https (L8)"
pin 0.1.99   # not cached: the launcher would fetch it
rm -f "$WORK/java.calls"
RC=0
OUT=$(cd "$PROJECT" && PATH="$FAKEBIN:$PATH" BRACE_HOME="$HOME_FAKE" BRACE_CACHE="$CACHE" \
    BRACE_RELEASE_BASE="http://127.0.0.1:1/releases" "$REPO/bin/brace" status 2>&1) || RC=$?
[[ $RC -ne 0 ]] || fail "an http:// release base was accepted"
grep -q "non-https" <<<"$OUT" || fail "no clear error for an http:// base: $OUT"
[[ ! -e "$WORK/java.calls" ]] || fail "java ran after a refused download"
RC=0
OUT=$(BRACE_DIR="$INSTALL" BRACE_VERSION=0.1.99 BRACE_RELEASE_BASE="http://127.0.0.1:1/releases" sh "$REPO/install.sh" 2>&1) || RC=$?
[[ $RC -ne 0 ]] || fail "install.sh accepted an http:// release base"
grep -q "non-https" <<<"$OUT" || fail "install.sh: no clear error for an http:// base: $OUT"
RC=0
OUT=$(BRACE_DIR="$INSTALL" BRACE_LATEST_URL="http://127.0.0.1:1/latest.json" sh "$REPO/install.sh" 2>&1) || RC=$?
[[ $RC -ne 0 ]] || fail "install.sh accepted an http:// latest URL"
grep -q "non-https" <<<"$OUT" || fail "install.sh: no clear error for an http:// latest URL: $OUT"
# The explicit override still lets it try (and fail to connect, here).
RC=0
OUT=$(cd "$PROJECT" && PATH="$FAKEBIN:$PATH" BRACE_HOME="$HOME_FAKE" BRACE_CACHE="$CACHE" \
    BRACE_ALLOW_INSECURE_DOWNLOAD=1 BRACE_RELEASE_BASE="http://127.0.0.1:1/releases" "$REPO/bin/brace" status 2>&1) || RC=$?
grep -q "insecure URL" <<<"$OUT" || fail "the override didn't warn: $OUT"
pass "http:// refused in the launcher and installer; override warns"

step "Pins below the launcher's minimum toolchain are refused"
# A pin is committed project content: a pull request could pin an old release to bring back a
# CLI bug fixed since (0.1.7 hands a .brace URL to `cmd /c start` on Windows).
mkdir -p "$CACHE/0.1.7/lib" "$CACHE/0.1.9/lib"
pin 0.1.7
run_brace ops dashboard
[[ $RC -ne 0 ]] || fail "pin 0.1.7 below the minimum was accepted"
grep -q "older than 0.1.8" <<<"$OUT" || fail "no clear error for a pin below the minimum: $OUT"
grep -q "BRACE_ALLOW_OLD_TOOLCHAIN" <<<"$OUT" || fail "the error doesn't name the override: $OUT"
[[ ! -e "$WORK/java.calls" ]] || fail "java ran for a refused pin"
RC=0
OUT=$(cd "$PROJECT" && PATH="$FAKEBIN:$PATH" BRACE_HOME="$HOME_FAKE" BRACE_CACHE="$CACHE" \
    BRACE_ALLOW_OLD_TOOLCHAIN=1 "$REPO/bin/brace" status 2>&1) || RC=$?
[[ $RC -eq 0 ]] || fail "BRACE_ALLOW_OLD_TOOLCHAIN=1 didn't allow the old pin: $OUT"
grep -q -- "-Dbrace.home=$CACHE/0.1.7 " "$WORK/java.calls" || fail "the allowed old pin didn't use its toolchain"
# Above the minimum but older than the launcher: runs, with a note.
pin 0.1.9
run_brace status
[[ $RC -eq 0 ]] || fail "pin 0.1.9 (above the minimum) was refused: $OUT"
grep -q "older than this launcher (0.1.10)" <<<"$OUT" || fail "no note for a pin older than the launcher: $OUT"
pass "below-minimum pin refused (override works); older-than-launcher pin runs with a note"

echo ""
echo -e "${GREEN}All pinned-version tests passed.${NC}"
