// Brace ops dashboard (/ops/dashboard): the error tabs and the stack-trace toggles.
// Served from /__brace/ops.js so the page carries no inline <script> or on* handler and runs
// under the ops Content-Security-Policy (script-src 'self'). Controls are found by data-*
// attributes through one delegated listener, so they keep working after htmx swaps in fresh
// content every few seconds; the selected tab and open traces are re-applied after each swap
// (the server always renders the Unresolved tab selected and every trace closed).
//
// There is deliberately no credential in here or in the page: htmx polls and posts with the
// httpOnly ops session cookie, which page script cannot read.
(function () {
  'use strict';

  var activeTab = 'unresolved';
  var openTraces = {};

  function traceRow(toggle) {
    var row = toggle.closest('tr');
    return row ? row.nextElementSibling : null;
  }

  function apply() {
    document.querySelectorAll('[data-ops-tab]').forEach(function (tab) {
      var name = tab.getAttribute('data-ops-tab');
      var on = name === activeTab;
      tab.classList.toggle('active', on);
      tab.setAttribute('aria-selected', on ? 'true' : 'false');
      var panel = document.getElementById('tab-' + name);
      if (panel) panel.classList.toggle('hidden', !on);
    });
    document.querySelectorAll('[data-ops-trace]').forEach(function (toggle) {
      var row = traceRow(toggle);
      if (row) row.classList.toggle('hidden', !openTraces[toggle.getAttribute('data-ops-trace')]);
    });
  }

  document.addEventListener('click', function (event) {
    if (!(event.target instanceof Element)) return;
    var tab = event.target.closest('[data-ops-tab]');
    if (tab) {
      activeTab = tab.getAttribute('data-ops-tab');
      apply();
      return;
    }
    var toggle = event.target.closest('[data-ops-trace]');
    if (toggle) {
      var id = toggle.getAttribute('data-ops-trace');
      if (openTraces[id]) delete openTraces[id]; else openTraces[id] = true;
      apply();
    }
  });

  // Re-apply when htmx swaps content in. A MutationObserver rather than an htmx event: its
  // callback runs before the browser paints the swapped-in markup (no flash of a closed trace),
  // and htmx:load only fires after the settle delay. apply() changes only classes and
  // attributes, so observing childList can't loop.
  function start() {
    apply();
    new MutationObserver(apply).observe(document.body, { childList: true, subtree: true });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
