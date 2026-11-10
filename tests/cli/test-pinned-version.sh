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

# Pre-cached toolchain for the SNAPSHOT pin, so nothing needs downloading. Its version is above
# any floor this launcher will have for a while, so the step doesn't depend on MIN_TOOLCHAIN.
CACHE="$WORK/cache"
mkdir -p "$CACHE/0.99.0-SNAPSHOT/lib"

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

# run_brace <args...>: runs $LAUNCHER in the project with $BUNDLED as its bundled toolchain;
# sets RC and OUT. ALLOW_OLD=1 sets BRACE_ALLOW_OLD_TOOLCHAIN=1 (passed either way, so a value in
# the caller's environment can't leak in). Every release base is a missing file:// tree: nothing
# is ever downloaded.
LAUNCHER="$REPO/bin/brace"
BUNDLED="$HOME_FAKE"
ALLOW_OLD=""
run_brace() {
    rm -f "$WORK/java.calls"
    RC=0
    OUT=$(cd "$PROJECT" && PATH="$FAKEBIN:$PATH" BRACE_HOME="$BUNDLED" BRACE_CACHE="$CACHE" \
        BRACE_ALLOW_OLD_TOOLCHAIN="$ALLOW_OLD" \
        BRACE_RELEASE_BASE="file://$WORK/no-such-releases" "$LAUNCHER" "$@" 2>&1) || RC=$?
}

# launcher_with_floor <v>: a copy of the launcher with MIN_TOOLCHAIN set to <v>, so the floor
# rules are tested independently of the value this checkout ships.
launcher_with_floor() {
    local out="$WORK/brace-floor-$1"
    sed "s/^MIN_TOOLCHAIN=\".*\"\$/MIN_TOOLCHAIN=\"$1\"/" "$REPO/bin/brace" > "$out"
    chmod +x "$out"
    grep -qxF "MIN_TOOLCHAIN=\"$1\"" "$out" || fail "could not set MIN_TOOLCHAIN in the launcher copy"
    echo "$out"
}

step "Valid pins still resolve and exec"
for v in 0.1.10 v0.1.10; do
    pin "$v"
    run_brace status
    [[ $RC -eq 0 ]] || fail "pin '$v' was refused: $OUT"
    grep -q -- "-Dbrace.home=$HOME_FAKE " "$WORK/java.calls" || fail "pin '$v' did not use the bundled toolchain"
done
pin 0.99.0-SNAPSHOT
run_brace status
[[ $RC -eq 0 ]] || fail "pin '0.99.0-SNAPSHOT' was refused: $OUT"
grep -q -- "-Dbrace.home=$CACHE/0.99.0-SNAPSHOT " "$WORK/java.calls" || fail "SNAPSHOT pin did not use the cached toolchain"
pass "0.1.10, v0.1.10 and 0.99.0-SNAPSHOT resolve"

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
# CLI bug fixed since (0.1.7 hands a .brace URL to `cmd /c start` on Windows). This runs the
# launcher as shipped, whose floor is 0.1.8 or later.
mkdir -p "$CACHE/0.1.7/lib"
pin 0.1.7
run_brace ops dashboard
[[ $RC -ne 0 ]] || fail "pin 0.1.7 below the minimum was accepted"
grep -q "older than 0\.1\.[0-9]*, the oldest this launcher runs" <<<"$OUT" || fail "no clear error for a pin below the minimum: $OUT"
grep -q "BRACE_ALLOW_OLD_TOOLCHAIN" <<<"$OUT" || fail "the error doesn't name the override: $OUT"
[[ ! -e "$WORK/java.calls" ]] || fail "java ran for a refused pin"
ALLOW_OLD=1 run_brace status
[[ $RC -eq 0 ]] || fail "BRACE_ALLOW_OLD_TOOLCHAIN=1 didn't allow the old pin: $OUT"
grep -q -- "-Dbrace.home=$CACHE/0.1.7 " "$WORK/java.calls" || fail "the allowed old pin didn't use its toolchain"
pass "below-minimum pin refused; the override works"

step "A floor of 0.1.11 refuses 0.1.10 and every 0.1.11 prerelease (H2, decision D5)"
# 0.1.8 to 0.1.10 and 0.1.11-rc.1 to rc.5 write the ops token cache through a symlink a cloned
# repo can commit. Run against a launcher copy with the floor set to 0.1.11 and a bundled 0.1.12,
# so no pin below is exempt as the bundled version. Every pin is cached: nothing is fetched.
LAUNCHER="$(launcher_with_floor 0.1.11)"
HOME_NEW="$WORK/home-0.1.12"
mkdir -p "$HOME_NEW/lib"
touch "$HOME_NEW/lib/brace-0.1.12.jar"
BUNDLED="$HOME_NEW"
for v in 0.1.10 0.1.11-rc.1 0.1.11-rc.5 0.1.11-rc.6 0.1.11-SNAPSHOT 0.1.11 0.1.12-rc.1; do
    mkdir -p "$CACHE/$v/lib"
