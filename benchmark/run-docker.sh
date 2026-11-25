#!/usr/bin/env bash
set -euo pipefail

# Containerized TechEmpower-style benchmark, meant for a dedicated Linux box (e.g. a
# dedicated-vCPU cloud VM) so nothing else competes for the CPUs. Postgres, the app and wrk each
# run in their own container, pinned to their own CPUs (docker/compose.yml). The images come from
# this checkout (Brace) and TechEmpower's archived repo at a pinned commit plus a Spring Data JPA
# profile (Spring).
#
# Usage: ./benchmark/run-docker.sh [framework...]     framework: brace | spring-jpa (default: both)
#
# Every invocation starts from a fresh, freshly seeded Postgres. For ROUNDS rounds it launches each
# framework in turn (a new JVM each time), checks its responses, warms up, runs RUNS passes over the
# six tests, and stops it. Alternating frameworks round by round spreads any drift on the machine
# across both. The summary reports medians per framework and the req/s ratio between them.
#
# Environment (all optional):
#   ROUNDS=3 RUNS=1                 launches per framework, measured passes per launch
#   WRK_CONNECTIONS=256 WRK_DURATION=15s WARMUP_DURATION=5s
#   WRK_THREADS                     default: one per load-generator CPU
#   CPUS_APP CPUS_DB CPUS_LOAD      cpusets, e.g. "0-7" "8-11" "12-15"; default: half the
#                                   Docker host's CPUs to the app, a quarter each to Postgres and wrk
#   APP_MEM DB_MEM                  container memory limits, e.g. 512m (default: none)
#   DB_POOL=256 JVM_OPTS            pool size (both apps), extra JVM flags (both apps)
#   PG_MAX_CONNECTIONS=600 PG_SHARED_BUFFERS=256MB
#   PORT=8080                       host port for the driver's checks (bound to 127.0.0.1)
#   BENCH_HOST_LABEL                free text for the report, e.g. "hetzner ccx33 fsn1"
#   SKIP_BUILD=1                    reuse the images already built
#   KEEP=1                          leave Postgres running afterwards

source "$(cd "$(dirname "$0")" && pwd)/lib.sh"

: "${ROUNDS:=3}"
: "${RUNS:=1}"
USER_WRK_THREADS=${WRK_THREADS:-}   # tfb_init would default it to 8; here it follows CPUS_LOAD
tfb_init

FRAMEWORKS=("$@")
[ ${#FRAMEWORKS[@]} -gt 0 ] || FRAMEWORKS=(brace spring-jpa)
for fw in "${FRAMEWORKS[@]}"; do
  case "$fw" in brace|spring-jpa) ;; *) echo "FATAL: unknown framework '$fw' (brace, spring-jpa)" >&2; exit 1 ;; esac
done

COMPOSE=(docker compose -f "$BENCH_DIR/docker/compose.yml")
ALL_PROFILES=(--profile brace --profile spring-jpa --profile tools)

# ---- CPU layout ------------------------------------------------------------------------------

cpu_range() { if [ "$1" = "$2" ]; then echo "$1"; else echo "$1-$2"; fi; }

# Number of CPUs in a cpuset string like "0-3,6".
cpuset_count() {
  echo "$1" | tr ',' '\n' | awk -F- '{ n += ($2 == "" ? 1 : $2 - $1 + 1) } END { print n }'
}

NCPU=$(docker info --format '{{.NCPU}}')
if [ -z "${CPUS_APP:-}${CPUS_DB:-}${CPUS_LOAD:-}" ]; then
  if [ "$NCPU" -lt 3 ]; then
    echo "FATAL: the Docker host has $NCPU CPUs; need at least 3 (one each for app, Postgres, wrk)" >&2
    exit 1
  fi
  n_load=$(( NCPU / 4 )); [ "$n_load" -ge 1 ] || n_load=1
  n_db=$(( NCPU / 4 ));   [ "$n_db" -ge 1 ]   || n_db=1
  n_app=$(( NCPU - n_load - n_db ))
  CPUS_APP=$(cpu_range 0 $(( n_app - 1 )))
  CPUS_DB=$(cpu_range "$n_app" $(( n_app + n_db - 1 )))
  CPUS_LOAD=$(cpu_range $(( n_app + n_db )) $(( NCPU - 1 )))
