# Brace 0.1.9 release notes

> Draft: 0.1.9 is in progress. Source of truth for every item (with before/after examples)
> is `docs/migrations/brace-0.1.8-to-0.1.9.md`.

---

Brace 0.1.9 adds query strings and encoding to `Url.to`, and a pagination helper.

## Routing

- **Query strings with `Url.query`.** Pass `Url.query(name, value, ...)` as the last
  argument of `Url.to`: `Url.to(Routes.LIST, Url.query("project", slug, "q", q))` →
  `/catalog/list?project=punks&q=red+hat`. Values are encoded, `null` and empty values are
  left out, and a collection value repeats the name.
- **`req.urlWith(name, value)`** returns the current URL with one query parameter changed,
  for sort and filter links that keep the rest of the query. **`req.url()`** returns the
  current path and query, for `next=` return addresses.
- **Path values are encoded and path parameters decoded**, so `Url.to(Routes.TAG, "red
  hat")` gives `/tags/red%20hat` and the handler's `req.pathParam("name")` gives `"red
  hat"`. Values that can't be a single path segment (`/`, `%`, empty, `.`, `..`) throw.
- **`Url.to` throws on surplus path arguments.** Extra arguments used to be ignored,
  producing a link that looked right but dropped a value (typically a filter).

## Database

- **Pagination.** `db.paginate(Post.class, "... ORDER BY ...", req, 20)` returns a
  `Paged<Post>` with the page's rows, `page()`, `totalPages()`, `totalCount()`, and
  `links()`/`prevUrl()`/`nextUrl()` built from the current URL, so filters carry over.
  The page comes from `?page=` and is clamped; the count is derived from the same query.
  `paged.map(fn)` converts entities to view records or DTOs, `Paged.slice(list, req,
  perPage)` pages an in-memory list, and `Paged` serializes to JSON.

## Fixes

- **500 errors are recorded before the response is sent**, so a client or test that
  reads `/ops/errors` right after a 500 always finds it.

## Upgrading

Read `docs/migrations/brace-0.1.8-to-0.1.9.md`. Three changes are breaking, each only for
code that was already producing wrong links or decoding path parameters itself:

1. **`Url.to` throws on surplus path arguments.** Delete the extra argument, or pass query
   parameters with `Url.query(...)`.
2. **`Url.to` throws for path values containing `/` or `%`, or empty.** Pass them as query
   parameters.
3. **Path parameters are decoded.** Remove any `URLDecoder.decode(req.pathParam(...))`.

After bumping `<brace.version>`, finish with `brace agents-md` to refresh your project's
framework docs.