done
for v in 0.1.9 0.1.10 v0.1.10 0.1.11-rc.1 0.1.11-rc.5 0.1.11-rc.6 0.1.11-SNAPSHOT; do
    pin "$v"
    for cmd in status check; do
        run_brace "$cmd"
        [[ $RC -ne 0 ]] || fail "pin '$v' below the 0.1.11 floor was accepted for '$cmd'"
        [[ ! -e "$WORK/java.calls" ]] || fail "java ran for refused pin '$v'"
        grep -q "older than 0.1.11, the oldest this launcher runs" <<<"$OUT" || fail "no floor error for pin '$v': $OUT"
    done
done
pin 0.1.11-rc.1
run_brace status
grep -q "counts as older than the release" <<<"$OUT" || fail "the prerelease refusal doesn't explain itself: $OUT"
pass "0.1.9, 0.1.10, 0.1.11-rc.1/rc.5/rc.6 and 0.1.11-SNAPSHOT refused, nothing exec'd"

step "A floor of 0.1.11 accepts 0.1.11 and later, with the older-than-launcher note"
pin 0.1.11
run_brace status
[[ $RC -eq 0 ]] || fail "pin 0.1.11 (the floor itself) was refused: $OUT"
grep -q -- "-Dbrace.home=$CACHE/0.1.11 " "$WORK/java.calls" || fail "pin 0.1.11 didn't use its cached toolchain"
grep -q "older than this launcher (0.1.12)" <<<"$OUT" || fail "no note for a pin older than the launcher: $OUT"
pin 0.1.12-rc.1
run_brace status
[[ $RC -eq 0 ]] || fail "pin 0.1.12-rc.1 (above the floor) was refused: $OUT"
grep -q -- "-Dbrace.home=$CACHE/0.1.12-rc.1 " "$WORK/java.calls" || fail "pin 0.1.12-rc.1 didn't use its cached toolchain"
grep -q "older than this launcher (0.1.12)" <<<"$OUT" || fail "no note for a prerelease of the launcher's version: $OUT"
pin 0.1.12
run_brace status
[[ $RC -eq 0 ]] || fail "pin 0.1.12 (the bundled version) was refused: $OUT"
grep -q -- "-Dbrace.home=$HOME_NEW " "$WORK/java.calls" || fail "pin 0.1.12 didn't use the bundled toolchain"
grep -q "older than this launcher" <<<"$OUT" && fail "a pin of the launcher's own version got the older note: $OUT"
pass "0.1.11, 0.1.12-rc.1 and the bundled 0.1.12 run"

step "BRACE_ALLOW_OLD_TOOLCHAIN=1 still overrides the 0.1.11 floor (and only the value 1 does)"
for v in 0.1.10 0.1.11-rc.1; do
    pin "$v"
    ALLOW_OLD=1 run_brace status
    [[ $RC -eq 0 ]] || fail "BRACE_ALLOW_OLD_TOOLCHAIN=1 didn't allow pin '$v': $OUT"
    grep -q -- "-Dbrace.home=$CACHE/$v " "$WORK/java.calls" || fail "the allowed pin '$v' didn't use its toolchain"
done
pin 0.1.10
ALLOW_OLD=true run_brace status
[[ $RC -ne 0 ]] || fail "BRACE_ALLOW_OLD_TOOLCHAIN=true was taken as the override"
pass "the override runs 0.1.10 and 0.1.11-rc.1"

step "The bundled toolchain is exempt from the floor; other prereleases of the floor are not"
# A development or rc build of the floor's own version must still run its own projects, and the
# launcher already runs its bundled toolchain for `new` and `version`.
HOME_RC="$WORK/home-rc"
mkdir -p "$HOME_RC/lib" "$CACHE/0.1.11-rc.8/lib"
touch "$HOME_RC/lib/brace-0.1.11-rc.7.jar"
BUNDLED="$HOME_RC"
pin 0.1.11-rc.7
run_brace status
[[ $RC -eq 0 ]] || fail "the bundled 0.1.11-rc.7 was refused under a 0.1.11 floor: $OUT"
grep -q -- "-Dbrace.home=$HOME_RC " "$WORK/java.calls" || fail "pin 0.1.11-rc.7 didn't use the bundled toolchain"
for v in 0.1.11-rc.6 0.1.11-rc.8; do
    pin "$v"
    run_brace status
    [[ $RC -ne 0 ]] || fail "pin '$v' (a prerelease of the floor, not bundled) was accepted"
    [[ ! -e "$WORK/java.calls" ]] || fail "java ran for refused pin '$v'"
done
pass "bundled 0.1.11-rc.7 runs; cached rc.6 and rc.8 are refused"
LAUNCHER="$REPO/bin/brace"
BUNDLED="$HOME_FAKE"

step "Version ordering (version_lt, as the floor and the note use it)"
FNS="$WORK/version-fns.sh"
sed -n -e '/^num_lt() {/,/^}/p' -e '/^prerelease_lt() {/,/^}/p' -e '/^version_lt() {/,/^}/p' \
    "$REPO/bin/brace" > "$FNS"
