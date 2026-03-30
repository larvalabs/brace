package io.brace;

import java.time.Duration;

public class JobOptions {
    private int maxAttempts = 3;
    private Duration backoff = Duration.ofMinutes(1);
    private Long afterJobId = null;

    public JobOptions() {}

    public static JobOptions maxAttempts(int n) {
        var opts = new JobOptions();
        opts.maxAttempts = n;
        return opts;
    }

    public JobOptions backoff(Duration d) {
        this.backoff = d;
        return this;
    }

    public static JobOptions after(long jobId) {
        var opts = new JobOptions();
        opts.afterJobId = jobId;
        return opts;
    }

    public int maxAttempts() { return maxAttempts; }
    public Duration backoff() { return backoff; }
    public Long afterJobId() { return afterJobId; }
}
