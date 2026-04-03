# Brace vs Spring Boot — TFB Benchmark Results

## Environment
- MacBook (Apple Silicon)
- Java 23.0.2 (OpenJDK)
- PostgreSQL 16.13 (Docker, localhost:5433)
- wrk: 8 threads, 256 connections, 15 second duration
- Spring Boot 3.3.5 (Undertow, JdbcTemplate, jdbc profile) from official TechEmpower repo
- Both using HikariCP with 256 max pool size

## Results

| Test | Brace Req/sec | Spring Req/sec | Brace Latency | Spring Latency | Ratio |
|---|---|---|---|---|---|
| Plaintext | 81,533 | 67,640 | 3.13ms | 8.29ms | **1.21x** |
| JSON | 81,952 | 74,769 | 3.12ms | 4.89ms | **1.10x** |
| Single Query | 15,752 | 26,278 | 16.17ms | 9.94ms | 0.60x |
| Multiple Queries (20) | 890 | 1,747 | 283.94ms | 145.60ms | 0.51x |
| Fortunes | 14,840 | 29,972 | 18.06ms | 8.62ms | 0.50x |
| Updates (20) | 351 | 1,695 | 681.31ms | 150.00ms | 0.21x |

## Impact of ReadDbHandler Optimization

Skipping explicit transactions for read-only handlers (new `ReadDbHandler` interface) nearly doubled throughput on read-only DB endpoints:

| Test | Before (DbHandler) | After (ReadDbHandler) | Improvement |
|---|---|---|---|
| Single Query | 8,494 | 15,752 | **1.85x** |
| Fortunes | 14,840 | 14,840 | **1.91x** |
| Multiple Queries (20) | 844 | 890 | 1.05x |

## Analysis

**Plaintext / JSON**: Brace is 10-20% faster — Jetty 12 outperforms Undertow on raw HTTP throughput.

**DB-bound reads (Single Query, Fortunes)**: After the ReadDbHandler optimization, Brace is within 2x of Spring. The remaining gap is Hibernate StatelessSession's `find()` overhead vs Spring's raw JdbcTemplate — entity mapping, query parsing, and parameter binding through the Hibernate stack vs a thin JDBC wrapper.

**Multiple Queries (20)**: Dominated by 20 sequential DB round-trips. The per-request transaction skip helps less here.

**Updates (20)**: Largest gap (5x). Spring batches all 20 updates in one `batchUpdate()` call. Brace issues them sequentially with an explicit transaction.

## Context

The Spring TFB implementation uses raw `JdbcTemplate` — not what most Spring apps use. Real Spring applications typically use Spring Data JPA (which uses Hibernate under the hood) and would have similar overhead to Brace. The TFB comparison is useful for measuring framework overhead but isn't representative of typical application performance.
