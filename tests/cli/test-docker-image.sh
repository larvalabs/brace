#!/usr/bin/env bash
# M13: builds a freshly scaffolded app's image with its own Dockerfile and runs it with its own
# compose.yaml (Postgres alongside, read-only root filesystem, no capabilities, non-root), then
# checks the container's health check passes and the app serves its home page.
#
# Needs Docker with Compose. Without a daemon it skips (exit 0), unless BRACE_REQUIRE_DOCKER=1,
# which makes a missing daemon a failure so CI can't skip it silently. Behind a TLS-intercepting
# proxy, set BRACE_DOCKER_BUILD_CA to the proxy's CA certificate (PEM) and the build stage trusts it.

set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
WORK=$(mktemp -d)
PROJECT="bracesmoke$$"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

step() { echo -e "${YELLOW}▸${NC} $*"; }
pass() { echo -e "${GREEN}✓${NC} $*"; }
fail() { echo -e "${RED}✗${NC} $*" >&2; exit 1; }

if ! docker info >/dev/null 2>&1; then
    [[ "${BRACE_REQUIRE_DOCKER:-}" == 1 ]] && fail "Docker is not available (BRACE_REQUIRE_DOCKER=1)"
    echo "Docker is not available; skipping the image test."
    exit 0
fi

compose() {
    (cd "$WORK/smokeapp" && docker compose -p "$PROJECT" -f compose.yaml -f compose.smoke.yaml "$@")
}

cleanup() {
    if [[ -f "$WORK/smokeapp/compose.smoke.yaml" ]]; then
        compose down -v --rmi local >/dev/null 2>&1 || true
    fi
    rm -rf "$WORK"
}
trap cleanup EXIT

