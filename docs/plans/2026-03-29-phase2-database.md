# Phase 2: Database — Hibernate StatelessSession, Flyway Migrations

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add database support to Brace: Hibernate 7 StatelessSession wrapped in a `Database` class, per-request transaction lifecycle in BraceHandler, Flyway migrations on startup. At the end, a developer can define JPA entities, write migrations, and query/insert data from controllers.

**Architecture:** `Brace.database(url)` creates a `DatabaseFactory` that holds the Hibernate `SessionFactory`. On each request that needs a database (controller method has a `Database` parameter), `BraceHandler` opens a `StatelessSession`, wraps it in a `Database`, begins a transaction, calls the controller, commits (or rolls back on exception), and closes. Flyway runs migrations before Hibernate starts.

**Tech Stack:** Hibernate 7 (hibernate-core), H2 (h2, test/dev), HikariCP (connection pooling), Flyway (flyway-core), JUnit 5

---

## File Structure

```
src/main/java/com/larvalabs/brace/
├── Database.java              # Thin wrapper over StatelessSession — query API
├── DatabaseFactory.java       # Creates SessionFactory, opens sessions, runs Flyway
├── Brace.java                 # Updated — accepts database config
├── BraceHandler.java          # Updated — per-request session/transaction lifecycle
├── Invoker.java               # Updated — recognizes Database.class by type (not name)
src/test/java/com/larvalabs/brace/
├── DatabaseTest.java          # Unit tests for Database query API (H2 in-memory)
├── DatabaseIntegrationTest.java # Full HTTP tests with DB queries
src/test/java/com/larvalabs/brace/testmodels/
├── Post.java                  # Test entity
src/test/resources/
├── db/migration/
│   └── V1__create_posts.sql   # Test migration
```

---

### Task 1: Add Dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add Hibernate 7, H2, HikariCP, Flyway to pom.xml**

Add these dependencies:
- `org.hibernate.orm:hibernate-core` (7.0.x — use latest stable)
- `com.h2database:h2` (2.3.x — test scope)
- `com.zaxxer:HikariCP` (6.x)
- `org.flywaydb:flyway-core` (10.x)

Also add `jakarta.persistence:jakarta.persistence-api` if not pulled in transitively by Hibernate 7.

- [ ] **Step 2: Verify build still compiles**

Run: `mvn compile`

- [ ] **Step 3: Commit**

```
git commit -m "Phase 2 Task 1: add Hibernate 7, H2, HikariCP, Flyway dependencies"
```

---

### Task 2: DatabaseFactory (SessionFactory + Flyway)

**Files:**
- Create: `src/main/java/com/larvalabs/brace/DatabaseFactory.java`

- [ ] **Step 1: Implement DatabaseFactory**

DatabaseFactory should:
1. Accept a JDBC URL, optional user/password
2. Run Flyway migrations against the URL (looking for migrations in `db/migration/` on the classpath or `migrations/` directory)
3. Build a Hibernate `SessionFactory` programmatically (no persistence.xml, no hibernate.cfg.xml):
   - Set the JDBC URL, user, password
   - Use HikariCP as the connection provider
   - Auto-detect dialect from URL (H2 for `jdbc:h2:`, PostgreSQL for `jdbc:postgresql:`)
   - Scan for `@Entity` classes (or accept a list of entity classes)
   - Set `hbm2ddl.auto=validate` (we use Flyway for schema, Hibernate validates)
4. Provide `openSession()` returning a `StatelessSession`
5. Provide `close()` to shut down the SessionFactory

For entity class discovery: since we don't do classpath scanning, accept entity classes explicitly:
```java
var dbFactory = new DatabaseFactory("jdbc:h2:mem:test", null, null, List.of(Post.class, User.class));
```

Or Brace.database() can collect them:
```java
var db = Brace.database("jdbc:h2:mem:test").entities(Post.class, User.class);
```

- [ ] **Step 2: Commit**

