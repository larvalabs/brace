# AI Token Efficiency Benchmark — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible benchmark that measures AI token usage when building the same Conference Manager app in Brace vs Spring Boot.

**Architecture:** A standalone project (`ai-benchmark/`) containing template projects for both frameworks, a shared spec prompt, an integration test suite, and a runner script that orchestrates Claude Code headless runs and collects metrics.

**Tech Stack:** Bash (runner), Python + pytest + requests (test suite), Maven (both frameworks), H2 in-memory DB (both frameworks)

---

### Task 1: Create project structure and README

**Files:**
- Create: `ai-benchmark/README.md`

- [ ] **Step 1: Create the project directory and README**

```bash
mkdir -p ai-benchmark/tests ai-benchmark/results ai-benchmark/brace-template/src/main/java/app ai-benchmark/spring-template/src/main/java/app
```

Write `ai-benchmark/README.md`:

```markdown
# AI Token Efficiency Benchmark

Measures how many AI tokens it takes to build the same Conference Manager API
in Brace vs Spring Boot using Claude Code in headless mode.

## Prerequisites

- Java 21+ (`java --version`)
- Maven (`mvn --version`)
- Python 3.10+ (`python3 --version`)
- Claude Code CLI (`claude --version`)

## Quick Start

```bash
# Install test dependencies
pip install -r tests/requirements.txt

# Run the full benchmark (3 runs per framework)
./run.sh

# Results are written to results/
```

## Structure

- `spec.md` — the prompt given to the AI (identical for both frameworks)
- `tests/` — integration test suite (framework-agnostic, runs over HTTP)
- `brace-template/` — empty Brace project with CLAUDE.md
- `spring-template/` — empty Spring Boot project with CLAUDE.md
- `run.sh` — orchestrator: runs Claude Code, compiles, tests, collects metrics
- `results/` — JSON output from each run
```

- [ ] **Step 2: Commit**

```bash
cd ai-benchmark
git init
git add README.md
git commit -m "Initial project structure and README"
```

---

### Task 2: Write the spec prompt (spec.md)

**Files:**
- Create: `ai-benchmark/spec.md`

- [ ] **Step 1: Write spec.md**

This is the prompt given identically to both frameworks. It must be framework-agnostic — it describes what to build, not how.

Write `ai-benchmark/spec.md`:

```markdown
# Conference Manager API

Build a JSON REST API for managing a small conference. The app should use an
in-memory H2 database (no external database required). All endpoints accept
and return JSON. The app runs on port 8080.

## Entities

### Event
- id (auto-generated integer)
- name (required string)
- description (optional string)
- date (required, ISO date format YYYY-MM-DD)
- venueName (optional string)

### Speaker
- id (auto-generated integer)
- name (required string)
- bio (optional string)
- email (required string, globally unique)

### Room
- id (auto-generated integer)
- name (required string)
- capacity (required positive integer)
- eventId (required, references Event)

### Talk
- id (auto-generated integer)
- title (required string)
- description (optional string)
- durationMinutes (required positive integer)
- startTime (required, ISO datetime format YYYY-MM-DDTHH:MM:SS)
- speakerId (required, references Speaker)
- eventId (required, references Event)
- roomId (required, references Room)

### Attendee
- id (auto-generated integer)
- name (required string)
- email (required string, unique within the same event)
- eventId (required, references Event)

### Registration
- id (auto-generated integer)
- attendeeId (required, references Attendee)
- talkId (required, references Talk)
- Unique constraint: one registration per attendee per talk

## API Endpoints

All endpoints return JSON. Use standard HTTP status codes.

### Events
- POST /events — create event, return 201 with created event
- GET /events — list all events
- GET /events/:id — get event by id, 404 if not found
- PUT /events/:id — update event, 404 if not found
- DELETE /events/:id — delete event, 204 on success

### Speakers
- POST /speakers — create speaker, return 201
- GET /speakers — list all speakers
- GET /speakers/:id — get speaker by id, 404 if not found
- PUT /speakers/:id — update speaker, 404 if not found
- DELETE /speakers/:id — delete speaker, 204 on success

### Rooms
- POST /rooms — create room, return 201
- GET /rooms?event=:eventId — list rooms for an event
- GET /rooms/:id — get room by id, 404 if not found
- PUT /rooms/:id — update room, 404 if not found
- DELETE /rooms/:id — delete room, 204 on success

### Talks
- POST /talks — create talk, return 201. Validate business rules (see below).
- GET /talks?event=:eventId — list talks for an event
- GET /talks/:id — get talk by id, 404 if not found
- PUT /talks/:id — update talk, 404 if not found
- DELETE /talks/:id — delete talk, 204 on success

### Attendees
- POST /attendees — create attendee, return 201
- GET /attendees?event=:eventId — list attendees for an event
- GET /attendees/:id — get attendee by id, 404 if not found
- PUT /attendees/:id — update attendee, 404 if not found
- DELETE /attendees/:id — delete attendee, 204 on success

### Registrations
- POST /registrations — register attendee for talk, return 201. Validate capacity.
- GET /registrations?attendee=:attendeeId — list registrations for an attendee
- DELETE /registrations/:id — delete registration, 204 on success

### Schedule Endpoints
- GET /events/:id/schedule — all talks at this event, grouped by room, ordered by startTime within each room. Return format:
  ```json
  [
    {
      "room": {"id": 1, "name": "Main Hall", "capacity": 100},
      "talks": [
        {"id": 1, "title": "Keynote", "startTime": "2025-06-15T09:00:00", "durationMinutes": 60, "speaker": {"id": 1, "name": "Jane Doe"}}
      ]
    }
  ]
  ```
- GET /speakers/:id/schedule?event=:eventId — talks by this speaker at this event, ordered by startTime. Return array of talk objects.
- GET /attendees/:id/schedule — talks this attendee is registered for, ordered by startTime. Return array of talk objects.

## Business Rules

1. **Room conflict:** Two talks in the same room cannot have overlapping times. A talk occupies the window from startTime to startTime + durationMinutes. Return 409 with an error message if there is a conflict.

2. **Speaker conflict:** A speaker cannot give two talks with overlapping times at the same event. Return 409 with an error message.

3. **Talk date must match event date:** The date portion of a talk's startTime must equal the event's date. Return 400 with an error message.

4. **Room capacity:** A registration is rejected if the number of existing registrations for the talk equals or exceeds the talk's room capacity. Return 409 with an error message.

5. **No duplicate registration:** An attendee cannot register for the same talk twice. Return 409 with an error message.

## Validation

- Missing required fields return 400
- Referenced entities that don't exist return 400
- Business rule violations return 409 (conflicts/capacity) or 400 (validation)
- Not found returns 404

## Notes

- Use auto-generated integer IDs (not UUIDs)
- Dates are ISO format strings (YYYY-MM-DD for dates, YYYY-MM-DDTHH:MM:SS for datetimes)
- No authentication required
- No pagination required
```