elif [ -z "${CPUS_APP:-}" ] || [ -z "${CPUS_DB:-}" ] || [ -z "${CPUS_LOAD:-}" ]; then
  echo "FATAL: set all of CPUS_APP, CPUS_DB and CPUS_LOAD, or none" >&2
  exit 1
fi
WRK_THREADS=${USER_WRK_THREADS:-$(cpuset_count "$CPUS_LOAD")}
: "${APP_MEM:=0}"
: "${DB_MEM:=0}"
: "${BRACE_LOG_LEVEL:=WARN}"
BRACE_COMMIT=$(commit_desc)
export CPUS_APP CPUS_DB CPUS_LOAD APP_MEM DB_MEM DB_POOL JVM_OPTS PORT \
  BRACE_LOG_LEVEL BRACE_COMMIT PG_MAX_CONNECTIONS PG_SHARED_BUFFERS

# ---- Hooks for lib.sh: wrk and memory readings go through the containers ----------------------

load_url() { echo "http://app:8080/$1"; }
run_wrk() { "${COMPOSE[@]}" run --rm --no-deps -T wrk "$@" 2> /dev/null; }
# The app's java process is PID 1 in its container. VmHWM is its peak RSS; writing 5 to
# clear_refs resets that peak, so each measurement reports its own.
proc_status_mb() { docker exec "$CID" sh -c "grep $1 /proc/1/status" | awk '{printf "%d", $2 / 1024}'; }
mem_now_mb() { proc_status_mb VmRSS; }
mem_mark() { docker exec "$CID" sh -c 'echo 5 > /proc/1/clear_refs' 2> /dev/null || true; }
mem_peak_mb() { proc_status_mb VmHWM; }

container_alive() { [ "$(docker inspect -f '{{.State.Running}}' "$CID" 2> /dev/null)" = true ]; }

# ---- Setup -----------------------------------------------------------------------------------

if port_in_use "$PORT"; then
  echo "FATAL: something is already listening on port $PORT; set PORT or stop it" >&2
  exit 1
fi

cleanup() {
  [ "${KEEP:-0}" = 1 ] && return 0
  "${COMPOSE[@]}" "${ALL_PROFILES[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
}
trap cleanup EXIT

if [ "${SKIP_BUILD:-0}" != 1 ]; then
  echo "Building images (Brace from this checkout at $BRACE_COMMIT)..."
  build_profiles=(--profile tools)
  for fw in "${FRAMEWORKS[@]}"; do build_profiles+=(--profile "$fw"); done
  "${COMPOSE[@]}" "${build_profiles[@]}" build --quiet
fi

echo "Starting a fresh Postgres..."
"${COMPOSE[@]}" "${ALL_PROFILES[@]}" down -v --remove-orphans > /dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --wait postgres > /dev/null 2>&1 \
  || { echo "FATAL: Postgres did not become healthy" >&2; "${COMPOSE[@]}" logs postgres | tail -20 >&2; exit 1; }

tfb_record_output "docker-$(IFS=-; echo "${FRAMEWORKS[*]}")"

image_id() { docker image inspect -f '{{.Id}}' "$1" 2> /dev/null | cut -c8-19; }
in_image() { docker run --rm --entrypoint "$2" "$1" "${@:3}" 2>&1 | head -1; }

