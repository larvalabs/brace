#!/usr/bin/env bash
# End-to-end test for the brace CLI distribution.
# Builds the zip, unzips it to a temp dir, runs brace new + compile + test.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

step() { echo -e "${YELLOW}▸${NC} $*"; }
pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*" >&2; exit 1; }

step "Building distribution"
cd "$REPO"
mvn package -DskipTests -q
ZIP=$(ls target/brace-*.zip | head -1)
[[ -f "$ZIP" ]] || fail "Distribution zip not built"
pass "Built $(basename "$ZIP")"

step "Unzipping distribution to $WORK"
unzip -q "$ZIP" -d "$WORK"
BRACE_BIN=$(ls "$WORK"/brace-*/bin/brace | head -1)
[[ -x "$BRACE_BIN" ]] || fail "bin/brace not executable"
pass "Launcher found at $BRACE_BIN"

step "Running brace help"
"$BRACE_BIN" help > "$WORK/help.out"
grep -q "Global commands" "$WORK/help.out" || fail "help output missing 'Global commands'"
grep -q "brace new" "$WORK/help.out" || fail "help output missing 'brace new'"
pass "help prints usage"

step "Running brace new testapp"
cd "$WORK"
"$BRACE_BIN" new testapp > "$WORK/new.out" 2>&1 || fail "brace new failed"
[[ -d testapp ]] || fail "testapp directory not created"
[[ -f testapp/pom.xml ]] || fail "testapp/pom.xml not created"
[[ -f testapp/src/main/java/app/App.java ]] || fail "App.java not created"
[[ -f testapp/src/test/java/app/HomeControllerTest.java ]] || fail "test class not created"
pass "brace new created project"

step "Running brace compile"
cd "$WORK/testapp"
"$BRACE_BIN" compile > "$WORK/compile.out" 2>&1 || {
    cat "$WORK/compile.out"
    fail "brace compile failed"
}
[[ -f target/classes/app/App.class ]] || fail "App.class not produced"
pass "brace compile succeeded"

step "Running brace test"
"$BRACE_BIN" test > "$WORK/test.out" 2>&1 || {
    cat "$WORK/test.out"
    fail "brace test failed"
}
grep -qE "Test run finished|Successful tests|\[ +1 tests successful +\]" "$WORK/test.out" || {
    cat "$WORK/test.out"
    fail "test output doesn't look right"
}
pass "brace test ran"

step "Running brace ops keypair"
rm -f ops-authorized-keys  # clear the one from brace new
"$BRACE_BIN" ops keypair > "$WORK/keypair.out" 2>&1 || fail "brace ops keypair failed"
grep -q "Public key:" "$WORK/keypair.out" || fail "keypair output missing 'Public key:'"
grep -q "Private key:" "$WORK/keypair.out" || fail "keypair output missing 'Private key:'"
[[ -f ops-authorized-keys ]] || fail "ops-authorized-keys not created"
pass "brace ops keypair generated and wrote file"

echo ""
echo -e "${GREEN}All distribution tests passed.${NC}"