- [ ] **Step 2: Commit**

```bash
git add spec.md
git commit -m "Add conference manager spec prompt"
```

---

### Task 3: Write the integration test suite

**Files:**
- Create: `ai-benchmark/tests/requirements.txt`
- Create: `ai-benchmark/tests/test_conference.py`

- [ ] **Step 1: Create requirements.txt**

Write `ai-benchmark/tests/requirements.txt`:

```
requests>=2.31
pytest>=7.4
```

- [ ] **Step 2: Write the test suite**

Write `ai-benchmark/tests/test_conference.py`:

```python
"""
Integration tests for the Conference Manager API.
Run against a live server on http://localhost:8080.
Tests execute in order — later tests depend on data created by earlier ones.
"""
import requests
import pytest

BASE = "http://localhost:8080"


# ---------- helpers ----------

def post(path, json):
    return requests.post(f"{BASE}{path}", json=json)

def get(path):
    return requests.get(f"{BASE}{path}")

def put(path, json):
    return requests.put(f"{BASE}{path}", json=json)

def delete(path):
    return requests.delete(f"{BASE}{path}")


# ---------- Event CRUD ----------

class TestEventCRUD:
    def test_create_event(self):
        r = post("/events", {"name": "DevConf 2025", "date": "2025-06-15", "venueName": "Convention Center"})
        assert r.status_code == 201
        body = r.json()
        assert body["name"] == "DevConf 2025"
        assert body["date"] == "2025-06-15"
        assert "id" in body

    def test_list_events(self):
        r = get("/events")
        assert r.status_code == 200
        events = r.json()
        assert len(events) >= 1

    def test_get_event(self):
        r = get("/events/1")
        assert r.status_code == 200
        assert r.json()["name"] == "DevConf 2025"

    def test_update_event(self):
        r = put("/events/1", {"name": "DevConf 2025 Updated", "date": "2025-06-15"})
        assert r.status_code == 200
        assert r.json()["name"] == "DevConf 2025 Updated"

    def test_get_event_not_found(self):
        r = get("/events/9999")
        assert r.status_code == 404


# ---------- Speaker CRUD ----------

class TestSpeakerCRUD:
    def test_create_speaker(self):
        r = post("/speakers", {"name": "Alice Smith", "email": "alice@example.com", "bio": "Expert in distributed systems"})
        assert r.status_code == 201
        assert r.json()["name"] == "Alice Smith"

    def test_create_second_speaker(self):
        r = post("/speakers", {"name": "Bob Jones", "email": "bob@example.com"})
        assert r.status_code == 201

    def test_duplicate_speaker_email(self):
        r = post("/speakers", {"name": "Alice Clone", "email": "alice@example.com"})
        assert r.status_code in [400, 409]

    def test_speaker_missing_required_fields(self):
        r = post("/speakers", {"bio": "No name or email"})
        assert r.status_code == 400


# ---------- Room CRUD ----------

class TestRoomCRUD:
    def test_create_room(self):
        r = post("/rooms", {"name": "Main Hall", "capacity": 100, "eventId": 1})
        assert r.status_code == 201
        assert r.json()["name"] == "Main Hall"

    def test_create_small_room(self):
        r = post("/rooms", {"name": "Workshop Room", "capacity": 2, "eventId": 1})
        assert r.status_code == 201

    def test_list_rooms_by_event(self):
        r = get("/rooms?event=1")
        assert r.status_code == 200
        assert len(r.json()) >= 2


# ---------- Talk CRUD + Business Rules ----------

class TestTalkCRUD:
    def test_create_talk(self):
        r = post("/talks", {
            "title": "Keynote: Future of Tech",
            "durationMinutes": 60,
            "startTime": "2025-06-15T09:00:00",
            "speakerId": 1,
            "eventId": 1,
            "roomId": 1
        })
        assert r.status_code == 201
        assert r.json()["title"] == "Keynote: Future of Tech"

    def test_create_second_talk(self):
        """Non-conflicting talk: different room, same time."""
        r = post("/talks", {
            "title": "Workshop: Hands-On Coding",
            "durationMinutes": 90,
            "startTime": "2025-06-15T09:00:00",
            "speakerId": 2,
            "eventId": 1,
            "roomId": 2
        })
        assert r.status_code == 201

    def test_create_back_to_back_talk(self):
        """Talk starts exactly when the first one ends — no conflict."""
        r = post("/talks", {
            "title": "Panel Discussion",
            "durationMinutes": 30,
            "startTime": "2025-06-15T10:00:00",
            "speakerId": 2,
            "eventId": 1,
            "roomId": 1
        })
        assert r.status_code == 201

    def test_list_talks_by_event(self):
        r = get("/talks?event=1")
        assert r.status_code == 200
        assert len(r.json()) >= 3


class TestRoomConflict:
    def test_room_double_booking(self):
        """Talk overlaps with Keynote (09:00-10:00) in Main Hall."""
        r = post("/talks", {
            "title": "Conflicting Talk",
            "durationMinutes": 30,
            "startTime": "2025-06-15T09:30:00",
            "speakerId": 2,
            "eventId": 1,
            "roomId": 1
        })
        assert r.status_code == 409

    def test_room_overlap_at_start(self):
        """Talk starts before Keynote ends."""
        r = post("/talks", {
            "title": "Another Conflict",
            "durationMinutes": 60,
            "startTime": "2025-06-15T08:30:00",
            "speakerId": 2,
            "eventId": 1,
            "roomId": 1
        })
        assert r.status_code == 409


class TestSpeakerConflict:
    def test_speaker_double_booking(self):
        """Alice already has Keynote at 09:00-10:00. Book her at 09:30 = conflict."""
        r = post("/talks", {
            "title": "Alice Double Booked",
            "durationMinutes": 30,
            "startTime": "2025-06-15T09:30:00",
            "speakerId": 1,
            "eventId": 1,
            "roomId": 2
        })
        assert r.status_code == 409


class TestTalkDateValidation:
    def test_talk_date_mismatch(self):
        """Event is on 2025-06-15, talk on 2025-06-16."""
        r = post("/talks", {
            "title": "Wrong Day",
            "durationMinutes": 30,
            "startTime": "2025-06-16T09:00:00",
            "speakerId": 2,
            "eventId": 1,
            "roomId": 2
        })
        assert r.status_code == 400


# ---------- Attendee CRUD ----------

class TestAttendeeCRUD:
    def test_create_attendee(self):
        r = post("/attendees", {"name": "Charlie Brown", "email": "charlie@example.com", "eventId": 1})
        assert r.status_code == 201

    def test_create_second_attendee(self):
        r = post("/attendees", {"name": "Diana Prince", "email": "diana@example.com", "eventId": 1})
        assert r.status_code == 201

    def test_duplicate_attendee_email_same_event(self):
        r = post("/attendees", {"name": "Charlie Again", "email": "charlie@example.com", "eventId": 1})
        assert r.status_code in [400, 409]


# ---------- Registration + Capacity ----------

class TestRegistration:
    def test_register_for_talk(self):
        r = post("/registrations", {"attendeeId": 1, "talkId": 1})
        assert r.status_code == 201

    def test_duplicate_registration(self):
        r = post("/registrations", {"attendeeId": 1, "talkId": 1})
        assert r.status_code == 409

    def test_register_second_attendee(self):
        r = post("/registrations", {"attendeeId": 2, "talkId": 1})
        assert r.status_code == 201

    def test_list_registrations(self):
        r = get("/registrations?attendee=1")
        assert r.status_code == 200
        assert len(r.json()) >= 1


class TestRoomCapacity:
    def test_register_for_small_room_talk(self):
        """Workshop Room has capacity 2. Register attendee 1."""
        r = post("/registrations", {"attendeeId": 1, "talkId": 2})
        assert r.status_code == 201

    def test_register_second_for_small_room(self):
        """Register attendee 2. Room now full."""
        r = post("/registrations", {"attendeeId": 2, "talkId": 2})
        assert r.status_code == 201

    def test_register_over_capacity(self):
        """Create a third attendee and try to register. Should be rejected."""
        post("/attendees", {"name": "Eve Extra", "email": "eve@example.com", "eventId": 1})
        r = post("/registrations", {"attendeeId": 3, "talkId": 2})
        assert r.status_code == 409


# ---------- Schedule Endpoints ----------

class TestSchedule:
    def test_event_schedule(self):
        r = get("/events/1/schedule")
        assert r.status_code == 200
        schedule = r.json()
        assert isinstance(schedule, list)
        assert len(schedule) >= 1
        # Each entry has room and talks
        entry = schedule[0]
        assert "room" in entry
        assert "talks" in entry
        assert isinstance(entry["talks"], list)

    def test_speaker_schedule(self):
        r = get("/speakers/1/schedule?event=1")
        assert r.status_code == 200
        talks = r.json()
        assert isinstance(talks, list)
        assert len(talks) >= 1
        assert talks[0]["title"] == "Keynote: Future of Tech"

    def test_attendee_schedule(self):
        r = get("/attendees/1/schedule")
        assert r.status_code == 200
        talks = r.json()
        assert isinstance(talks, list)
        assert len(talks) >= 1


# ---------- Delete ----------

class TestDelete:
    def test_delete_registration(self):
        r = delete("/registrations/1")
        assert r.status_code == 204

    def test_delete_event_not_found(self):
        r = delete("/events/9999")
        assert r.status_code == 404
```

