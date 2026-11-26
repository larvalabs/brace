# The load generator. Runs pinned to its own CPUs (compose.yml), on the same Docker network
# as the app.
FROM alpine:3.22
RUN apk add --no-cache wrk
# Percentile report (p50/p90/p95/p99/p99.9/max) read by lib.sh.
COPY wrk-report.lua /scripts/report.lua
ENTRYPOINT ["wrk"]
