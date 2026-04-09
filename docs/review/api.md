## Absolutely — here’s a **concrete API redesign proposal** for Brace, optimized for **AI-agent reliability**, not just human aesthetics

My goal here is **not** to make Brace more abstract or more “enterprise.”  
It’s to make it:

- more **regular**
- more **inferable**
- more **hard to misuse**
- more **easy for an agent to generate correctly on the first try**

---

# Design goals

I’d optimize the API around these rules:

1. **One obvious way to do common things**
2. **Capability source is always clear**
3. **Method names encode semantics**
4. **Convenience never hides data origin by default**
5. **Common CRUD and HTTP flows require little freeform string generation**
6. **The compiler should catch wrong assumptions early**

---

# Proposed design rules

## Rule 1: Separate **application dependencies** from **request capabilities**
Use:

- **constructor injection** for app-level dependencies
- **handler parameters** for request-scoped framework capabilities

### Good examples
- `PostController(DatabaseFactory dbFactory, Mailer mailer)` → constructor deps
- `create(Request req, Database db, Session session)` → request-scoped deps

### Avoid
- mixing constructor injection, globals, and `req.*()` access for the same kind of capability

---

## Rule 2: `Request` should expose **source-specific** accessors
This is the single highest-value improvement.

Instead of making `param()` the main API, make it a convenience fallback.

---

## Rule 3: Replace handler casts with named route registration methods
This is mostly about generation reliability.

Instead of:

```java
app.get("/posts", (DbHandler) ctrl::index);
```


prefer:

```java
app.getDb("/posts", ctrl::index);
```


Not fancier. Just easier to generate.

---

## Rule 4: Add constrained DB helpers for common queries
Keep string queries for advanced use, but don’t make them the golden path.

---

## Rule 5: Make the “golden path” painfully obvious
Agents perform best when the framework says:

- “for CRUD pages, do this”
- “for JSON endpoints, do this”
- “for uploads, do this”
- “for auth-required handlers, do this”

---

# Proposed API redesign

# 1. Routing API

## Current shape
You currently have a good base, but typed handler registration relies on casts.

## Proposed shape

```java
public interface AppRoutes {
    Brace get(String path, Handler handler);
    Brace post(String path, Handler handler);
    Brace put(String path, Handler handler);
    Brace delete(String path, Handler handler);
    Brace patch(String path, Handler handler);

    Brace getDb(String path, DbHandler handler);
    Brace postDb(String path, DbHandler handler);
    Brace putDb(String path, DbHandler handler);
    Brace deleteDb(String path, DbHandler handler);

    Brace getSession(String path, SessionHandler handler);
    Brace postSession(String path, SessionHandler handler);

    Brace getFull(String path, FullHandler handler);
    Brace postFull(String path, FullHandler handler);
    Brace putFull(String path, FullHandler handler);
    Brace deleteFull(String path, FullHandler handler);

    Brace getRead(String path, ReadDbHandler handler);
    Brace getReadFull(String path, ReadFullHandler handler);
}
```


## Why this helps
An agent can infer the correct method from the needed capabilities, without remembering cast syntax.

### Example

```java
app.get("/", ctrl::home);
app.getDb("/posts", ctrl::index);
app.getDb("/posts/{id}", ctrl::show);
app.postFull("/posts", ctrl::create);
app.postSession("/login", auth::login);
```


That’s boring in exactly the right way.

---

# 2. Request API redesign

## Current issue
`param()` merges path/query/form, which is concise but ambiguous.

## Proposed shape

```java
public final class Request {
    public String method();
    public String path();

    public String pathParam(String name);
    public int intPathParam(String name);
    public long longPathParam(String name);

    public String queryParam(String name);
    public String queryParam(String name, String defaultValue);
    public Integer queryInt(String name);
    public int queryInt(String name, int defaultValue);
    public Long queryLong(String name);
    public boolean hasQueryParam(String name);

    public String formParam(String name);
    public Integer formInt(String name);
    public boolean hasFormParam(String name);

    public String param(String name); // convenience fallback only

    public String header(String name);
    public boolean hasHeader(String name);

    public String cookie(String name);
    public boolean hasCookie(String name);

    public String body();
    public <T> T bodyAs(Class<T> type);

    public <T> Form<T> form(Class<T> type);

    public UploadedFile file(String name);
    public List<UploadedFile> files(String name);

    public boolean isHtmx();
    public String ip();
}
```


## Preferred usage

