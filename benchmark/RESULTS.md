# Brace TFB Benchmark Results

## Environment
- MacBook (Apple Silicon)
- Java 25.0.2 (Homebrew OpenJDK)
- PostgreSQL 16.13 (Docker, localhost:5433)
- wrk: 4 threads, 256 connections, 15 second duration

## Results

| Test | Req/sec | Avg Latency | Max Latency |
|---|---|---|---|
| Plaintext | 72,828 | 3.34ms | 32.66ms |
| JSON | 72,493 | 3.52ms | 26.15ms |
| Single Query | 8,607 | 29.65ms | 133.53ms |
| Multiple Queries (20) | 879 | 286.54ms | 692.90ms |
| Fortunes | 8,548 | 29.63ms | 84.27ms |
| Updates (20) | 434 | 586.78ms | 1.90s |

## Notes
- Plaintext/JSON show raw Jetty 12 throughput: ~73K req/s
- Single Query and Fortunes are similar (~8.5K req/s) — dominated by per-request Hibernate StatelessSession + PostgreSQL round-trip
- Multiple Queries and Updates scale linearly with query count (20 queries = ~20x slower than single)
- This is a dev laptop benchmark, not production hardware. Relative comparison with Spring Boot matters more than absolute numbers.

## TODO
- Run Spring Boot TFB on same machine for comparison
- Tune connection pool size
- Test with H2 embedded for max-performance comparison
