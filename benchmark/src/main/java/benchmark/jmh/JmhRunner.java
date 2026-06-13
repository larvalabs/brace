package benchmark.jmh;

import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Programmatic JMH entry point so the benchmark jar can keep {@code benchmark.App} as its shaded main
 * class. Always attaches the GC profiler — {@code gc.alloc.rate.norm} (bytes/op) is the whole point of
 * these micro-benchmarks. An optional first arg is an include regex (default: all of {@code benchmark.jmh}).
 *
 * <p>Run from the repo root:
 * {@code java --enable-preview -cp benchmark/target/brace-benchmark-*.jar benchmark.jmh.JmhRunner [Regex]}
 */
public final class JmhRunner {
    private JmhRunner() {}

    public static void main(String[] args) throws Exception {
        String include = args.length > 0 ? args[0] : "benchmark\\.jmh\\.";
        var options = new OptionsBuilder()
                .include(include)
                .addProfiler(GCProfiler.class)
                .build();
        new Runner(options).run();
    }
}