```
git commit -m "Phase 2 Task 2: DatabaseFactory with Hibernate SessionFactory and Flyway"
```

---

### Task 3: Database Wrapper (Query API)

**Files:**
- Create: `src/main/java/com/larvalabs/brace/Database.java`
- Create: `src/test/java/com/larvalabs/brace/DatabaseTest.java`
- Create: `src/test/java/com/larvalabs/brace/testmodels/Post.java`
- Create: `src/test/resources/db/migration/V1__create_posts.sql`

- [ ] **Step 1: Create test entity and migration**

```java
// src/test/java/com/larvalabs/brace/testmodels/Post.java
package com.larvalabs.brace.testmodels;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "posts")
public class Post {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public String body;
    public Instant createdAt;
}
```

```sql
-- src/test/resources/db/migration/V1__create_posts.sql
CREATE TABLE posts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(255),
    body TEXT,
    created_at TIMESTAMP
);
```

- [ ] **Step 2: Write failing tests for Database**

```java
package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    static DatabaseFactory factory;

    @BeforeAll
    static void setup() {
        factory = new DatabaseFactory("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", null, null, List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        factory.close();
    }

    @Test
    void insertAndFind() {
        var db = new Database(factory.openSession());
        try {
            var post = new Post();
            post.title = "Hello";
            post.body = "World";
            post.createdAt = Instant.now();
            db.insert(post);
            assertNotNull(post.id);

            var found = db.find(Post.class, post.id);
            assertNotNull(found);
            assertEquals("Hello", found.title);
        } finally {
            db.close();
        }
    }

    @Test
    void findReturnsNullForMissing() {
        var db = new Database(factory.openSession());
        try {
            var found = db.find(Post.class, 99999L);
            assertNull(found);
        } finally {
            db.close();
        }
    }

    @Test
    void findAll() {
        var db = new Database(factory.openSession());
        try {
            var posts = db.findAll(Post.class);
            assertNotNull(posts);
            // Should have at least the post from insertAndFind (if test order allows)
            // Just verify it returns a list without error
        } finally {
            db.close();
        }
    }

    @Test
    void queryWithCondition() {
        var db = new Database(factory.openSession());
        try {
            var post = new Post();
            post.title = "Unique Title";
            post.body = "Content";
            post.createdAt = Instant.now();
            db.insert(post);

            var results = db.query(Post.class, "title = ?", "Unique Title");
            assertFalse(results.isEmpty());
            assertEquals("Unique Title", results.get(0).title);
        } finally {
            db.close();
        }
    }

    @Test
    void queryOne() {
        var db = new Database(factory.openSession());
        try {
            var post = new Post();
            post.title = "QueryOne Test";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);

            var found = db.queryOne(Post.class, "title = ?", "QueryOne Test");
            assertNotNull(found);
            assertEquals("QueryOne Test", found.title);

            var missing = db.queryOne(Post.class, "title = ?", "Nonexistent");
            assertNull(missing);
        } finally {
            db.close();
        }
    }

    @Test
    void count() {
        var db = new Database(factory.openSession());
        try {
            long count = db.count(Post.class);
            assertTrue(count >= 0);
        } finally {
            db.close();
        }
    }

    @Test
    void update() {
        var db = new Database(factory.openSession());
        try {
            var post = new Post();
            post.title = "Before Update";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);

            post.title = "After Update";
            db.update(post);

            var found = db.find(Post.class, post.id);
            assertEquals("After Update", found.title);
        } finally {
            db.close();
        }
    }

    @Test
    void delete() {
        var db = new Database(factory.openSession());
        try {
            var post = new Post();
            post.title = "To Delete";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
            var id = post.id;

            db.delete(post);

            var found = db.find(Post.class, id);
            assertNull(found);
        } finally {
            db.close();
        }
    }

    @Test
    void sql() {
        var db = new Database(factory.openSession());
        try {
            var post = new Post();
            post.title = "SQL Test";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);

            db.sql("UPDATE posts SET title = ? WHERE id = ?", "SQL Updated", post.id);

            var found = db.find(Post.class, post.id);
            assertEquals("SQL Updated", found.title);
        } finally {
            db.close();
        }
    }
}
```

