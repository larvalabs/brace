package benchmark;

import com.larvalabs.brace.*;
import benchmark.model.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Profiles the DB path to identify where time is spent.
 * Runs 10,000 iterations of the single-query pattern and prints timing breakdown.
 */
public class Profile {
    public static void main(String[] args) throws Exception {
        var db = new DatabaseFactory(
            "jdbc:postgresql://localhost:5433/hello_world",
            "benchmarkdbuser", "benchmarkdbpass",
            List.of(World.class, Fortune.class),
            256);

        // Warmup
        for (int i = 0; i < 1000; i++) {
            var session = db.openSession();
            var d = new Database(session);
            d.beginTransaction();
            int id = ThreadLocalRandom.current().nextInt(1, 10001);
            d.find(World.class, id);
            d.commitTransaction();
            d.close();
        }

        int iterations = 10_000;

        // Profile single query with full per-request lifecycle (what BraceHandler does)
        long totalOpenSession = 0, totalBeginTx = 0, totalFind = 0, totalCommit = 0, totalClose = 0;

        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            var session = db.openSession();
            long t1 = System.nanoTime();
            var d = new Database(session);
            d.beginTransaction();
            long t2 = System.nanoTime();
            int id = ThreadLocalRandom.current().nextInt(1, 10001);
            d.find(World.class, id);
            long t3 = System.nanoTime();
            d.commitTransaction();
            long t4 = System.nanoTime();
            d.close();
            long t5 = System.nanoTime();

            totalOpenSession += (t1 - t0);
            totalBeginTx += (t2 - t1);
            totalFind += (t3 - t2);
            totalCommit += (t4 - t3);
            totalClose += (t5 - t4);
        }

        long totalAll = totalOpenSession + totalBeginTx + totalFind + totalCommit + totalClose;
        System.out.println("=== Single Query Profile (" + iterations + " iterations) ===");
        System.out.printf("openSession:      %6.2f us  (%4.1f%%)\n", totalOpenSession / 1000.0 / iterations, 100.0 * totalOpenSession / totalAll);
        System.out.printf("beginTransaction: %6.2f us  (%4.1f%%)\n", totalBeginTx / 1000.0 / iterations, 100.0 * totalBeginTx / totalAll);
        System.out.printf("find (query):     %6.2f us  (%4.1f%%)\n", totalFind / 1000.0 / iterations, 100.0 * totalFind / totalAll);
        System.out.printf("commit:           %6.2f us  (%4.1f%%)\n", totalCommit / 1000.0 / iterations, 100.0 * totalCommit / totalAll);
        System.out.printf("close:            %6.2f us  (%4.1f%%)\n", totalClose / 1000.0 / iterations, 100.0 * totalClose / totalAll);
        System.out.printf("TOTAL:            %6.2f us\n", totalAll / 1000.0 / iterations);
        System.out.printf("\nImplied max req/sec: %.0f\n", 1_000_000.0 / (totalAll / 1000.0 / iterations));

        // For comparison: profile just the find without session/tx lifecycle
        long totalFindOnly = 0;
        var persistentSession = db.openSession();
        var pd = new Database(persistentSession);
        pd.beginTransaction();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            int id = ThreadLocalRandom.current().nextInt(1, 10001);
            pd.find(World.class, id);
            long t1 = System.nanoTime();
            totalFindOnly += (t1 - t0);
        }
        pd.commitTransaction();
        pd.close();

        System.out.println("\n=== Find-only (reused session, single tx) ===");
        System.out.printf("find:             %6.2f us\n", totalFindOnly / 1000.0 / iterations);
        System.out.printf("Implied max req/sec: %.0f\n", 1_000_000.0 / (totalFindOnly / 1000.0 / iterations));

        // Profile fortunes
        long totalFortunes = 0;
        for (int i = 0; i < iterations; i++) {
            var session = db.openSession();
            var d = new Database(session);
            d.beginTransaction();
            long t0 = System.nanoTime();
            var fortunes = new ArrayList<>(d.findAll(Fortune.class));
            var additional = new Fortune();
            additional.id = 0;
            additional.message = "Additional fortune added at request time.";
            fortunes.add(additional);
            fortunes.sort(Comparator.comparing(f -> f.message));
            long t1 = System.nanoTime();
            d.commitTransaction();
            d.close();
            totalFortunes += (t1 - t0);
        }
        System.out.println("\n=== Fortunes (query + sort, excluding session lifecycle) ===");
        System.out.printf("fortunes:         %6.2f us\n", totalFortunes / 1000.0 / iterations);

        db.close();
    }
}
