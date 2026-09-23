# Brace 0.1.9 release notes

> Draft: 0.1.9 is in progress. Source of truth for every item (with before/after examples)
> is `docs/migrations/brace-0.1.8-to-0.1.9.md`.

---

Brace 0.1.9 extends named routes to query strings and makes `Url.to` reject arguments it
used to drop silently.

## Routing

- **Query strings from records.** Records passed after the path arguments of `Url.to`
  become the query string: `Url.to(Routes.LIST, new ListQuery("punks"), Page.of(2))` →
  `/catalog/list?project=punks&page=2`. The handler reads the same records with
  `req.form(ListQuery.class)`, so parameter names are written once and an IDE rename
  updates the handler and every link together. Several records combine, so a concern like
  pagination can be one record shared by every route. Nulls are skipped, values are
  form-encoded, and component types are limited to those `req.form` reads back.
- **`Url.to` throws on surplus path arguments.** Extra arguments used to be ignored,
  producing a link that looked right but dropped a value (typically a filter). Too few and
  too many now both throw `IllegalArgumentException`. Calls with the right number of
  arguments are unchanged.

## Fixes

- **500 errors are recorded before the response is sent**, so a client or test that
  reads `/ops/errors` right after a 500 always finds it.

## Upgrading

Read `docs/migrations/brace-0.1.8-to-0.1.9.md`. One change is breaking, and only for code
that was already producing wrong links:

1. **`Url.to` throws on surplus path arguments.** Delete the extra argument, or pass query
   parameters as a trailing record.

After bumping `<brace.version>`, finish with `brace agents-md` to refresh your project's
framework docs.
