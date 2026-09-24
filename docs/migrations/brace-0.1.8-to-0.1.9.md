# Migrating from Brace 0.1.8 → 0.1.9

> In progress: 0.1.9 is the current `-SNAPSHOT`. This guide is updated as changes land.

This release finishes what 0.1.8's named routes started: query strings and path values are
encoded for you, and a pagination helper replaces hand-built pagers.

Three changes **are breaking**. Each one only affects code that was already producing a
wrong or broken link, or that decoded path parameters itself:

- **`Url.to` throws on surplus path arguments** instead of silently dropping them.
- **`Url.to` encodes path values**, and throws for a value that can't be a single path
  segment (`/`, `%`, empty, `.`, `..`).
- **Path parameters are percent-decoded** before your handler sees them.

## Index

| Change | Type | Action required | Anchor |
|---|---|---|---|
| `Url.to` throws on surplus path arguments | breaking | fix calls that pass extra arguments | [§](#breaking-urlto-throws-on-surplus-path-arguments) |
| `Url.to` encodes path values; unroutable values throw | breaking | pass `/`/`%` values as query params | [§](#breaking-urlto-encodes-path-values) |
| Path parameters are decoded | breaking | remove hand-written `URLDecoder.decode(req.pathParam(...))` | [§](#breaking-path-parameters-are-decoded) |
| Query strings: `Url.query(...)` | new-optional | replace `Url.to(...) + "?k=" + v` | [§](#new-optional-query-strings-with-urlquery) |
| Pagination: `db.paginate`, `Paged`, `req.urlWith` | new-optional | replace hand-built pagers | [§](#new-optional-pagination-with-paged) |
| 500 errors are buffered before the response is sent | fix | none | [§](#fix-500-errors-are-buffered-before-the-response-is-sent) |

---

## Breaking: `Url.to` throws on surplus path arguments

**What changed.** `Url.to(patternOrName, args...)` already threw when there were too few
arguments for the pattern's `{placeholders}`, but silently ignored extra ones. It now throws
`IllegalArgumentException` in both cases. This applies to route names and literal
`/patterns` alike.

**Why.** A dropped argument produces a link that looks right but points somewhere else. The
typical case is trying to pass a filter: `Url.to(Routes.LIST, collector)` against
`/catalog/list` returned `/catalog/list`, the unfiltered page.

**Who needs to act.** Only code whose `Url.to` calls pass more arguments than the pattern
uses. The error is thrown at render time, not startup, so exercise your pages (or grep for
`Url.to(`) after upgrading.

**Before (0.1.8), extra argument silently dropped:**

```java
Url.to(Routes.LIST, collector)       // "/catalog/list", collector ignored
Url.to("/users/{id}", user.id, tab)  // "/users/42", tab ignored
```

**After (0.1.9), both throw.** Pass query parameters with `Url.query` (below), or delete the
extra argument if it was a mistake:

```java
Url.to(Routes.LIST, Url.query("collector", collector))  // "/catalog/list?collector=0xabc"
Url.to("/users/{id}", user.id, Url.query("tab", tab))   // "/users/42?tab=posts"
```

---

## Breaking: `Url.to` encodes path values

**What changed.** Path values used to be inserted with `toString()` as-is, so
`Url.to(Routes.TAG, "red hat")` produced `/tags/red hat` and `"a?b"` produced a URL whose
path ended at `a`. Values are now percent-encoded (`/tags/red%20hat`). Letters, digits and
`-._~!$&'()*+,=:@` stay literal, so ids, slugs and hex addresses produce the same URLs as
before. Enums are written by `name()`.

A value containing `/` or `%`, or that is empty, `.` or `..`, now throws. Jetty rejects
`%2F` and `%25` in paths with a 400, and the others can't be a single segment, so these
links never reached the route anyway.

**Who needs to act.** Only code that puts such values in a path. Move them to a query
parameter:

```java
// Before: "/files/docs/a.txt" -> 404, the route is /files/{name}
Url.to(Routes.FILE, "docs/a.txt")
// After
app.get("/files", files::show).name(Routes.FILE);
Url.to(Routes.FILE, Url.query("path", "docs/a.txt"))   // "/files?path=docs%2Fa.txt"
```

---

## Breaking: path parameters are decoded

**What changed.** Jetty hands Brace the request path still encoded, and Brace passed path
parameters through unchanged: `GET /tags/red%20hat` gave `req.pathParam("name")` ==
`"red%20hat"`. Path parameters are now percent-decoded, so it gives `"red hat"`, which is
what `Url.to` encoded. This is path decoding, not form decoding: `+` stays a literal `+`. A
malformed escape (`%zz`) is left as it arrived.

**Who needs to act.** Only handlers that decoded path parameters themselves. They would now
decode twice (a value containing `%25` would be decoded to a different string). Parameters
without `%` escapes (numeric ids, plain slugs) are unchanged.

**Before (0.1.8):**

```java
var name = URLDecoder.decode(req.pathParam("name"), StandardCharsets.UTF_8);
```

**After (0.1.9):**

```java
var name = req.pathParam("name");
```

Note that `URLDecoder` also turned `+` into a space, which is wrong for paths. If your app
relied on that, links built with `Url.to` now produce `%20` for spaces instead.

---

## New (optional): query strings with `Url.query`

**Nothing to do.** 0.1.8's named routes covered the path but not the query string, so links
with filters were joined by hand and usually not encoded (a `q` containing `&` or `#`
produced a broken link). Pass `Url.query(name, value, ...)` as the last argument of
`Url.to`:

**Before (all versions, still works):**

```java
Url.to(Routes.LIST) + "?project=" + slug
Url.to(Routes.ADMIN_LOGIN) + "?next=" + URLEncoder.encode(next, UTF_8)
```

**After (0.1.9+):**

```java
Url.to(Routes.LIST, Url.query("project", slug))
Url.to(Routes.ADMIN_LOGIN, Url.query("next", next))
Url.to(Routes.LIST, Url.query("project", slug, "collector", addr, "q", q))  // nulls left out
Url.to(Routes.LIST, Url.query("tag", List.of("a", "b")))                  // tag=a&tag=b
```

```html
<a href="${Url.to(Routes.LIST, Url.query("project", "meebits"))}">Catalog</a>
```

Details:

- Values are form-encoded (a space becomes `+`) and read back as usual with
  `req.queryParam`, `req.queryParams(name)` or `req.form(Record.class)`.
- `null` and empty values are left out, so optional filters need no `if`s.
- A collection value adds one pair per element; enums are written by `name()`.
- `Url.query(...)` must be the last argument of `Url.to`.

To keep the current query and change one parameter (sort or filter toggles), use
`req.urlWith(name, value)`: on `/posts?tag=java&sort=date`, `req.urlWith("sort", "name")`
gives `/posts?tag=java&sort=name`, and a `null` value removes the parameter.

---

## New (optional): pagination with `Paged`

**Nothing to do.** `db.queryPage` + `db.count` still work. Before 0.1.9 each app wrote its
own page math, page-link strip and "next page with the same filters" URLs. `db.paginate`
returns a `Paged<T>` with all of that:

**Before (all versions, still works):**

```java
int page = Math.max(1, req.queryInt("page", 1));
long total = db.count(Post.class, "tag = ?", tag);
int totalPages = (int) Math.max(1, Math.ceil((double) total / 20));
page = Math.min(page, totalPages);
var posts = db.queryPage(Post.class, "tag = ? ORDER BY createdAt DESC", 20, (page - 1) * 20, tag);
var pageLinks = buildPageLinks(page, totalPages,
    p -> Url.to(Routes.POSTS) + "?tag=" + tag + (p > 1 ? "&page=" + p : ""));
return View.of("posts/index", "posts", posts, "page", page, "totalPages", totalPages, "pageLinks", pageLinks);
```

**After (0.1.9+):**

```java
var posts = db.paginate(Post.class, "tag = ? ORDER BY createdAt DESC", req, 20, tag);
return View.of("posts/index", "posts", posts);
```

```html
@import com.larvalabs.brace.Paged
@param Paged<Post> posts

@for(var post : posts.items()) ... @endfor
@if(posts.hasPrev())<a href="${posts.prevUrl()}">Prev</a>@endif
@for(var link : posts.links())
  @if(link.gap())…@elseif(link.current())<b>${link.label()}</b>@else<a href="${link.url()}">${link.label()}</a>@endif
@endfor
@if(posts.hasNext())<a href="${posts.nextUrl()}">Next</a>@endif
```

Details:

- The page number comes from `?page=`. Missing or unparseable input is page 1, and a page
  past the end is clamped to the last page.
- The total is counted from the same query with `ORDER BY` removed, so the where clause is
  written once. A zero count skips the fetch.
- Links are the current URL with only `page` changed, so filters and sort order in the query
  string carry over. Page 1 leaves the parameter out. The URL is recorded when the `Paged` is
  built, so templates call `links()`, `prevUrl()` and `nextUrl()` with no arguments.
- `links()` is the first and last page plus two either side of the current one, with
  `Paged.Link.gap()` entries where pages are skipped. `links(n)` changes the spread.
- `Paged.slice(list, req, perPage)` pages an in-memory list.
  `Paged.of(items, page, perPage, total).linkedTo(req)` wraps a page you fetched yourself.
- `db.paginate(type, where, page, perPage, params...)` takes an explicit page for JSON APIs;
  `Result.json(paged)` gives `{items, page, perPage, totalCount, totalPages}`.
- If you had a shared `Page` query record carrying `?page=`, delete it. `req.form(Page.class)`
  and `Page.of(n)` links are replaced by `db.paginate(..., req, ...)` and `paged.url(n)`.

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
