-- Prints one machine-readable line after a wrk run, with the latency percentiles wrk's
-- --latency table leaves out (it shows only 50/75/90/99). Values in microseconds.
-- Used by lib.sh (tfb_measure) for both the local and the containerized runs.
done = function(summary, latency, requests)
  io.write(string.format(
    "TFB_LATENCY_US p50=%d p90=%d p95=%d p99=%d p999=%d max=%d\n",
    latency:percentile(50), latency:percentile(90), latency:percentile(95),
    latency:percentile(99), latency:percentile(99.9), latency.max))
end