echo "=== Containerized TFB benchmark: ${FRAMEWORKS[*]} ==="
echo "date:        $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "host:        ${BENCH_HOST_LABEL:-(set BENCH_HOST_LABEL)} / $(machine_desc) / $(uname -sr)"
echo "docker:      $(docker version --format '{{.Server.Version}}'), $(docker info --format '{{.OperatingSystem}}, kernel {{.KernelVersion}}, {{.NCPU}} cpus, cgroup v{{.CgroupVersion}}')"
echo "cpusets:     app $CPUS_APP, postgres $CPUS_DB, wrk $CPUS_LOAD"
echo "memory caps: app ${APP_MEM/#0/none}, postgres ${DB_MEM/#0/none}"
echo "postgres:    $(docker image inspect -f '{{index .RepoDigests 0}}' postgres:16 2> /dev/null), max_connections ${PG_MAX_CONNECTIONS:-600}, shared_buffers ${PG_SHARED_BUFFERS:-256MB}"
echo "pool:        $DB_POOL connections per app"
echo "jvm opts:    ${JVM_OPTS:-(none)}"
for fw in "${FRAMEWORKS[@]}"; do
  case "$fw" in
    brace)  echo "brace:       image $(image_id brace-bench/brace:local), brace $(in_image brace-bench/brace:local cat /app/brace-version), commit $(in_image brace-bench/brace:local cat /app/brace-commit), $(in_image brace-bench/brace:local java -version), BRACE_LOG_LEVEL=$BRACE_LOG_LEVEL" ;;
    spring-jpa) echo "spring-jpa:  image $(image_id brace-bench/spring-jpa:local), TechEmpower $(in_image brace-bench/spring-jpa:local cat /spring/tfb-commit | cut -c1-12) + Spring Data JPA profile (benchmark/docker/spring-jpa), $(in_image brace-bench/spring-jpa:local java -version)" ;;
  esac
done
echo "load:        wrk -t$WRK_THREADS -c$WRK_CONNECTIONS -d$WRK_DURATION --latency, $WARMUP_DURATION warmup per test"
echo "plan:        $ROUNDS rounds x (${FRAMEWORKS[*]}), $RUNS measured pass(es) per launch"
echo "load avg:    $(uptime | sed 's/.*load averages*: //')"
echo ""

# CPU time the hypervisor gave to other tenants ("steal"), from /proc/stat on a Linux host: a
# direct measure of noisy neighbours on a cloud VM. Prints "total steal" jiffies.
cpu_jiffies() { [ -r /proc/stat ] && awk '/^cpu / { t = 0; for (i = 2; i <= NF; i++) t += $i; print t, $9 }' /proc/stat; }
STEAL0=$(cpu_jiffies || true)

# ---- Rounds ----------------------------------------------------------------------------------

for round in $(seq 1 "$ROUNDS"); do
  for fw in "${FRAMEWORKS[@]}"; do
    label=$fw
    echo "##### Round $round/$ROUNDS: $label #####"
    "${COMPOSE[@]}" --profile "$fw" up -d --no-deps --force-recreate "$fw" > /dev/null 2>&1
    t0=$(now_ms)
    CID=$("${COMPOSE[@]}" --profile "$fw" ps -q "$fw")
    rc=0
    wait_until_ready container_alive || rc=$?
    t1=$(now_ms)
    if [ "$rc" != 0 ]; then
      echo "FATAL: $label did not come up (container $( [ "$rc" = 2 ] && echo exited || echo 'not answering after 120s')); last log lines:" >&2
      docker logs --tail 20 "$CID" >&2 || true
      exit 1
    fi
    echo "startup:     $((t1 - t0)) ms (container started to first 200)"
    tsv_row "$label" "$round" _startup "$((t1 - t0))" 0 0 0
    m=$(mem_now_mb); echo "rss ready:   $m MB"
    tsv_row "$label" "$round" _mem_ready "$m" 0 0 0
    tfb_check_responses
    tfb_warmup "$label" "$round"
    echo ""
    tfb_run_suite "$label" "$round"
    docker logs "$CID" > "${OUT%.txt}-$label-round$round.log" 2>&1 || true
    "${COMPOSE[@]}" --profile "$fw" stop -t 30 "$fw" > /dev/null 2>&1
    if [ "$(docker inspect -f '{{.State.OOMKilled}}' "$CID")" = true ]; then
      echo "WARNING: $label was OOM-killed in round $round"
      tsv_row "$label" "$round" _oom_killed 1 0 0 0
    fi
    "${COMPOSE[@]}" --profile "$fw" rm -f "$fw" > /dev/null 2>&1
  done
done

STEAL1=$(cpu_jiffies || true)
if [ -n "$STEAL0" ] && [ -n "$STEAL1" ]; then
  echo "cpu steal:   $(echo "$STEAL0 $STEAL1" | awk '{ d = $3 - $1; printf "%.2f%%", (d > 0 ? 100 * ($4 - $2) / d : 0) }') of host CPU time during the run (above ~1% means noisy neighbours; distrust the run)"
  echo ""
fi
tfb_summary