grep -q '^version_lt() {' "$FNS" || fail "could not extract version_lt from the launcher"
# shellcheck disable=SC1090
source "$FNS"
# Each line: A B and whether A < B.
while read -r a b want; do
    [[ -n "$a" ]] || continue
    if version_lt "$a" "$b"; then got=yes; else got=no; fi
    [[ "$got" == "$want" ]] || fail "version_lt $a $b: got $got, want $want"
done <<'EOF'
0.1.10 0.1.11 yes
0.1.11 0.1.11 no
0.1.11 0.1.10 no
0.1.9 0.1.11 yes
0.1.11-rc.1 0.1.11 yes
0.1.11-rc.6 0.1.11 yes
0.1.11-SNAPSHOT 0.1.11 yes
0.1.11 0.1.11-rc.6 no
0.1.12-rc.1 0.1.11 no
0.1.12-SNAPSHOT 0.1.11 no
0.2.0 0.1.11 no
1.0.0 0.99.99 no
0.1.011 0.1.11 no
0.1.010 0.1.11 yes
0.1.18446744073709551627 0.1.11 no
0.1.11-rc.2 0.1.11-rc.10 yes
0.1.11-rc.10 0.1.11-rc.2 no
0.1.11-rc 0.1.11-rc.1 yes
0.1.11-rc.1 0.1.11-rc no
0.1.11-rc.6 0.1.11-rc.6 no
0.1.11-1 0.1.11-rc yes
0.1.11-rc 0.1.11-1 no
EOF
pass "release > prerelease, numeric parts compared as numbers (no overflow), rc.10 > rc.2"

step "A pom.xml with more than one <brace.version> is refused"
# With two, the launcher would pick a toolchain by one value while Maven (and a reviewer) see the
# other: a commented-out pin, or one in a profile.
POMS="$WORK/poms"
mkdir -p "$POMS"
printf '<project><properties>\n  <!-- <brace.version>0.1.12</brace.version> -->\n  <brace.version>0.1.10</brace.version>\n</properties></project>\n' \
    > "$POMS/comment.xml"
printf '<project><properties><brace.version>0.1.10</brace.version></properties>\n<profiles><profile><properties>\n  <brace.version>0.1.12</brace.version>\n</properties></profile></profiles></project>\n' \
    > "$POMS/profile.xml"
printf '<project><properties>\n  <brace.version\n    >0.1.10</brace.version>\n  <brace.version a="b">0.1.12</brace.version>\n</properties></project>\n' \
    > "$POMS/split.xml"
# Decoys the count must see because the extraction does: the launcher deletes whitespace before it
# reads the pin, so a tag name split by a space or a newline is still a <brace.version> to it, and
# a NUL byte must not hide the rest of the file from the count (macOS awk stops reading at one).
printf '<project><properties>\n  <brace.version>0.1.12</brace.version>\n  <!-- <brace .version>0.1.11</brace.version> -->\n</properties></project>\n' \
    > "$POMS/spacedname.xml"
printf '<project><properties>\n  <brace.version>0.1.12</brace.version>\n  <!-- <brace.ver\nsion>0.1.11</brace.version> -->\n</properties></project>\n' \
    > "$POMS/newlinename.xml"
printf '<project><properties>\n  <brace.version>0.1.12</brace.version>\n  \000<!-- <brace.version>0.1.11</brace.version> -->\n</properties></project>\n' \
    > "$POMS/nul.xml"
if LC_ALL=C tr -d '\000' < "$POMS/nul.xml" | cmp -s - "$POMS/nul.xml"; then
    fail "the NUL fixture has no NUL byte"
fi
for f in comment profile split spacedname newlinename nul; do
    cp "$POMS/$f.xml" "$PROJECT/pom.xml"
    for cmd in status version; do
        run_brace "$cmd"
        [[ $RC -ne 0 ]] || fail "a pom with two <brace.version> elements ($f) was accepted for '$cmd'"
        [[ ! -e "$WORK/java.calls" ]] || fail "java ran for a pom with two pins ($f, '$cmd')"
        grep -q "has 2 <brace.version> elements" <<<"$OUT" || fail "no clear error for two pins ($f): $OUT"
    done
done
# One element, laid out over several lines, and a ${brace.version} reference: still one pin.
printf '<project><properties>\n  <brace.version>\n    0.1.10\n  </brace.version>\n  <brace.versionNote>x</brace.versionNote>\n</properties>\n<version>${brace.version}</version></project>\n' \
    > "$PROJECT/pom.xml"
run_brace status
[[ $RC -eq 0 ]] || fail "a single multi-line pin was refused: $OUT"
grep -q -- "-Dbrace.home=$HOME_FAKE " "$WORK/java.calls" || fail "the single multi-line pin didn't resolve to 0.1.10"
pass "two pins refused for status and version; one pin over several lines still read"

echo ""
echo -e "${GREEN}All pinned-version tests passed.${NC}"