- [ ] **Step 3: Verify test suite syntax**

```bash
cd ai-benchmark
pip install -r tests/requirements.txt
python -m py_compile tests/test_conference.py
echo "Syntax OK"
```

- [ ] **Step 4: Commit**

```bash
git add tests/
git commit -m "Add integration test suite (30 tests)"
```

---

### Task 4: Create Brace template project

**Files:**
- Create: `ai-benchmark/brace-template/pom.xml`
- Create: `ai-benchmark/brace-template/CLAUDE.md`
- Create: `ai-benchmark/brace-template/src/main/java/app/App.java`

- [ ] **Step 1: Create pom.xml**

Write `ai-benchmark/brace-template/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>app</groupId>
    <artifactId>conference-manager</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.brace</groupId>
            <artifactId>brace</artifactId>
            <version>0.1.0-SNAPSHOT</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>3.6.0</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals><goal>shade</goal></goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>app.App</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                            <filters>
                                <filter>
                                    <artifact>*:*</artifact>
                                    <excludes>
                                        <exclude>META-INF/*.SF</exclude>
                                        <exclude>META-INF/*.DSA</exclude>
                                        <exclude>META-INF/*.RSA</exclude>
                                    </excludes>
                                </filter>
                            </filters>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create CLAUDE.md**

Write `ai-benchmark/brace-template/CLAUDE.md`:

```markdown
# Brace Framework — Development Guide

