// Brace: send the page's CSRF token with every non-GET htmx request to the same origin.
// Include after htmx, with <meta name="csrf-token" content="..."> in the page (${csrfMeta}).
document.addEventListener('htmx:configRequest', function (event) {
  if (event.detail.verb === 'get') return;
  var meta = document.querySelector('meta[name="csrf-token"]');
  if (!meta) return;
  var sameOrigin = false;
  try {
    sameOrigin = new URL(event.detail.path, document.baseURI || location.href).origin === location.origin;
  } catch (e) {
    sameOrigin = false;
  }
  if (sameOrigin) event.detail.headers['X-CSRF-Token'] = meta.content;
});