step "Building distribution"
cd "$REPO"
mvn package -DskipTests -q
ZIP=$(ls -t target/brace-*.zip | head -1)
VERSION=$(basename "$ZIP" .zip)
VERSION=${VERSION#brace-}
JAR="target/brace-$VERSION.jar"
[[ -f "$JAR" ]] || fail "$JAR not built"
unzip -q "$ZIP" -d "$WORK"
BRACE_BIN=$(ls "$WORK"/brace-*/bin/brace | head -1)
pass "Built brace $VERSION"

step "Scaffolding smokeapp"
cd "$WORK"
"$BRACE_BIN" new smokeapp > "$WORK/new.out" 2>&1 || { cat "$WORK/new.out"; fail "brace new failed"; }
for f in Dockerfile .dockerignore compose.yaml; do
    [[ -f "smokeapp/$f" ]] || fail "brace new didn't write $f"
done
pass "brace new wrote Dockerfile, .dockerignore and compose.yaml"

# The scaffold resolves Brace from JitPack, which doesn't have this unreleased build. Install it
# into a project-local Maven repository under the coordinates the scaffold asks for, resolve
# everything else into the same repository on the host, and hand that repository to the image's
# build stage. The build stage itself is unchanged apart from that one COPY.
step "Seeding a local Maven repository with this build of Brace"
cd "$WORK/smokeapp"
M2="$WORK/smokeapp/.m2repo"
perl -0pe 's#<groupId>com\.larvalabs</groupId>#<groupId>com.github.larvalabs</groupId>#; s#<version>\Q'"$VERSION"'\E</version>#<version>v'"$VERSION"'</version>#' \
    "$REPO/pom.xml" > "$WORK/brace-jitpack.pom"
mvn -B -q install:install-file -Dfile="$REPO/$JAR" -DpomFile="$WORK/brace-jitpack.pom" \
    -Dmaven.repo.local="$M2" > "$WORK/install.out" 2>&1 || { cat "$WORK/install.out"; fail "install-file failed"; }
mvn -B -q -Dmaven.repo.local="$M2" dependency:go-offline package -DskipTests > "$WORK/warm.out" 2>&1 \
    || { cat "$WORK/warm.out"; fail "resolving the scaffold's dependencies failed"; }
rm -rf target
pass "Dependencies resolved"

step "Building the image"
# The scaffold's Dockerfile with the seeded repository copied in ahead of its first mvn step.
EXTRA=""
if [[ -n "${BRACE_DOCKER_BUILD_CA:-}" ]]; then
    cp "$BRACE_DOCKER_BUILD_CA" smoke-ca.crt
    EXTRA='COPY smoke-ca.crt /tmp/smoke-ca.crt\nRUN keytool -importcert -cacerts -noprompt -storepass changeit -alias smoke-ca -file /tmp/smoke-ca.crt'
fi
awk -v extra="$EXTRA" '{ print } /^WORKDIR \/src$/ { print "COPY .m2repo/ /root/.m2/repository/"; if (extra != "") print extra }' \
    Dockerfile > Dockerfile.smoke
grep -q "^COPY .m2repo/" Dockerfile.smoke || fail "couldn't find the build stage's WORKDIR in the Dockerfile"
# Publish on a free host port rather than the scaffold's fixed 8080.
cat > compose.smoke.yaml <<'EOF'
services:
  app:
    build:
      context: .
      dockerfile: Dockerfile.smoke
    ports: !override
      - "127.0.0.1::8080"
EOF
export SESSION_SECRET="smoke-test-session-secret-$(date +%s)-0123456789abcdef"
# Base64 characters on purpose: the scaffold says to generate it with openssl rand -base64.
export POSTGRES_PASSWORD="smoke/test+pass=word"
export OPS_ORIGINS="http://localhost:8080"
compose build > "$WORK/build.out" 2>&1 || { tail -50 "$WORK/build.out"; fail "docker build failed"; }
pass "Image built"

step "Starting the stack and waiting for the health check"
if ! compose up -d --wait --wait-timeout 180 > "$WORK/up.out" 2>&1; then
    cat "$WORK/up.out"
    compose logs app | tail -50
    fail "the stack didn't become healthy"
fi
APP=$(compose ps -q app)
[[ "$(docker inspect -f '{{.State.Health.Status}}' "$APP")" == healthy ]] || fail "app container isn't healthy"
pass "Container is healthy (HEALTHCHECK polls /__brace/health)"

step "Checking the container's lockdown"
[[ "$(docker inspect -f '{{.Config.User}}' "$APP")" == "65532:65532" ]] || fail "image doesn't run as uid 65532"
[[ "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$APP")" == true ]] || fail "root filesystem isn't read-only"
docker inspect -f '{{.HostConfig.CapDrop}}' "$APP" | grep -q ALL || fail "capabilities aren't dropped"
docker inspect -f '{{.HostConfig.SecurityOpt}}' "$APP" | grep -q "no-new-privileges" || fail "no-new-privileges isn't set"
JAVA_UID=$(docker top "$APP" -o pid,uid,args | awk '/-jar app.jar/ { print $2; exit }')
[[ "$JAVA_UID" == 65532 ]] || {
    docker top "$APP" -o pid,uid,args
    fail "java isn't running as uid 65532 (got '$JAVA_UID')"
}
DB=$(compose ps -q db)
[[ -z "$(docker port "$DB")" ]] || fail "Postgres publishes a port: $(docker port "$DB")"
pass "Non-root, read-only, no capabilities, no-new-privileges; Postgres unpublished"

step "Requesting the app"
PORT=$(compose port app 8080 | sed 's/.*://')
BASE="http://127.0.0.1:$PORT"
curl -fsS "$BASE/__brace/health" | grep -qx "ok" || fail "/__brace/health didn't answer ok"
curl -fsS -D "$WORK/home.headers" -o "$WORK/home.html" "$BASE/" || fail "GET / failed"
grep -q "Welcome" "$WORK/home.html" || fail "home page didn't render: $(head -c 300 "$WORK/home.html")"
grep -qi "^Content-Security-Policy: .*script-src 'self'" "$WORK/home.headers" \
    || fail "home page lacks the strict CSP: $(cat "$WORK/home.headers")"
grep -qi "^X-Content-Type-Options: nosniff" "$WORK/home.headers" || fail "home page lacks nosniff"
curl -fsS -o /dev/null "$BASE/public/css/style.css" || fail "the layout's stylesheet isn't served"
pass "Home page renders from the image with the strict headers"

echo
pass "Docker image test passed"