## What This Is

Brace is a Java 21+ web framework. Plain Java, no DI container, no bytecode enhancement, no classpath scanning.

## Building and Running

```bash
mvn compile                    # compile
mvn package -DskipTests        # build fat jar
java -jar target/conference-manager-1.0-SNAPSHOT.jar  # run on port 8080
```

## Project Structure

```
src/main/java/app/
  App.java             # Entry point: configure routes and start server
  model/               # JPA entity classes
  controller/          # Handler methods grouped by resource
src/main/resources/
  db/migration/        # Flyway SQL migration files (V1__description.sql)
```

## Adding an Entity

Create a JPA entity class with public fields:

```java
package app.model;

import jakarta.persistence.*;

@Entity
@Table(name = "speakers")
public class Speaker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public int id;
    public String name;
    public String email;
    public String bio;
}
```

Then add a Flyway migration in `src/main/resources/db/migration/V1__create_tables.sql`:

```sql
CREATE TABLE speakers (
    id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    bio TEXT
);
```

Register the entity in App.java when creating DatabaseFactory:

```java
var db = new DatabaseFactory("jdbc:h2:mem:conference;DB_CLOSE_DELAY=-1",
    "sa", "", List.of(Speaker.class, Event.class /*, ... */));
```

## Adding an Endpoint