```java
int id = req.intPathParam("id");
int page = req.queryInt("page", 1);
String search = req.queryParam("q", "");
String title = req.formParam("title");
```


## Why this helps
It makes generated code:

- more explicit
- easier to test
- easier to review
- less likely to accidentally read from the wrong source

## Keep `param()`?
Yes, but demote it in docs:

> `param()` searches path, query, then form. Use source-specific accessors in application code.

That preserves convenience without making ambiguity the main style.

---

# 3. Introduce typed request parsing helpers

## Proposed additions

```java
public final class Request {
    public <T> T json(Class<T> type);
    public <T> T requireJson(Class<T> type);
    public boolean isJson();
    public boolean isFormPost();
    public boolean isMultipart();
}
```


### Why
Agents often need to branch correctly based on request type. These helpers reduce “manual content-type string logic” in generated code.

---

# 4. Session / request capability access

## Current tension
Some capabilities are handler params, some are on `Request`.

## Proposal
Make **request-scoped framework services** available primarily as handler parameters, not through `Request`.

### Recommended handler capability set
- `Request`
- `Database`
- `Session`
- maybe `Storage`
- maybe `Cache`

If a handler needs storage often enough, give it a proper handler type.

## Example

```java
@FunctionalInterface
public interface StorageHandler {
    Result handle(Request req, Storage storage) throws Exception;
}

@FunctionalInterface
public interface FullStorageHandler {
    Result handle(Request req, Database db, Session session, Storage storage) throws Exception;
}
```


Then:

```java
app.postStorage("/upload", files::upload);
app.postFullStorage("/posts", posts::createWithAttachment);
```


### Alternative if you want to stay smaller
If that feels like too many handler types, then do the opposite:

- keep `Storage` on `Request`
- but **commit hard** to that pattern and don’t also introduce it elsewhere

Either choice is fine. Mixed metaphors are the problem.

---

# 5. Database API redesign

## Current issue
The simple CRUD methods are good, but the query story leans too much on strings.

## Proposed shape

Keep existing methods:

```java
db.find(Post.class, id);
db.insert(post);
db.update(post);
db.delete(post);
db.findAll(Post.class);
db.query(Post.class, "author.id = ?", userId);
```


But add constrained helpers:

```java
db.findBy(Post.class, "slug", slug);
db.findAllBy(Post.class, "authorId", authorId);
db.findAllBy(Post.class, "published", true);
db.countBy(Post.class, "published", true);
db.existsBy(User.class, "email", email);
db.deleteBy(SessionToken.class, "userId", userId);
```


## Suggested signatures

```java
public <T> T findBy(Class<T> type, String field, Object value);
public <T> List<T> findAllBy(Class<T> type, String field, Object value);
public <T> long countBy(Class<T> type, String field, Object value);
public <T> boolean existsBy(Class<T> type, String field, Object value);
public <T> int deleteBy(Class<T> type, String field, Object value);
```


## Why this helps
These methods cover a huge percentage of app code while requiring much less freeform query generation.

An agent is far more likely to generate:

```java
db.findBy(User.class, "email", email);
```


correctly than:

```java
db.queryOne(User.class, "email = ?", email);
```


even though both are simple.

---

## Optional next step: sort/page helpers
Only if you want them.

```java
db.findAll(Post.class, Order.desc("createdAt"));
db.findPage(Post.class, Page.of(page, 20));
db.findAllBy(Post.class, "authorId", authorId, Order.asc("title"));
```


But I would not rush here. Keep it small.

---

# 6. Result API redesign

## Current issue
The split across `Result`, `Json`, `View`, `Redirect` is workable, but you should make the “response grammar” extremely obvious.

## Proposed shape
Keep the helpers, but standardize them as one family:

```java
Result.text("Hello");
Result.html(html);
Result.json(data);
Result.view("posts/index", "posts", posts);
Result.redirect("/login");
Result.notFound();
Result.unauthorized();
Result.forbidden();
Result.badRequest("Missing title");
Result.created("/posts/42");
Result.bytes(bytes, "image/png");
Result.download("report.csv", bytes, "text/csv");
```


## Why
This reduces helper fragmentation.

Then `Json.of(...)`, `View.of(...)`, `Redirect.to(...)` can either:
- become thin aliases, or
- be deprecated over time

### Preferred generated style

```java
return Result.view("posts/show", "post", post);
return Result.json(Map.of("ok", true));
return Result.redirect("/login");
```


One namespace, one pattern.

---

# 7. Form API redesign

## Current shape
Reasonable, but can be a little more regular.

