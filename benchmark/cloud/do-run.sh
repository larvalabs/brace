#!/usr/bin/env bash
set -euo pipefail

# Run benchmark/run-docker.sh on a throwaway DigitalOcean CPU-Optimized (dedicated vCPU) droplet:
# create it, copy this checkout over (working tree as-is, so uncommitted changes are measured
# too), run, copy the results back into benchmark/results/, and destroy the droplet and its
# one-off SSH key. Destruction runs on any exit, including errors and Ctrl-C.
#
# Usage:
#   ./benchmark/cloud/do-run.sh [run-docker.sh args...]     e.g.  ./benchmark/cloud/do-run.sh brace
#   ./benchmark/cloud/do-run.sh --cleanup                   delete any leftover brace-bench droplets and keys
#   ./benchmark/cloud/do-run.sh --list                      show leftover brace-bench droplets
#
# Token: DIGITALOCEAN_TOKEN, or the file ~/.config/brace-bench/digitalocean-token (mode 600).
# A custom-scoped token is enough: droplet (create, read, delete), ssh_key (create, read,
# delete), sizes (read), regions (read), tag (create, read). See benchmark/README.md.
#
# Environment:
#   DO_SIZE=c-8          CPU-Optimized slug: c-4, c-8, c-16, c-32 (c-8: app 4, Postgres 2, wrk 2 CPUs)
#   DO_REGION            default: the first of nyc1, nyc3, sfo3, sfo2, tor1 offering DO_SIZE
#   BENCH_YES=1          skip the cost confirmation prompt
#   Passed through to run-docker.sh: ROUNDS RUNS WRK_CONNECTIONS WRK_DURATION WARMUP_DURATION
#   WRK_THREADS CPUS_APP CPUS_DB CPUS_LOAD APP_MEM DB_MEM DB_POOL JVM_OPTS PG_MAX_CONNECTIONS
#   PG_SHARED_BUFFERS

source "$(cd "$(dirname "$0")/.." && pwd)/lib.sh"

DO_SIZE="${DO_SIZE:-c-8}"
DO_REGION="${DO_REGION:-}"     # default: the first of nyc1, nyc3, sfo3, sfo2, tor1 that offers DO_SIZE
DO_IMAGE="ubuntu-24-04-x64"
TAG="brace-bench"
API="https://api.digitalocean.com/v2"
PASS_VARS=(ROUNDS RUNS WRK_CONNECTIONS WRK_DURATION WARMUP_DURATION WRK_THREADS CPUS_APP CPUS_DB
  CPUS_LOAD APP_MEM DB_MEM DB_POOL JVM_OPTS PG_MAX_CONNECTIONS PG_SHARED_BUFFERS)

WORK=$(mktemp -d "${TMPDIR:-/tmp}/brace-bench.XXXXXX")
chmod 700 "$WORK"
# Holds the token header and the droplet's SSH key; removed on every exit (teardown below
# takes this over once there is a droplet to destroy).
trap 'rm -rf "$WORK"' EXIT

# ---- API -------------------------------------------------------------------------------------

# The token goes into a mode-600 header file, never onto a command line (where ps would show it)
# or into output.
if [ -n "${DIGITALOCEAN_TOKEN:-}" ]; then
  printf 'Authorization: Bearer %s\n' "$DIGITALOCEAN_TOKEN" > "$WORK/auth"
elif [ -r "$HOME/.config/brace-bench/digitalocean-token" ]; then
  printf 'Authorization: Bearer %s\n' "$(tr -d ' \n\r' < "$HOME/.config/brace-bench/digitalocean-token")" > "$WORK/auth"
else
  echo "FATAL: no DigitalOcean token: set DIGITALOCEAN_TOKEN or write ~/.config/brace-bench/digitalocean-token" >&2
  exit 1
fi
chmod 600 "$WORK/auth"

