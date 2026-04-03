# AI Token Efficiency Benchmark — Design Spec

## Goal

Measure how many AI tokens it takes to build the same application in Brace vs Spring Boot, using Claude Code in headless mode. Tests the claim that Brace's small, explicit API surface leads to more token-efficient AI-assisted development.

## App: Conference Manager

A JSON API for managing a small conference. Chosen because it has PetClinic-level complexity but isn't in AI training data, and the scheduling logic requires real programming beyond basic CRUD.

### Entities (6)

**Event**
- id, name (required), description, date (required), venueName

**Speaker**
- id, name (required), bio, email (required, unique)

**Room**
- id, name (required), capacity (required, positive integer), eventId (required)

**Talk**
- id, title (required), abstract, durationMinutes (required, positive integer), speakerId (required), eventId (required), roomId (required), startTime (required, ISO datetime)

**Attendee**
- id, name (required), email (required, unique per event), eventId (required)

**Registration**
- id, attendeeId (required), talkId (required)
- Unique constraint: one registration per attendee per talk

### Relationships

- Event has many Rooms, Talks, Attendees
- Speaker has many Talks
- Room has many Talks
- Attendee has many Registrations
- Talk has many Registrations
- Registration belongs to Attendee and Talk

### API Endpoints

**Events:** `POST /events`, `GET /events`, `GET /events/:id`, `PUT /events/:id`, `DELETE /events/:id`

**Speakers:** `POST /speakers`, `GET /speakers`, `GET /speakers/:id`, `PUT /speakers/:id`, `DELETE /speakers/:id`

**Rooms:** `POST /rooms`, `GET /rooms?event=:id`, `GET /rooms/:id`, `PUT /rooms/:id`, `DELETE /rooms/:id`

**Talks:** `POST /talks`, `GET /talks?event=:id`, `GET /talks/:id`, `PUT /talks/:id`, `DELETE /talks/:id`

**Attendees:** `POST /attendees`, `GET /attendees?event=:id`, `GET /attendees/:id`, `PUT /attendees/:id`, `DELETE /attendees/:id`

**Registrations:** `POST /registrations`, `DELETE /registrations/:id`, `GET /registrations?attendee=:id`

**Schedule endpoints:**
- `GET /events/:id/schedule` — all talks grouped by room, ordered by start time
- `GET /speakers/:id/schedule?event=:id` — speaker's talks at an event, ordered by time
- `GET /attendees/:id/schedule` — talks the attendee is registered for, ordered by time

### Business Rules

1. **Room conflict:** Two talks in the same room cannot have overlapping times (start time to start time + duration).
2. **Speaker conflict:** A speaker cannot give two talks with overlapping times at the same event.
3. **Talk within event:** A talk's date must match its event's date.
4. **Room capacity:** A registration is rejected if the talk's room is already at capacity (count of existing registrations >= room capacity).
5. **No duplicate registration:** An attendee cannot register for the same talk twice.

Conflict and capacity violations return HTTP 409. Validation errors return HTTP 400. Not found returns HTTP 404.

## Test Suite

~30 integration tests written in Python using `requests`. Tests run against the app over HTTP on a known port. Tests execute in order since later tests depend on data created by earlier ones.

**Test categories:**
- Entity CRUD (create, read, list, update, delete) for all 6 entities (~12 tests)
- Room double-booking rejected (~2 tests)
- Speaker double-booking rejected (~2 tests)
- Room capacity enforcement (~2 tests)
- Duplicate registration rejected (~1 test)
- Talk date must match event date (~1 test)
- Schedule endpoints return correct data (~3 tests)
- Edge cases: overlapping but not conflicting times, back-to-back talks (~3 tests)

Tests assert HTTP status codes and response body fields. Test suite reports pass/fail per test and total count.

## Project Structure

```
ai-benchmark/
  README.md                # how to run the benchmark
  spec.md                  # the prompt given to the AI (domain + API + rules)
  tests/
    test_conference.py     # integration test suite
    requirements.txt       # requests, pytest
  run.sh                   # orchestrator script
  results/                 # collected results per run
  brace-template/
    pom.xml                # brace dependency, empty project
    CLAUDE.md              # brace framework documentation
    src/main/java/app/
      App.java             # minimal main class that starts Brace
  spring-template/
    pom.xml                # spring boot starter web + jpa + h2
    CLAUDE.md              # spring boot documentation (equivalent scope to brace)
    src/main/java/app/
      App.java             # @SpringBootApplication main class
```

Both templates use H2 in-memory database to avoid Docker/Postgres setup complexity during runs.

## CLAUDE.md Approach

Both frameworks get a CLAUDE.md covering the same topics at equivalent depth:
- Project structure conventions
- How to add an entity
- How to add an endpoint
- How to add validation
- Database/query patterns
- Dependencies available
- How to build and run

The Brace CLAUDE.md is derived from the existing one in the repo. The Spring CLAUDE.md is written to cover the same ground using standard Spring Boot patterns (Spring Data JPA, @RestController, Bean Validation).

Both are honest best-effort documentation. The contents are part of the benchmark repo so anyone can review them, disagree, substitute their own, and rerun.

## Runner Script (run.sh)

For each framework, for each run:
1. Copy template project to a fresh working directory
2. Run `claude -p "$(cat spec.md)" --output-format json` in that directory
3. Attempt `mvn compile`
4. If compile fails, run `claude -p "fix these compilation errors: $(mvn compile 2>&1)" --output-format json` (up to 5 retries)
5. Start the app on port 8080
6. Run pytest test suite against localhost:8080
7. If tests fail, run `claude -p "these tests are failing: $(pytest output)" --output-format json` (up to 3 retries, re-running the test suite after each fix)
8. Record all metrics from JSON output
9. Stop the app, clean up

Repeat 3 times per framework. If results overlap between frameworks, add runs up to 5.

## Metrics

**Per run:**
- Input tokens consumed
- Output tokens generated
- Total tokens (input + output)
- Compile attempts (1 = first try success)
- Test fix attempts (0 = all tests pass first try)
- Tests passed (out of total)
- Wall clock time

**Aggregated:**
- Mean and range for each metric
- First-attempt compile rate
- First-attempt full test pass rate
- Total tokens: mean, min, max per framework

## What This Measures

- **Token efficiency:** Does Brace's smaller API surface require fewer tokens to build the same app?
- **First-attempt success:** Does either framework produce working code more reliably on the first try?
- **Fix cost:** When things break, which framework costs more tokens to fix?

## What This Doesn't Measure

- Template/HTML rendering efficiency (future benchmark)
- Performance of the resulting app
- Code quality or maintainability
- Developer experience beyond token cost
