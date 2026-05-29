#!/usr/bin/env bash
# End-to-end test for the installer + brace self-update.
# Builds the dist, stages a fake release tree served over file://, then exercises
# install.sh and `brace self-update` against it (no network, no real releases).

set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
step() { echo -e "${YELLOW}▸${NC} $*"; }
pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*" >&2; exit 1; }

step "Building distribution"
cd "$REPO"
mvn package -DskipTests -q
DIST="$REPO/target/brace-current"
[[ -d "$DIST/lib" ]] || fail "target/brace-current not built"
pass "Built dist"

step "Staging fake release tree (0.2.0, 0.3.0) over file://"
REL="$WORK/releases"
STAGE="$WORK/stage"
mkdir -p "$STAGE"
cp -RL "$DIST" "$STAGE/base"
for V in 0.2.0 0.3.0; do
    mkdir -p "$REL/v$V"
    rm -rf "$STAGE/brace-$V"
    cp -R "$STAGE/base" "$STAGE/brace-$V"
    ( cd "$STAGE" && zip -qr "$REL/v$V/brace-$V.zip" "brace-$V" )
done
printf '{"tag_name":"v0.3.0"}\n' > "$REL/latest.json"
pass "Staged releases + latest.json"

INSTALL="$WORK/home/.brace"
LINK="$INSTALL/bin/brace"

step "install.sh resolves latest (0.3.0) and links the launcher"
BRACE_DIR="$INSTALL" \
BRACE_RELEASE_BASE="file://$REL" \
BRACE_LATEST_URL="file://$REL/latest.json" \
    sh "$REPO/install.sh" > "$WORK/install.out" 2>&1 || { cat "$WORK/install.out"; fail "install.sh failed"; }
[[ -L "$LINK" ]] || fail "launcher symlink not created"
[[ -d "$INSTALL/toolchains/0.3.0/lib" ]] || fail "0.3.0 toolchain not installed"
[[ "$(readlink "$LINK")" == */toolchains/0.3.0/bin/brace ]] || fail "launcher not linked to 0.3.0"
[[ -x "$LINK" ]] || fail "linked launcher is not executable"
"$LINK" version > /dev/null 2>&1 || fail "installed launcher does not run"
pass "installed + linked + runnable"

step "install.sh does not modify the shell rc by default"
FAKE="$WORK/fakehome"
mkdir -p "$FAKE"
echo "# original" > "$FAKE/.zshrc"
env -i HOME="$FAKE" SHELL=/bin/zsh PATH="/usr/bin:/bin" \
    BRACE_DIR="$FAKE/.brace" BRACE_VERSION=0.2.0 BRACE_RELEASE_BASE="file://$REL" \
    sh "$REPO/install.sh" > /dev/null 2>&1 || fail "install (fakehome) failed"
[[ "$(cat "$FAKE/.zshrc")" == "# original" ]] || fail "install.sh modified .zshrc without BRACE_MODIFY_PATH"
pass "rc untouched by default"

step "self-update to an explicit version (0.2.0) relinks the launcher"
BRACE_RELEASE_BASE="file://$REL" "$LINK" self-update 0.2.0 > "$WORK/su1.out" 2>&1 || { cat "$WORK/su1.out"; fail "self-update 0.2.0 failed"; }
[[ "$(readlink "$LINK")" == */toolchains/0.2.0/bin/brace ]] || fail "launcher not relinked to 0.2.0"
"$LINK" version > /dev/null 2>&1 || fail "launcher not runnable after switch"
pass "switched to 0.2.0"

step "self-update reuses a cached toolchain (no re-download)"
# 0.3.0 was installed above, so switching back must not download again.
BRACE_RELEASE_BASE="file://$REL" "$LINK" self-update 0.3.0 > "$WORK/su2.out" 2>&1 || { cat "$WORK/su2.out"; fail "self-update 0.3.0 failed"; }
grep -qi "download" "$WORK/su2.out" && fail "self-update re-downloaded a cached toolchain"
[[ "$(readlink "$LINK")" == */toolchains/0.3.0/bin/brace ]] || fail "launcher not relinked to 0.3.0"
pass "reused cached 0.3.0"

step "self-update outside an install layout fails cleanly"
LIB="$INSTALL/toolchains/0.3.0/lib"
if java -Dbrace.home=/tmp/not-a-toolchain -cp "$LIB/*" com.larvalabs.brace.Cli self-update > "$WORK/su3.out" 2>&1; then
    fail "self-update should fail when not installed"
fi
grep -q "only works for an installed brace" "$WORK/su3.out" || { cat "$WORK/su3.out"; fail "wrong error for non-install"; }
pass "clean error when not installed"

echo ""
echo -e "${GREEN}All self-update tests passed.${NC}"