api() { # method path [json-body]
  local args=(-sS -X "$1" -H @"$WORK/auth" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ $# -ge 3 ] && args+=(--data "$3")
  local out code
  out=$(curl "${args[@]}" "$API$2")
  code=${out##*$'\n'}
  out=${out%$'\n'*}
  if [ "${code:0:1}" != 2 ]; then
    echo "FATAL: DigitalOcean API $1 $2 -> HTTP $code: $(echo "$out" | jq -r '.message // .' 2> /dev/null)" >&2
    return 1
  fi
  echo "$out"
}

list_leftovers() {
  api GET "/droplets?tag_name=$TAG&per_page=200" \
    | jq -r '.droplets[] | "droplet \(.id)  \(.name)  \(.size_slug)  \(.region.slug)  created \(.created_at)"'
  api GET "/account/keys?per_page=200" | jq -r --arg t "$TAG-" '.ssh_keys[] | select(.name | startswith($t)) | "ssh key \(.id)  \(.name)"'
}

cleanup_all() {
  local ids
  ids=$(api GET "/droplets?tag_name=$TAG&per_page=200" | jq -r '.droplets[].id')
  for id in $ids; do api DELETE "/droplets/$id" > /dev/null && echo "deleted droplet $id"; done
  ids=$(api GET "/account/keys?per_page=200" | jq -r --arg t "$TAG-" '.ssh_keys[] | select(.name | startswith($t)) | .id')
  for id in $ids; do api DELETE "/account/keys/$id" > /dev/null && echo "deleted ssh key $id"; done
}

case "${1:-}" in
  --list)    list_leftovers; exit 0 ;;
  --cleanup) cleanup_all; exit 0 ;;
esac

# ---- Plan and confirm ------------------------------------------------------------------------

size_json=$(api GET "/sizes?per_page=200" | jq --arg s "$DO_SIZE" '.sizes[] | select(.slug == $s)')
if [ -z "$size_json" ]; then
  echo "FATAL: unknown droplet size $DO_SIZE" >&2; exit 1
fi
if [ -z "$DO_REGION" ]; then
  # Size availability differs per region (c-8 isn't in nyc3, for one).
  for r in nyc1 nyc3 sfo3 sfo2 tor1 $(echo "$size_json" | jq -r '.regions[]'); do
    if echo "$size_json" | jq -e --arg r "$r" '.regions | index($r)' > /dev/null; then DO_REGION=$r; break; fi
  done
fi
if ! echo "$size_json" | jq -e --arg r "$DO_REGION" '.regions | index($r)' > /dev/null; then
  echo "FATAL: size $DO_SIZE isn't offered in $DO_REGION; it is in: $(echo "$size_json" | jq -r '.regions | join(" ")')" >&2
  exit 1
fi
price=$(echo "$size_json" | jq -r '.price_hourly')
desc=$(echo "$size_json" | jq -r '"\(.vcpus) vCPU, \(.memory / 1024) GB, \(.description)"')

leftovers=$(list_leftovers)
if [ -n "$leftovers" ]; then
  echo "WARNING: leftover brace-bench resources on the account (delete with --cleanup):"
  echo "$leftovers"
fi

echo "Plan: one $DO_SIZE droplet ($desc) in $DO_REGION at \$$price/hour, destroyed when the run ends."
echo "      run-docker.sh ${*:-brace spring-jpa}"
if [ "${BENCH_YES:-0}" != 1 ]; then
  read -r -p "Create it? [y/N] " answer || answer=""
  [ "$answer" = y ] || [ "$answer" = Y ] || { echo "Cancelled."; exit 1; }
fi

# ---- Create ----------------------------------------------------------------------------------

STAMP=$(date +%Y%m%d-%H%M%S)
NAME="$TAG-$STAMP"
DROPLET_ID=""
KEY_ID=""
START_TS=$(date +%s)

teardown() {
  local rc=$?
  set +e
  if [ -n "$DROPLET_ID" ]; then
    api DELETE "/droplets/$DROPLET_ID" > /dev/null && echo "Destroyed droplet $DROPLET_ID ($NAME)."
  fi
  if [ -n "$KEY_ID" ]; then
    api DELETE "/account/keys/$KEY_ID" > /dev/null && echo "Deleted SSH key $KEY_ID."
  fi
  if [ -n "$DROPLET_ID" ]; then
    local mins=$(( ( $(date +%s) - START_TS + 59 ) / 60 ))
    echo "Droplet lifetime about $mins min (about \$$(awk -v p="$price" -v m="$mins" 'BEGIN { printf "%.2f", p * m / 60 }'))."
  fi
  rm -rf "$WORK"
  exit "$rc"
}
trap teardown EXIT
trap 'exit 130' INT TERM