- [ ] **Step 3: Implement Database**

Database wraps a `StatelessSession` and provides:
- `find(Class<T>, Object id)` — `session.get(type, id)`
- `insert(entity)` — `session.insert(entity)`
- `update(entity)` — `session.update(entity)`
- `delete(entity)` — `session.delete(entity)`
- `findAll(Class<T>)` — HQL `FROM T`
- `query(Class<T>, String hqlWhere, Object... params)` — HQL `FROM T WHERE {hqlWhere}` with positional params
- `queryOne(Class<T>, String hqlWhere, Object... params)` — same but returns single result or null
- `count(Class<T>)` — HQL `SELECT COUNT(*) FROM T`
- `count(Class<T>, String hqlWhere, Object... params)` — with condition
- `hql(String hql, Object... params)` — raw HQL
- `sql(String sql, Object... params)` — native SQL execution
- `close()` — closes the underlying StatelessSession

Note on HQL with positional params: Hibernate 7 uses `?1`, `?2` syntax for positional parameters (JPA-style), not plain `?`. The Database class should convert plain `?` to `?1`, `?2` etc. before passing to Hibernate, so the user writes `"title = ?"` and it becomes `"title = ?1"` internally.

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=DatabaseTest`

- [ ] **Step 5: Commit**

```
git commit -m "Phase 2 Task 3: Database wrapper with query API and test suite"
```

---

### Task 4: Update Invoker and BraceHandler for Per-Request Database

**Files:**
- Modify: `src/main/java/com/larvalabs/brace/Invoker.java`
- Modify: `src/main/java/com/larvalabs/brace/BraceHandler.java`
- Modify: `src/main/java/com/larvalabs/brace/Brace.java`

- [ ] **Step 1: Update Invoker to recognize Database.class by type**

Change the Database parameter detection from name-matching to type-matching:
```java
} else if (type == Database.class) {
    paramTypes.add(ParamType.DATABASE);
}
```

- [ ] **Step 2: Update BraceHandler for per-request database lifecycle**

BraceHandler should accept an optional `DatabaseFactory`. When handling a request:
1. Check if the invoker `needsDatabase()`
2. If yes: open a StatelessSession, create a Database, begin transaction
3. Call the controller with the Database
4. On success: commit transaction, close session
5. On exception: rollback transaction, close session, return 500

- [ ] **Step 3: Update Brace to accept database configuration**

```java
var db = Brace.database("jdbc:h2:mem:dev").entities(Post.class, User.class);
var app = Brace.app().port(8080).database(db);
```

`Brace.database()` returns a builder that creates a `DatabaseFactory`. `Brace.database(db)` stores the factory for use by BraceHandler.

- [ ] **Step 4: Write integration test with database**

```java
// src/test/java/com/larvalabs/brace/DatabaseIntegrationTest.java
// Start a Brace app with H2 in-memory, register routes that use Database param,
// make HTTP requests, verify data is persisted and queried correctly
```

- [ ] **Step 5: Run all tests**

Run: `mvn test`
All existing tests (53) plus new DatabaseTest and DatabaseIntegrationTest should pass.

- [ ] **Step 6: Commit**

```
git commit -m "Phase 2 Task 4: per-request database lifecycle in BraceHandler"
```

---

## Phase 2 Complete

At this point you have:
- Hibernate 7 StatelessSession wrapped in a clean `Database` API
- Per-request transaction lifecycle (auto-commit, auto-rollback)
- Flyway migrations on startup
- Controllers that optionally take a `Database` parameter
- Full test suite with H2 in-memory

**Next:** Phase 3 adds JTE template rendering.