Handler methods receive a Request and return a Result. The parameter types determine what's available:

```java
// No database needed
app.get("/hello", req -> Result.text("Hello!"));

// With database access (auto transaction)
app.get("/speakers", (DbHandler) (req, db) -> {
    var speakers = db.findAll(Speaker.class);
    return Json.of(speakers);
});

// Read-only database (no transaction overhead)
app.get("/speakers/:id", (ReadDbHandler) (req, db) -> {
    var speaker = db.find(Speaker.class, Integer.parseInt(req.param("id")));
    if (speaker == null) return Result.notFound();
    return Json.of(speaker);
});
```

Register routes in App.java main method. Available methods: `app.get()`, `app.post()`, `app.put()`, `app.delete()`.

## Handler Types

- `Handler`: `(Request) -> Result` — no DB
- `ReadDbHandler`: `(Request, Database) -> Result` — read-only DB, no transaction
- `DbHandler`: `(Request, Database) -> Result` — DB with auto transaction
- `SessionHandler`: `(Request, Session) -> Result` — with session
- `FullHandler`: `(Request, Database, Session) -> Result` — DB + session

## Database Queries

```java
// Find by primary key
var speaker = db.find(Speaker.class, id);

// Find all
var speakers = db.findAll(Speaker.class);

// Query with HQL WHERE clause (use ? for params)
var talks = db.query(Talk.class, "eventId = ?", eventId);

// Single result
var talk = db.queryOne(Talk.class, "title = ?", title);

// Count
var count = db.count(Registration.class, "talkId = ?", talkId);

// Insert, update, delete
db.insert(speaker);
db.update(speaker);
db.delete(speaker);
```

## Returning JSON

```java
// Return an object as JSON (uses Jackson)
return Json.of(speaker);              // 200
return Json.of(speaker, 201);         // 201 Created

// Return error
return Result.error(400, "Name is required");
return Result.error(404, "Not found");
return Result.error(409, "Room conflict");

// Return no content
return Result.noContent();            // 204
```

## Reading Request Data

```java
// Path params (from route pattern like /speakers/:id)
String id = req.param("id");

// Query params (/rooms?event=1)
String eventId = req.param("event");

// JSON request body (parsed via Jackson)
var speaker = req.bodyAs(Speaker.class);
```

## App.java Structure

```java
package app;

import io.brace.*;
import app.model.*;
import java.util.List;

public class App {
    public static void main(String[] args) throws Exception {
        var db = new DatabaseFactory("jdbc:h2:mem:conference;DB_CLOSE_DELAY=-1",
            "sa", "", List.of(/* entity classes here */));

        var app = Brace.app()
            .port(8080)
            .database(db);

        // Register routes here
        app.get("/example", (ReadDbHandler) (req, dbSession) -> {
            return Json.of(dbSession.findAll(Example.class));
        });

        app.start();
    }
}
```
```

- [ ] **Step 3: Create minimal App.java**

Write `ai-benchmark/brace-template/src/main/java/app/App.java`:

```java
package app;

import io.brace.*;
import java.util.List;

public class App {
    public static void main(String[] args) throws Exception {
        var app = Brace.app().port(8080);
        app.get("/", req -> Result.text("Conference Manager API"));
        app.start();
    }
}
```

- [ ] **Step 4: Verify it compiles**

```bash
cd ai-benchmark/brace-template
mvn compile -q
```

- [ ] **Step 5: Commit**

```bash
cd ai-benchmark
git add brace-template/
git commit -m "Add Brace template project with CLAUDE.md"
```

---

### Task 5: Create Spring Boot template project

**Files:**
- Create: `ai-benchmark/spring-template/pom.xml`
- Create: `ai-benchmark/spring-template/CLAUDE.md`
- Create: `ai-benchmark/spring-template/src/main/java/app/App.java`
- Create: `ai-benchmark/spring-template/src/main/resources/application.properties`

- [ ] **Step 1: Create pom.xml**

Write `ai-benchmark/spring-template/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.4</version>
        <relativePath/>
    </parent>

    <groupId>app</groupId>
    <artifactId>conference-manager</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create application.properties**

Write `ai-benchmark/spring-template/src/main/resources/application.properties`:

```properties
spring.datasource.url=jdbc:h2:mem:conference;DB_CLOSE_DELAY=-1
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=update
spring.jpa.open-in-view=false
```

- [ ] **Step 3: Create CLAUDE.md**

Write `ai-benchmark/spring-template/CLAUDE.md`:

