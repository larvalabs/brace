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
# Pick the freshly built zip (newest mtime), not the first alphabetically —
# stale zips from earlier-versioned builds linger in target/ and would otherwise
# be tested instead of the current one.
ZIP=$(ls -t target/brace-*.zip | head -1)
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
grep -q "public static void routes(Brace app)" testapp/src/main/java/app/App.java || fail "App.java missing reusable routes(Brace) method"
grep -q "App::routes" testapp/src/test/java/app/HomeControllerTest.java || fail "generated test doesn't reuse App::routes"
grep -q "maven-surefire-plugin" testapp/pom.xml || fail "pom.xml missing surefire pin (mvn test would run zero tests)"
grep -q "maven-shade-plugin" testapp/pom.xml || fail "pom.xml missing shade plugin (Dockerfile jar would not run)"
grep -q "COPY target/app.jar app.jar" testapp/Dockerfile || fail "Dockerfile doesn't copy the shaded target/app.jar"
pass "brace new created project"

step "Running brace compile"
cd "$WORK/testapp"
"$BRACE_BIN" compile > "$WORK/compile.out" 2>&1 || {
    cat "$WORK/compile.out"
    fail "brace compile failed"
}
[[ -f target/classes/app/App.class ]] || fail "App.class not produced"
pass "brace compile succeeded"

step "Running brace agents-md"
[[ -f BRACE-AGENTS.md ]] || fail "brace new did not write BRACE-AGENTS.md"
echo "stale copy" > BRACE-AGENTS.md
"$BRACE_BIN" agents-md > "$WORK/agentsmd.out" 2>&1 || {
    cat "$WORK/agentsmd.out"
    fail "brace agents-md failed"
}
grep -q "Brace Framework Reference" BRACE-AGENTS.md || fail "agents-md did not rewrite BRACE-AGENTS.md from the jar"
grep -q "stale copy" BRACE-AGENTS.md && fail "agents-md left the stale copy in place"
"$BRACE_BIN" agents-md --stdout 2>/dev/null | grep -q "Brace Framework Reference" || fail "agents-md --stdout missing doc content"
pass "brace agents-md refreshed BRACE-AGENTS.md"

step "Running brace version (project-aware)"
# Inside a project, version reports both the launcher and the project's pin.
"$BRACE_BIN" version > "$WORK/version.out" 2>&1 || fail "brace version failed"
grep -q "(launcher)" "$WORK/version.out" || { cat "$WORK/version.out"; fail "version missing launcher line"; }
grep -q "(project, from pom.xml)" "$WORK/version.out" || { cat "$WORK/version.out"; fail "version missing project pin line"; }
# Outside a project it is just the bare launcher version (no labels).
( cd "$WORK" && "$BRACE_BIN" version ) > "$WORK/version-global.out" 2>&1 || fail "global brace version failed"
grep -q "(project" "$WORK/version-global.out" && fail "global version should not show a project pin"
pass "brace version reports launcher + project pin"

step "Running brace test"
# stdout is piped here (not a TTY), so brace test runs in concise mode:
# no JUnit tree, just a one-line summary.
"$BRACE_BIN" test > "$WORK/test.out" 2>&1 || {
    cat "$WORK/test.out"
    fail "brace test failed"
}
grep -qE "^[0-9]+ passed, 0 failed in" "$WORK/test.out" || {
    cat "$WORK/test.out"
    fail "concise test output missing 'N passed, 0 failed in X.Xs' summary line"
}
grep -q "Test run finished" "$WORK/test.out" && {
    cat "$WORK/test.out"
    fail "concise mode should not pass through the raw ConsoleLauncher summary"
}
pass "brace test ran (concise summary)"

step "Running brace test --verbose (full passthrough)"
"$BRACE_BIN" test --verbose > "$WORK/test-verbose.out" 2>&1 || {
    cat "$WORK/test-verbose.out"
    fail "brace test --verbose failed"
}
grep -qE "Test run finished|\[ +[0-9]+ tests successful +\]" "$WORK/test-verbose.out" || {
    cat "$WORK/test-verbose.out"
    fail "--verbose output missing raw ConsoleLauncher summary"
}
pass "brace test --verbose passes raw output through"

step "Running brace test with a failing test (concise failure line)"
cat > src/test/java/app/AlwaysFailsTest.java <<'EOF'
package app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AlwaysFailsTest {
    @Test
    public void alwaysFails() {
        assertEquals(1, 2);
    }
}
EOF
set +e
"$BRACE_BIN" test > "$WORK/test-fail.out" 2>&1
TEST_RC=$?
set -e
rm src/test/java/app/AlwaysFailsTest.java
[[ $TEST_RC -ne 0 ]] || { cat "$WORK/test-fail.out"; fail "brace test should exit nonzero on a failing test"; }
grep -q "AlwaysFailsTest.alwaysFails() — AssertionFailedError" "$WORK/test-fail.out" || {
    cat "$WORK/test-fail.out"
    fail "missing one-line failure for AlwaysFailsTest.alwaysFails()"
}
grep -q "(AlwaysFailsTest.java:" "$WORK/test-fail.out" || {
    cat "$WORK/test-fail.out"
    fail "failure line missing project-frame location (AlwaysFailsTest.java:NN)"
}
grep -qE "^[0-9]+ passed, 1 failed in" "$WORK/test-fail.out" || {
    cat "$WORK/test-fail.out"
    fail "missing 'N passed, 1 failed in X.Xs' summary line"
}
pass "failing test produces one-line failure + summary, exit code preserved"

step "Running brace ops keypair"
rm -f ops-authorized-keys ops-private.key  # clear the ones brace new generated
"$BRACE_BIN" ops keypair > "$WORK/keypair.out" 2>&1 || fail "brace ops keypair failed"
grep -q "Public key:" "$WORK/keypair.out" || fail "keypair output missing 'Public key:'"
grep -q "Wrote ops-private.key" "$WORK/keypair.out" || fail "keypair output missing 'Wrote ops-private.key'"
[[ -f ops-authorized-keys ]] || fail "ops-authorized-keys not created"
[[ -f ops-private.key ]] || fail "ops-private.key not created"
pass "brace ops keypair generated and wrote keys"

echo ""
echo -e "${GREEN}All distribution tests passed.${NC}"
