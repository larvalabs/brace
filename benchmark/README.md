# Brace Benchmarks

## Runtime Performance (TechEmpower Framework Benchmark suite)

Implements the standard TFB test suite to compare Brace against Spring Boot on the same hardware.

### Tests
1. **Plaintext** (`/plaintext`) — raw HTTP throughput
2. **JSON** (`/json`) — JSON serialization
3. **Single Query** (`/db`) — one DB lookup
4. **Multiple Queries** (`/queries?queries=20`) — 20 random DB lookups
5. **Fortunes** (`/fortunes`) — DB query + sort + template render + XSS escape
6. **Updates** (`/updates?queries=20`) — 20 read-modify-write cycles

### Setup

Requires PostgreSQL with TFB schema:

```bash
docker run -d --name tfb-postgres -p 5432:5432 \
  -e POSTGRES_USER=benchmarkdbuser \
  -e POSTGRES_PASSWORD=benchmarkdbpass \
  -e POSTGRES_DB=hello_world \
  postgres:16

# Seed the database
psql -h localhost -U benchmarkdbuser -d hello_world -f benchmark/sql/create.sql
```

### Running

```bash
# Build
cd benchmark
mvn package -DskipTests

# Run Brace benchmark app
java -jar target/brace-benchmark.jar

# In another terminal, run wrk
wrk -t8 -c256 -d15s http://localhost:8080/plaintext
wrk -t8 -c256 -d15s http://localhost:8080/json
wrk -t8 -c256 -d15s http://localhost:8080/db
wrk -t8 -c256 -d15s http://localhost:8080/queries?queries=20
wrk -t8 -c256 -d15s http://localhost:8080/fortunes
wrk -t8 -c256 -d15s http://localhost:8080/updates?queries=20
```

### Comparing with Spring Boot

Use the Spring Boot TFB implementation from the TechEmpower repo:
https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Java/spring
