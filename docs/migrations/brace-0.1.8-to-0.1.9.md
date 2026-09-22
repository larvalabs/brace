# Migrating from Brace 0.1.8 → 0.1.9

> In progress: 0.1.9 is the current `-SNAPSHOT`. This guide is updated as changes land.

This release extends 0.1.8's named routes to query strings and makes `Url.to` reject
arguments it used to drop silently.

One change **is breaking**, but only for code that was already producing wrong links:

- **`Url.to` throws on surplus path arguments.** A call that passes more path arguments
  than the pattern has `{placeholders}` used to ignore the extras and return a URL that
  looked right. It now throws `IllegalArgumentException`. See "surplus path arguments
  throw" below.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| `Url.to` throws on surplus path arguments | breaking | fix calls that pass extra arguments; pass query params as a record | [§](#breaking-urlto-throws-on-surplus-path-arguments) |
| Query strings from records (`Url.to(name, ..., new MyQuery(...))`) | new-optional | none — additive | [§](#new-optional-query-strings-from-records) |
| 500 errors are buffered before the response is sent | fix | none | [§](#fix-500-errors-are-buffered-before-the-response-is-sent) |

---

## Breaking: `Url.to` throws on surplus path arguments

**What changed.** `Url.to(patternOrName, args...)` already threw when there were too few
arguments for the pattern's `{placeholders}`, but silently ignored extra ones. It now throws
`IllegalArgumentException` in both cases, naming the placeholder count and the argument
count. This applies to route names and literal `/patterns` alike.

**Why.** A dropped argument produces a link that compiles, renders and looks right in
review but points somewhere else. The typical case is trying to pass a filter:
`Url.to(Routes.LIST, collector)` against `/catalog/list` returned `/catalog/list`, the
unfiltered page.

**Who needs to act.** Only code whose `Url.to` calls pass more arguments than the pattern
uses. Calls with the right number of arguments produce byte-identical output. Because the
error is thrown at render time, not startup, exercise your pages (or grep for `Url.to(`)
after upgrading.

**Before (0.1.8) — extra argument silently dropped:**

```java
app.getRead("/catalog/list", catalog::list).name(Routes.LIST);

Url.to(Routes.LIST, collector)       // "/catalog/list" — collector ignored
Url.to("/users/{id}", user.id, tab)  // "/users/42"     — tab ignored
```

**After (0.1.9) — both throw; pass query parameters as a record instead:**

```java
record ListQuery(String collector) {}
Url.to(Routes.LIST, new ListQuery(collector))  // "/catalog/list?collector=0xabc"

record UserQuery(String tab) {}
Url.to("/users/{id}", user.id, new UserQuery(tab))  // "/users/42?tab=posts"
```

If the extra argument was simply a mistake, delete it.

---

## New (optional): query strings from records

**Nothing to do** — purely additive. Before 0.1.9 named routes covered the path but not the
query string, so links with filters were still joined by hand, and every parameter name was
written twice — in the link and in the handler's `req.queryParam("...")`:

**Before (all versions, still works):**

```java
// handler
var collector = req.queryParam("collector");
var page = req.queryParam("page");

// link
Url.to(Routes.LIST) + "?collector=" + URLEncoder.encode(addr, UTF_8) + "&page=" + page
```

**After (0.1.9+) — one record read by the handler and written by links:**

```java
public record ListQuery(String collector, Integer page) {}

// handler — query params bind like form fields
ListQuery query = req.form(ListQuery.class).value();

// links, redirects, templates — a trailing record becomes the query string
Url.to(Routes.LIST, new ListQuery(addr, 2))   // "/catalog/list?collector=0xabc&page=2"
Url.to(Routes.USER_POSTS, user.id, new PostsQuery(2))  // "/users/42/posts?page=2"
```

```html
@import app.queries.ListQuery
<a href="${Url.to(Routes.LIST, new ListQuery(addr, 2))}">Page 2</a>
```

Details:

- Only the **last** argument is treated as a query record. A record anywhere else throws.
  A typed-ID record (`record UserId(long value)`) is not a path value; pass its field
  (`id.value()`).
- Components are written in declaration order and form-encoded (a space becomes `+`), so
  they round-trip through `req.form`.
- `null` and empty-string components are skipped; an all-null record adds no `?`.
  Primitives are always written (`int page` of 0 gives `page=0`), so use boxed types for
  optional parameters.
- Supported component types match what `req.form` reads: `String`, `int`/`Integer`,
  `long`/`Long`, `double`/`Double`, `float`/`Float`, `boolean`/`Boolean`, `BigDecimal`,
  enums, `LocalDate`, `Instant`. Anything else throws, naming the record and component.
- A literal pattern that already contains `?` can't take a query record; move those
  parameters into the record.
- A compact constructor is a good place to normalize (blank → `null`, a default `page=1`
  → `null`), and a `withPage(int)` method keeps pagination links to one call. See
  "Routing → Named routes → Query parameters" in `BRACE-AGENTS.md`.
- A query record's component names are its public URL contract. Renaming a component
  changes the URLs it produces, so old bookmarked links need a redirect.

---

## Fix: 500 errors are buffered before the response is sent

**Nothing to do.** When a handler threw, `BraceHandler` handed the error to a separate
virtual thread for recording and sent the 500 immediately, so a client (or a test) could
see the 500 before the error was in `ErrorStore`'s buffer. The error is now recorded
before the response is sent. Tests that trigger a 500 and then read `/ops/errors` or call
`errorStore().flush()` no longer race.

## Upgrading

Bump `<brace.version>` to `0.1.9` and re-run `brace agents-md` to regenerate
`BRACE-AGENTS.md` from the new jar.
