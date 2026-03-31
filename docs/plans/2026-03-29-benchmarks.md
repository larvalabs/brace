# Brace Benchmarks Plan

## Overview

Three benchmarks that validate Brace's core claims:

1. **Runtime Performance (TFB)** — TechEmpower Fortunes suite, Brace vs Spring Boot on same hardware
2. **Token Efficiency: Greenfield** — Build PetClinic from scratch in both frameworks, measure AI token usage
3. **Token Efficiency: Feature Addition** — Add a feature to the existing PetClinic implementations, measure how tokens scale with codebase size

---

## Benchmark 1: TFB Runtime Performance

### Status
Brace TFB implementation: DONE (6 endpoints in `benchmark/`)
Spring Boot TFB implementation: use existing from TechEmpower repo

### Tasks

#### 1A: Set up PostgreSQL with TFB schema
- Docker Compose for PostgreSQL with the TFB seed data
- Verify both Brace and Spring Boot can connect

#### 1B: Run Brace TFB benchmark
- Start Brace benchmark app
- Run `wrk` for each endpoint (plaintext, json, db, queries, fortunes, updates)
- Record results at concurrency levels: 16, 64, 256, 512

#### 1C: Set up Spring Boot TFB implementation
- Clone TechEmpower repo or use their Spring Boot implementation
- Build and run against same PostgreSQL
- Match configuration (thread pool, connection pool) as closely as possible

#### 1D: Run Spring Boot TFB benchmark
- Same `wrk` commands against Spring Boot
- Same concurrency levels

#### 1E: Compare and document results
- Side-by-side table: req/s, avg latency, p99 latency for each test
- Startup time comparison
- Memory usage comparison

---

## Benchmark 2: Token Efficiency — Greenfield PetClinic

### What PetClinic includes
- 5 entities: Owner, Pet, PetType, Vet (with Specialties many-to-many), Visit
- Relationships: Owner→Pets, Pet→Visits, Pet→PetType, Vet↔Specialties
- Features: list/search owners, add/edit owners, add pets, record visits, list vets
- Form validation, server-side HTML rendering

### Tasks

#### 2A: Design the PetClinic prompt
- Write a detailed spec that both frameworks receive identically
- Include: entities, relationships, pages, forms, validation rules
- The prompt should be framework-agnostic ("build a pet clinic app with these features")

#### 2B: Implement PetClinic in Brace via AI
- Start from `brace new petclinic`
- Give AI the spec prompt
- Measure: input tokens, output tokens, first-attempt success, retry tokens
- Run 3 times for statistical significance

#### 2C: Implement PetClinic in Spring Boot via AI
- Start from `spring init` (or Spring Initializr scaffold)
- Give AI the identical spec prompt
- Measure same metrics
- Run 3 times

#### 2D: Compare and document results
- Total tokens per framework
- First-attempt success rate
- Retry rate and cost
- Lines of code generated
- Which errors AI made in each framework

---

## Benchmark 3: Token Efficiency — Feature Addition

### The feature: Appointment Scheduling
Add to the existing PetClinic:
- New entity: Appointment (date/time, pet, vet, notes, status)
- New page: schedule an appointment (select pet, select vet, pick time)
- New page: list upcoming appointments for an owner
- New page: vet's daily schedule
- Validation: no double-booking a vet, appointment must be in the future

### Tasks

#### 3A: Design the feature addition prompt
- Identical prompt for both frameworks
- "Add appointment scheduling to this PetClinic app" with detailed requirements
- Include the existing codebase as context

#### 3B: Add appointment scheduling to Brace PetClinic via AI
- AI reads existing Brace PetClinic codebase
- Measure: context tokens (how much AI needs to read), output tokens, retries
- Run 3 times

#### 3C: Add appointment scheduling to Spring Boot PetClinic via AI
- AI reads existing Spring Boot PetClinic codebase
- Measure same metrics
- Run 3 times

#### 3D: Compare and document results
- Context tokens (Brace vs Spring Boot — this is where the "scales linearly" claim is tested)
- Total tokens
- Success rate
- Document which Spring Boot concepts caused AI confusion (DI, security config, etc.)

---

## Automation

For benchmarks 2 and 3, ideally automate via the Claude API:
1. Send prompt with codebase context
2. Capture response (code)
3. Write files
4. Run `mvn compile` and `mvn test`
5. Record: pass/fail, token counts (from API response)
6. If fail: send error back to AI, capture fix, record retry tokens
7. Repeat 3 times per framework

This could be a Python script using the Anthropic SDK.

---

## Expected Results

| Metric | Brace | Spring Boot | Difference |
|---|---|---|---|
| Fortunes req/s | TBD | TBD | ~2x (estimated) |
| Startup time | TBD | TBD | ~3x (estimated) |
| Greenfield tokens | TBD | TBD | ~65% less (estimated) |
| First-attempt success | TBD | TBD | ~90% vs ~60% (estimated) |
| Feature-add tokens | TBD | TBD | gap widens (estimated) |
| Feature-add context tokens | TBD | TBD | linear vs super-linear (estimated) |
