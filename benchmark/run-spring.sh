#!/usr/bin/env bash
set -euo pipefail

# TechEmpower-style suite against the official TechEmpower Spring Boot implementation
# (frameworks/Java/spring: Spring Boot 3.3.5, Undertow, Hikari). Same driver as run-brace.sh
# (lib.sh), so both get identical checks, warmups, runs and summary.
#
# The TechEmpower repo was archived in March 2026; the code is still there. Build it once:
#   git clone --depth 1 --filter=blob:none --sparse \
#     https://github.com/TechEmpower/FrameworkBenchmarks.git /tmp/tfb-spring
#   cd /tmp/tfb-spring && git sparse-checkout set frameworks/Java/spring
#   cd frameworks/Java/spring && mvn package -q -DskipTests
#
# SPRING_PROFILE picks the data-access variant TechEmpower ships, and its ORM class there:
#   jdbc (default)  JdbcTemplate             "Micro"
#   data-jdbc       Spring Data JDBC         "Full"
#   jooq            jOOQ                     "Full"
# None of them is Hibernate/JPA.
#
# Environment as run-brace.sh, plus SPRING_DIR and SPRING_PROFILE.

source "$(cd "$(dirname "$0")" && pwd)/lib.sh"
use_jdk
tfb_init
tfb_preflight

SPRING_DIR="${SPRING_DIR:-/tmp/tfb-spring/frameworks/Java/spring}"
SPRING_PROFILE="${SPRING_PROFILE:-jdbc}"
JAR="$SPRING_DIR/target/hello-spring-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "Spring TFB jar not found at $JAR; see the build steps at the top of this script." >&2
  exit 1
fi

tfb_record_output "spring-$SPRING_PROFILE"
tfb_print_environment "Spring Boot TFB benchmark, profile $SPRING_PROFILE ($JAR)"

# JVM flags from TechEmpower's spring.dockerfile. The pool size is set to DB_POOL so both
# frameworks get the same pool (the TechEmpower config hardcodes 256).
# shellcheck disable=SC2086
tfb_start_app "spring-$SPRING_PROFILE" "$RESULTS_DIR/spring-app.log" \
  java -XX:+DisableExplicitGC -XX:+UseStringDeduplication $JVM_OPTS \
    -Dlogging.level.root=OFF \
    -Ddatabase.host="$DB_HOST" -Ddatabase.port="$DB_PORT" \
    -Dspring.datasource.hikari.maximum-pool-size="$DB_POOL" \
    -Dserver.port="$PORT" \
    -jar "$JAR" --spring.profiles.active="$SPRING_PROFILE"
tfb_check_responses
tfb_warmup "spring-$SPRING_PROFILE" 1
echo ""
tfb_run_suite "spring-$SPRING_PROFILE"
tfb_summary