```markdown
# Spring Boot — Development Guide

## What This Is

Spring Boot 3.4 with Spring Data JPA, Bean Validation, and H2 in-memory database.

## Building and Running

```bash
mvn compile                    # compile
mvn package -DskipTests        # build fat jar
java -jar target/conference-manager-1.0-SNAPSHOT.jar  # run on port 8080
```

## Project Structure

```
src/main/java/app/
  App.java             # @SpringBootApplication entry point
  model/               # JPA entity classes
  repository/          # Spring Data JPA repositories
  controller/          # @RestController classes
src/main/resources/
  application.properties  # database and JPA config
```

## Adding an Entity

Create a JPA entity class:

```java
package app.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "speakers")
public class Speaker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotBlank
    private String name;

    @NotBlank
    @Column(unique = true)
    private String email;

    private String bio;

    // Getters and setters
    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
}
```

Spring JPA auto-creates tables from entity definitions (configured via `spring.jpa.hibernate.ddl-auto=update`).

## Adding a Repository

Create a Spring Data JPA repository interface:

```java
package app.repository;

import app.model.Speaker;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpeakerRepository extends JpaRepository<Speaker, Integer> {
    boolean existsByEmail(String email);
}
```

Spring Data JPA auto-implements CRUD methods: `findAll()`, `findById()`, `save()`, `deleteById()`, etc. Add custom query methods by following the naming convention (e.g., `findByEventId(int eventId)`).

## Adding a Controller

Create a @RestController:

```java
package app.controller;

import app.model.Speaker;
import app.repository.SpeakerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/speakers")
public class SpeakerController {
    private final SpeakerRepository repo;

