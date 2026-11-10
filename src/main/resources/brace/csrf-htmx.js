// Brace: send the page's CSRF token with every non-GET htmx request to the same origin.
// Include after htmx, with <meta name="csrf-token" content="..."> in the page (${csrfMeta}).
//
// Which origin the request goes to is settled when htmx sends it, not when this listener runs:
// another htmx:configRequest listener, registered later, can still rewrite event.detail.path.
// So the header is defined with a getter. htmx reads it when it copies the headers onto the
// request, after every htmx:configRequest listener has run, and skips a header whose value is
// null: the origin checked is the one the request goes to. What this can't see: a listener that
// replaces event.detail.headers with a copy (the copy holds the value as it was then) and
// changes the path afterwards. A value the page set itself before this listener ran
// (hx-headers='{"X-CSRF-Token": ...}', htmx.ajax's headers, an earlier listener) is kept and sent
// on a cross-origin request; a same-origin one gets the page's token. A later listener that sets
// the header replaces the getter.
(function () {
  var HEADER = 'X-CSRF-Token';

  function sameOrigin(path) {
    try {
      return new URL(path, document.baseURI || location.href).origin === location.origin;
    } catch (e) {
      return false;
    }
  }

  document.addEventListener('htmx:configRequest', function (event) {
    var detail = event.detail;
    if (detail.verb === 'get') return;
    var meta = document.querySelector('meta[name="csrf-token"]');
    if (!meta) return;
    var token = meta.content;
    var headers = detail.headers;
    var own = Object.prototype.hasOwnProperty.call(headers, HEADER) ? headers[HEADER] : null;
    Object.defineProperty(headers, HEADER, {
      enumerable: true,
      configurable: true,
      get: function () {
        return sameOrigin(detail.path) ? token : own;
      },
      set: function (value) {
        Object.defineProperty(headers, HEADER, { value: value, writable: true, enumerable: true, configurable: true });
      }
    });
  });
})();