## Proposed usage

```java
var form = req.form(PostForm.class);
if (form.hasErrors()) {
    return Result.view("posts/new", "form", form);
}

var data = form.value();
```


## Recommended API

```java
public interface Form<T> {
    boolean valid();      // keep if you want
    boolean hasErrors();  // preferred
    T value();
    Errors errors();
}
```


I’d prefer `hasErrors()` over requiring everyone to think in double-negative style.

---

# 8. Middleware API redesign

## Current idea
Before/after middleware with optional path scoping is solid.

## Proposed shape
Add named helpers for common patterns.

```java
app.use(Middleware.before(Auth::requireLogin));
app.use("/admin/*", Middleware.before(AdminAuth::requireAdmin));
app.use(Middleware.after(SecurityHeaders::apply));
```


Or even simpler:

```java
app.before(Auth::requireLogin);
app.before("/admin/*", AdminAuth::requireAdmin);
app.after(SecurityHeaders::apply);
```


That’s already fine.

## Suggested addition
Add a clear concept of route-local middleware:

```java
app.getDb("/posts", ctrl::index).before(Auth::requireLogin);
```


If you don’t already have that, it would help generated code stay local instead of forcing global changes for small features.

---

# 9. Jobs API redesign

## Current tension
You have recurring jobs and durable jobs, but the API shape is a bit split.

## Proposed family

```java
app.every("5m", "cleanup", jobs::cleanup);
app.daily("02:00", "digest", jobs::sendDigest);

Jobs.enqueue(db, new SendReceipt(orderId));
Jobs.schedule(db, new SendSurvey(orderId), Duration.ofDays(7));
Jobs.schedule(db, new SendSurvey(orderId), Duration.ofDays(7), JobOptions.maxAttempts(5));
```


## Add if possible

```java
Jobs.run(() -> doSomethingAsync());
Jobs.submit(() -> fetchSomething());
```


You already hinted at this in TODO-land; I agree with it. It fills a practical gap.

## AI ergonomics point
Try to keep all background work under the `Jobs` family, not partly under `app`, partly elsewhere, partly custom scheduler language.

That consistency matters.

---

# 10. Storage API redesign

## Current shape
Reasonable, but I’d make the safe path more obvious.

## Proposed shape

```java
String key = storage.key("photos", file);
String url = storage.put(key, file.bytes(), file.contentType());
```


or:

```java
StoredObject saved = storage.put("photos", file);
saved.key();
saved.url();
```


## Why
Right now the user is likely to write:

```java
"photos/" + file.name()
```


which is not a great default pattern.

## Better API

```java
public interface Storage {
    String put(String key, byte[] bytes, String contentType);
    void delete(String key);
    String url(String key);

    String safeKey(String folder, String originalName);
    String extension(String filename);
}
```


Or even:

```java
public record StoredFile(String key, String url) {}

StoredFile saved = storage.putGenerated("photos", file);
```


That would be very AI-friendly because it makes the safer pattern the easiest one.

---

# 11. Template rendering API redesign

## Current strength
Compiled templates are a win.

## Proposal
Standardize view model passing beyond alternating key/value pairs.

Key/value varargs are compact, but brittle for generated code.

### Safer options

## Option A: named model builder
```java
return Result.view("posts/show",
    Model.of("post", post)
         .put("comments", comments)
         .put("user", user));
```


## Option B: map-based
```java
return Result.view("posts/show", Map.of(
    "post", post,
    "comments", comments,
    "user", user
));
```


## Option C: typed view models
Best for larger apps:

```java
return Result.view("posts/show", new PostShowPage(post, comments, user));
```


### My recommendation
Support all three, but make **Map or typed model** the preferred path once a template takes more than 2 params.

Varargs key/value is agent-friendly up to a point, then becomes mistake-prone.

---

# 12. Route grouping redesign

Your grouping is already good.

I’d just add typed group methods too:

```java
app.group("/admin", admin -> {
    admin.getDb("/users", ctrl::list);
    admin.postFull("/users", ctrl::create);
});
```


This keeps the routing grammar uniform inside groups.

---

# 13. Canonical controller style

This matters more than it sounds.

## Proposed official controller pattern