    public SpeakerController(SpeakerRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Speaker> list() {
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public Speaker get(@PathVariable int id) {
        return repo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    public ResponseEntity<Speaker> create(@RequestBody Speaker speaker) {
        Speaker saved = repo.save(speaker);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public Speaker update(@PathVariable int id, @RequestBody Speaker speaker) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        speaker.setId(id);
        return repo.save(speaker);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        repo.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
```

## Database Queries

Spring Data JPA provides methods automatically:

```java
// Built-in
repo.findAll();
repo.findById(id);
repo.save(entity);          // insert or update
repo.deleteById(id);
repo.existsById(id);
repo.count();

// Custom query methods (defined in repository interface)
List<Talk> findByEventId(int eventId);
List<Talk> findByRoomIdAndEventId(int roomId, int eventId);
long countByTalkId(int talkId);
boolean existsByAttendeeIdAndTalkId(int attendeeId, int talkId);

// Custom JPQL
@Query("SELECT t FROM Talk t WHERE t.roomId = :roomId AND t.eventId = :eventId")
List<Talk> findByRoomAndEvent(@Param("roomId") int roomId, @Param("eventId") int eventId);
```

## Returning JSON

Spring Boot automatically serializes return values to JSON.

```java
return ResponseEntity.status(HttpStatus.CREATED).body(entity);  // 201
return ResponseEntity.noContent().build();                       // 204
return ResponseEntity.badRequest().body(Map.of("error", "msg")); // 400
return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "msg")); // 409

// Or throw:
throw new ResponseStatusException(HttpStatus.NOT_FOUND);
throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name required");
throw new ResponseStatusException(HttpStatus.CONFLICT, "Room conflict");
```

## Reading Request Data

```java
// Path variables (from URL pattern /speakers/{id})
@GetMapping("/{id}")
public Speaker get(@PathVariable int id) { ... }

// Query parameters (/rooms?event=1)
@GetMapping
public List<Room> list(@RequestParam int event) { ... }

// JSON request body
@PostMapping
public ResponseEntity<Speaker> create(@RequestBody Speaker speaker) { ... }
```

## Validation

Use Bean Validation annotations on entity fields:

```java
@NotBlank private String name;
@NotNull private Integer capacity;
@Positive private Integer capacity;
```

Add `@Valid` to controller method parameters to trigger validation:

```java
@PostMapping
public ResponseEntity<Speaker> create(@Valid @RequestBody Speaker speaker) { ... }
```

Validation errors automatically return 400.

## App.java Structure

```java
package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```
```

- [ ] **Step 4: Create minimal App.java**

Write `ai-benchmark/spring-template/src/main/java/app/App.java`:

```java
package app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        SpringApplication.run(App.class, args);
    }
}
```

- [ ] **Step 5: Verify it compiles**

```bash
cd ai-benchmark/spring-template
mvn compile -q
```

- [ ] **Step 6: Commit**

```bash
cd ai-benchmark
git add spring-template/
git commit -m "Add Spring Boot template project with CLAUDE.md"
```

---

### Task 6: Create the runner script

**Files:**
- Create: `ai-benchmark/run.sh`

- [ ] **Step 1: Write run.sh**

Write `ai-benchmark/run.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"
SPEC="$SCRIPT_DIR/spec.md"
TESTS="$SCRIPT_DIR/tests"
RUNS=${1:-3}
PORT=8080
MAX_COMPILE_RETRIES=5
MAX_TEST_RETRIES=3

mkdir -p "$RESULTS_DIR"

run_benchmark() {
    local framework=$1
    local run_number=$2
    local template_dir="$SCRIPT_DIR/${framework}-template"
    local work_dir="$SCRIPT_DIR/work/${framework}-run${run_number}"
    local result_file="$RESULTS_DIR/${framework}-run${run_number}.json"

    echo "=== $framework run $run_number ==="

    # Clean slate
    rm -rf "$work_dir"
    cp -r "$template_dir" "$work_dir"

    # Initialize git so Claude Code works
    cd "$work_dir"
    git init -q
    git add -A
    git commit -q -m "initial"

    local total_input_tokens=0
    local total_output_tokens=0
    local compile_attempts=0
    local test_fix_attempts=0
    local start_time=$(date +%s)

    # Step 1: Generate code
    echo "  Generating code..."
    local gen_output
    gen_output=$(claude -p "$(cat "$SPEC")" \
        --output-format json \
        --permission-mode bypassPermissions \
        --model sonnet \
        --max-budget-usd 5 \
        2>/dev/null)

    total_input_tokens=$(echo "$gen_output" | jq -r '.input_tokens // 0')
    total_output_tokens=$(echo "$gen_output" | jq -r '.output_tokens // 0')

    # Step 2: Compile loop
    local compiled=false
    for attempt in $(seq 1 $MAX_COMPILE_RETRIES); do
        compile_attempts=$attempt
        echo "  Compile attempt $attempt..."
        if mvn compile -q 2>/dev/null; then
            compiled=true
            echo "  Compile OK"
            break
        fi

        if [ "$attempt" -eq "$MAX_COMPILE_RETRIES" ]; then
            echo "  Compile failed after $MAX_COMPILE_RETRIES attempts"
            break
        fi

        local errors
        errors=$(mvn compile 2>&1 | tail -50)
        local fix_output
        fix_output=$(claude -p "The project failed to compile. Fix the compilation errors. Here are the errors:

$errors" \
            --output-format json \
            --permission-mode bypassPermissions \
            --model sonnet \
            --max-budget-usd 5 \
            2>/dev/null)

        total_input_tokens=$((total_input_tokens + $(echo "$fix_output" | jq -r '.input_tokens // 0')))
        total_output_tokens=$((total_output_tokens + $(echo "$fix_output" | jq -r '.output_tokens // 0')))
    done

    # Step 3: Package and start app
    local tests_passed=0
    local total_tests=0

    if [ "$compiled" = true ]; then
        echo "  Packaging..."
        mvn package -q -DskipTests 2>/dev/null || true

        echo "  Starting app..."
        local app_pid
        if [ "$framework" = "brace" ]; then
            java -jar target/conference-manager-1.0-SNAPSHOT.jar > /dev/null 2>&1 &
        else
            java -jar target/conference-manager-1.0-SNAPSHOT.jar > /dev/null 2>&1 &
        fi
        app_pid=$!

        # Wait for app to start
        for i in $(seq 1 30); do
            if curl -sf "http://localhost:$PORT/" > /dev/null 2>&1; then
                break
            fi
            sleep 1
        done

        # Step 4: Test loop
        for test_attempt in $(seq 0 $MAX_TEST_RETRIES); do
            echo "  Running tests (attempt $((test_attempt + 1)))..."
            local test_output
            test_output=$(cd "$TESTS" && python -m pytest test_conference.py -v --tb=short 2>&1) || true

            tests_passed=$(echo "$test_output" | grep -oP '\d+(?= passed)' || echo "0")
            total_tests=$(echo "$test_output" | grep -oP '\d+(?= passed)' || echo "0")
            local tests_failed=$(echo "$test_output" | grep -oP '\d+(?= failed)' || echo "0")
            total_tests=$((tests_passed + tests_failed))

            if [ "$tests_failed" = "0" ] && [ "$tests_passed" -gt "0" ]; then
                echo "  All $tests_passed tests passed!"
                break
            fi

            if [ "$test_attempt" -lt "$MAX_TEST_RETRIES" ]; then
                test_fix_attempts=$((test_fix_attempts + 1))
                echo "  $tests_passed/$total_tests tests passed. Sending failures to AI..."

                # Stop app before fix
                kill "$app_pid" 2>/dev/null; wait "$app_pid" 2>/dev/null || true

                local failed_output
                failed_output=$(echo "$test_output" | grep -A5 "FAILED\|ERROR\|AssertionError" | head -80)
                local fix_output
                fix_output=$(cd "$work_dir" && claude -p "Some integration tests are failing against the API. Fix the issues. Here are the test failures:

$failed_output

The test file is at $TESTS/test_conference.py — do not modify the tests, fix the application code." \
                    --output-format json \
                    --permission-mode bypassPermissions \
                    --model sonnet \
                    --max-budget-usd 5 \
                    2>/dev/null)

                total_input_tokens=$((total_input_tokens + $(echo "$fix_output" | jq -r '.input_tokens // 0')))
                total_output_tokens=$((total_output_tokens + $(echo "$fix_output" | jq -r '.output_tokens // 0')))

                # Rebuild and restart
                mvn package -q -DskipTests 2>/dev/null || true
                if [ "$framework" = "brace" ]; then
                    java -jar target/conference-manager-1.0-SNAPSHOT.jar > /dev/null 2>&1 &
                else
                    java -jar target/conference-manager-1.0-SNAPSHOT.jar > /dev/null 2>&1 &
                fi
                app_pid=$!
                for i in $(seq 1 30); do
                    if curl -sf "http://localhost:$PORT/" > /dev/null 2>&1; then
                        break
                    fi
                    sleep 1
                done
            fi
        done

        # Stop app
        kill "$app_pid" 2>/dev/null; wait "$app_pid" 2>/dev/null || true
    fi

    local end_time=$(date +%s)
    local wall_clock=$((end_time - start_time))

    # Write results
    cat > "$result_file" <<EOF
{
    "framework": "$framework",
    "run": $run_number,
    "input_tokens": $total_input_tokens,
    "output_tokens": $total_output_tokens,
    "total_tokens": $((total_input_tokens + total_output_tokens)),
    "compile_attempts": $compile_attempts,
    "compiled": $compiled,
    "test_fix_attempts": $test_fix_attempts,
    "tests_passed": $tests_passed,
    "total_tests": $total_tests,
    "wall_clock_seconds": $wall_clock
}
EOF

    echo "  Results: $(cat "$result_file")"
    echo ""
    cd "$SCRIPT_DIR"
}

# Run benchmarks
for run in $(seq 1 $RUNS); do
    run_benchmark "brace" "$run"
    run_benchmark "spring" "$run"
done

# Summary
echo "=== Summary ==="
echo ""
echo "Brace:"
jq -s '{
    avg_total_tokens: (map(.total_tokens) | add / length),
    avg_compile_attempts: (map(.compile_attempts) | add / length),
    avg_test_fix_attempts: (map(.test_fix_attempts) | add / length),
    avg_tests_passed: (map(.tests_passed) | add / length),
    runs: length
}' "$RESULTS_DIR"/brace-*.json

echo ""
echo "Spring:"
jq -s '{
    avg_total_tokens: (map(.total_tokens) | add / length),
    avg_compile_attempts: (map(.compile_attempts) | add / length),
    avg_test_fix_attempts: (map(.test_fix_attempts) | add / length),
    avg_tests_passed: (map(.tests_passed) | add / length),
    runs: length
}' "$RESULTS_DIR"/spring-*.json
```

- [ ] **Step 2: Make executable**

```bash
chmod +x ai-benchmark/run.sh
```

- [ ] **Step 3: Commit**

```bash
cd ai-benchmark
git add run.sh
git commit -m "Add benchmark runner script"
```

---

### Task 7: End-to-end dry run

**Files:** None (validation only)

- [ ] **Step 1: Verify project structure**

```bash
cd ai-benchmark
find . -type f | grep -v '.git/' | grep -v 'target/' | sort
```

Expected output:
```
./README.md
./brace-template/CLAUDE.md
./brace-template/pom.xml
./brace-template/src/main/java/app/App.java
./run.sh
./spec.md
./spring-template/CLAUDE.md
./spring-template/pom.xml
./spring-template/src/main/java/app/App.java
./spring-template/src/main/resources/application.properties
./tests/requirements.txt
./tests/test_conference.py
```

- [ ] **Step 2: Verify both templates compile**

```bash
cd ai-benchmark/brace-template && mvn compile -q && echo "Brace OK"
cd ai-benchmark/spring-template && mvn compile -q && echo "Spring OK"
```

- [ ] **Step 3: Verify test suite has valid syntax**

```bash
cd ai-benchmark
python -m pytest tests/test_conference.py --collect-only 2>&1 | tail -5
```

Should show ~30 collected tests.

- [ ] **Step 4: Run a single benchmark (1 run, Brace only) to validate the pipeline**

```bash
cd ai-benchmark
# Modify run.sh temporarily or run manually:
./run.sh 1
```

Review the results JSON for correctness.

- [ ] **Step 5: Commit any fixes from dry run**

```bash
git add -A
git commit -m "Fix issues found during dry run"
```
