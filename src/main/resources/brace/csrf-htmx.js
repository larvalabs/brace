// Brace: send the page's CSRF token with every non-GET htmx request.
// Include after htmx, with <meta name="csrf-token" content="..."> in the page (${csrfMeta}).
document.addEventListener('htmx:configRequest', function (event) {
  if (event.detail.verb === 'get') return;
  var meta = document.querySelector('meta[name="csrf-token"]');
  if (meta) event.detail.headers['X-CSRF-Token'] = meta.content;
});