```java
public final class PostController {
    private final Mailer mailer;

    public PostController(Mailer mailer) {
        this.mailer = mailer;
    }

    public Result index(Request req, Database db) {
        var posts = db.findAll(Post.class);
        return Result.view("posts/index", "posts", posts);
    }

    public Result show(Request req, Database db) {
        var post = db.find(Post.class, req.intPathParam("id"));
        if (post == null) return Result.notFound();
        return Result.view("posts/show", "post", post);
    }

    public Result create(Request req, Database db, Session session) {
        var form = req.form(PostForm.class);
        if (form.hasErrors()) {
            return Result.view("posts/new", "form", form);
        }

        var post = new Post();
        post.apply(form.value());
        post.authorId = session.getInt("userId");
        db.insert(post);

        return Result.redirect("/posts/" + post.id);
    }
}
```


Then in `main()`:

```java
var posts = new PostController(mail);

app.getDb("/posts", posts::index);
app.getDb("/posts/{id}", posts::show);
app.postFull("/posts", posts::create);
```


This is a very strong canonical pattern for agents.

---

# Suggested deprecations

These are things I’d keep for backward compatibility, but gradually demote.

## Deprecate as primary style
- handler casts like `(DbHandler) ctrl::index`
- `req.param()` as the main lookup API
- response helpers split across too many classes
- key/value template varargs for larger model payloads
- raw filename-based storage examples

---

# Concrete “before vs after”

## Routing

### Before
```java
app.get("/posts", (DbHandler) ctrl::index);
app.post("/posts", (FullHandler) ctrl::create);
```


### After
```java
app.getDb("/posts", ctrl::index);
app.postFull("/posts", ctrl::create);
```


---

## Request params

### Before
```java
var id = req.intParam("id");
var page = Integer.parseInt(req.param("page"));
```


### After
```java
var id = req.intPathParam("id");
var page = req.queryInt("page", 1);
```


---

## DB lookup

### Before
```java
var user = db.queryOne(User.class, "email = ?", email);
```


### After
```java
var user = db.findBy(User.class, "email", email);
```


---

## Responses

### Before
```java
return View.of("posts/show", "post", post);
return Json.of(Map.of("ok", true));
return Redirect.to("/login");
```


### After
```java
return Result.view("posts/show", "post", post);
return Result.json(Map.of("ok", true));
return Result.redirect("/login");
```


---

## Storage

### Before
```java
String url = req.storage().put("photos/" + file.name(), file.bytes(), file.contentType());
```


### After
```java
var saved = storage.putGenerated("photos", file);
return Result.json(Map.of("url", saved.url()));
```


---

# Minimal implementation plan

If you want maximum impact with minimal churn, do this in phases.

## Phase 1: additive, no breakage
Add:

- `getDb`, `postDb`, `postFull`, etc.
- `pathParam`, `queryParam`, `formParam`, typed variants
- `Result.json`, `Result.view`, `Result.redirect`
- `db.findBy`, `findAllBy`, `countBy`, `existsBy`
- `Form.hasErrors()`

This gives most of the ergonomics benefits immediately.

---

## Phase 2: docs and examples
Update examples to prefer:

- typed route registration
- source-specific request accessors
- `Result.*` unified responses
- constrained DB helpers
- safe storage key generation

This is crucial. Agents learn from examples more than API signatures.

---

## Phase 3: soft deprecations
Mark older patterns as still supported but no longer preferred:

- handler casts
- `param()`-everywhere style
- fragmented response helper style

---

# My recommended “official Brace style guide”

If I had to write the one-page internal law for Brace, it would be:

## Brace style guide for AI-friendly apps

1. **Wire the app explicitly in `main()`**
2. **Use constructor injection for app services**
3. **Use typed route registration methods**
4. **Use source-specific request accessors**
5. **Use `Result.*` for all responses**
6. **Use constrained DB helpers before freeform query strings**
7. **Use typed forms for user input**
8. **Use typed/Map-based template models once a view has more than 2 values**
9. **Use generated storage keys, never raw client filenames**
10. **Prefer one obvious pattern per feature**

That would make the framework significantly easier for agents to work in.

---

# Final verdict

If you apply just the core redesign pieces, Brace becomes much more than “small and explicit.” It becomes:

> **small, explicit, and mechanically inferable**

That last part is the real prize for AI-agent development.

The best changes are not dramatic architectural shifts. They’re mostly:

- **typed route registration**
- **source-specific request APIs**
- **constrained DB helpers**
- **unified response grammar**
- **safer default storage workflow**

Those changes would give Brace a much tighter API language.

If you want, I can turn this into one of these next:

1. **a concrete proposed Java API surface** with method signatures for the main classes
2. **a migration plan** from the current Brace API to the proposed one
3. **a “golden path” example app** rewritten in the proposed style