# A one-off key pair for this droplet only.
ssh-keygen -q -t ed25519 -N '' -C "$NAME" -f "$WORK/id"
KEY_ID=$(api POST /account/keys "$(jq -n --arg n "$NAME" --arg k "$(cat "$WORK/id.pub")" '{name: $n, public_key: $k}')" | jq -r '.ssh_key.id')

# Docker plus BuildKit (the Dockerfiles use cache mounts), and no background apt runs
# competing with the benchmark.
cat > "$WORK/user-data" <<'EOF'
#cloud-config
package_update: true
packages: [docker.io, docker-compose-v2, docker-buildx, rsync, git, curl]
runcmd:
  - systemctl disable --now unattended-upgrades.service apt-daily.timer apt-daily-upgrade.timer
  - systemctl enable --now docker
EOF

echo "Creating droplet $NAME..."
body=$(jq -n --arg n "$NAME" --arg r "$DO_REGION" --arg s "$DO_SIZE" --arg i "$DO_IMAGE" \
  --argjson k "$KEY_ID" --arg t "$TAG" --rawfile u "$WORK/user-data" \
  '{name: $n, region: $r, size: $s, image: $i, ssh_keys: [$k], tags: [$t], user_data: $u, ipv6: false, monitoring: false}')
DROPLET_ID=$(api POST /droplets "$body" | jq -r '.droplet.id')

IP=""
for _ in $(seq 1 60); do
  d=$(api GET "/droplets/$DROPLET_ID")
  if [ "$(echo "$d" | jq -r '.droplet.status')" = active ]; then
    IP=$(echo "$d" | jq -r '.droplet.networks.v4[] | select(.type == "public") | .ip_address' | head -1)
    [ -n "$IP" ] && break
  fi
  sleep 5
done
[ -n "$IP" ] || { echo "FATAL: droplet didn't become active within 5 minutes" >&2; exit 1; }
echo "Droplet $DROPLET_ID is up at $IP."

SSH=(ssh -i "$WORK/id" -o BatchMode=yes -o StrictHostKeyChecking=accept-new
  -o UserKnownHostsFile="$WORK/known_hosts" -o ConnectTimeout=10 -o ServerAliveInterval=30
  -o ServerAliveCountMax=6 "root@$IP")
for _ in $(seq 1 60); do "${SSH[@]}" true 2> /dev/null && break; sleep 5; done
"${SSH[@]}" true || { echo "FATAL: no SSH to $IP" >&2; exit 1; }

echo "Waiting for cloud-init (Docker install)..."
status=$("${SSH[@]}" 'cloud-init status --wait > /dev/null 2>&1; cloud-init status' || true)
case "$status" in
  *done*) ;;
  *) echo "FATAL: cloud-init: $status" >&2; "${SSH[@]}" 'tail -30 /var/log/cloud-init-output.log' >&2 || true; exit 1 ;;
esac

# ---- Run -------------------------------------------------------------------------------------

echo "Copying the working tree..."
rsync -az --delete -e "ssh -i $WORK/id -o BatchMode=yes -o UserKnownHostsFile=$WORK/known_hosts" \
  --exclude .git --exclude target/ --exclude .claude/ --exclude jte-classes/ \
  --exclude benchmark/results/ --exclude node_modules/ \
  "$REPO_ROOT/" "root@$IP:/root/brace/"

remote_env="BRACE_COMMIT=$(printf %q "$(commit_desc)") BENCH_HOST_LABEL=$(printf %q "digitalocean $DO_SIZE $DO_REGION")"
for v in "${PASS_VARS[@]}"; do
  if [ -n "${!v:-}" ]; then remote_env+=" $v=$(printf %q "${!v}")"; fi
done
remote_args=""
for a in "$@"; do remote_args+=" $(printf %q "$a")"; done

echo "Running: $remote_env ./benchmark/run-docker.sh$remote_args"
rc=0
"${SSH[@]}" "cd /root/brace && env $remote_env ./benchmark/run-docker.sh$remote_args" || rc=$?

echo "Copying results back..."
mkdir -p "$BENCH_DIR/results"
rsync -az -e "ssh -i $WORK/id -o BatchMode=yes -o UserKnownHostsFile=$WORK/known_hosts" \
  "root@$IP:/root/brace/benchmark/results/" "$BENCH_DIR/results/" || echo "WARNING: copying results failed"
echo "Results in $BENCH_DIR/results/"
exit "$rc"
