// Brace: send the page's CSRF token with every non-GET htmx request to the same origin, and
// send no X-CSRF-Token at all with an htmx request to another origin.
// Include after htmx, with <meta name="csrf-token" content="..."> in the page (${csrfMeta}).
//
// Which origin the request goes to is settled when htmx sends it, not when this listener runs:
// another htmx:configRequest listener, registered later, can still rewrite event.detail.path.
// So the header is defined with a getter. htmx reads it when it copies the headers onto the
// request, after every htmx:configRequest listener has run, and skips a header whose value is
// null: the origin checked is the one the request goes to. What this can't see: a listener that
// replaces event.detail.headers with a copy (the copy holds the value as it was then) and
// changes the path afterwards.
//
// A value already in the headers when this listener runs (hx-headers on the element or inherited
// from an ancestor, htmx.ajax's headers, an earlier listener), under any letter case, is not
// sent to another origin either. This script cannot tell a third party's token put there on
// purpose from the app's own token on <body hx-headers>, and markup alone can turn a same-origin
// request into a cross-origin one: an injected <base href> changes where a relative hx-post
// goes, while htmx's own selfRequestsOnly check still resolves it against the page's address.
// On a same-origin request such a value is replaced by the page's token (non-GET) or kept
// (GET, or a page with no csrf-token meta), and sent under the one spelling X-CSRF-Token.
//
// To send an X-CSRF-Token of your choosing to another origin, set it from script, in an
// htmx:configRequest listener added on document after this script is included:
//   document.addEventListener('htmx:configRequest', function (e) {
//     if (e.detail.path.indexOf('https://api.example.com/') === 0) e.detail.headers['X-CSRF-Token'] = value;
//   });
// Assigning the header replaces the getter. A listener on document.body or on the element runs
// before this one, so its value is treated like hx-headers above.
(function () {
  var HEADER = 'X-CSRF-Token';
  var LOWER = HEADER.toLowerCase();

  function sameOrigin(path) {
    try {
      return new URL(path, document.baseURI || location.href).origin === location.origin;
    } catch (e) {
      return false;
    }
  }

  document.addEventListener('htmx:configRequest', function (event) {
    var detail = event.detail;
    var headers = detail.headers;
    // Take out every spelling of the header: HTTP header names are case-insensitive, and a
    // second spelling left in place would be sent beside this one ("A, token").
    var own = null;
    Object.keys(headers).forEach(function (name) {
      if (name.toLowerCase() !== LOWER) return;
      if (own === null && headers[name] != null) own = headers[name];
      delete headers[name];
    });
    var meta = detail.verb === 'get' ? null : document.querySelector('meta[name="csrf-token"]');
    var value = meta ? meta.content : own;
    if (value === null) return;
    Object.defineProperty(headers, HEADER, {
      enumerable: true,
      configurable: true,
      get: function () {
        return sameOrigin(detail.path) ? value : null;
      },
      set: function (replacement) {
        Object.defineProperty(headers, HEADER, { value: replacement, writable: true, enumerable: true, configurable: true });
      }
    });
  });
})();
