@param String x
@param String y
<a id="s1" title="x a = 'b" href="${x}://${y}">x</a>
<a id="s2" data-note='say "href=' href="${x}://${y}">x</a>
<a id="s3" title="1 > 0" href="${x}://${y}">x</a>
<a id="s4" class="c"href="${x}://${y}">x</a>
<img id="s5" alt="it's a = " src="${x}:${y}">
<a id="s6" title="${y} @template.xssCallee(u = x)">x</a>
<a id="s7" href = "${x}://${y}">x</a>
<a id="s8" title="a" data-x="b = '" href='${x}://${y}'>x</a>
<p id="s9">a href="${x}://${y}"</p>
<a id="e1" href="&#32;${x}">x</a>
<a id="e2" href="&#x20;&#9;${x}">x</a>
<a id="e3" href="&Tab;&NewLine;${x}">x</a>
<a id="e4" href="&#106;avascript&colon;go(${y})">x</a>
<a id="e5" href="&#${y};${x}">x</a>
<a id="e6" href="&#32${x}">x</a>
<a id="k1" href="/search?a=1&amp;b=${x}">x</a>
<a id="k2" href="/search?a=1&b=${x}">x</a>
<a id="k3" href="&#47;users/${x}">x</a>
<a id="k4" href="mailto&colon;${x}">x</a>
<a id="k5" href="&copy;${x}">x</a>
<div id="h1" hx-vals='&#106;${x}'></div>
<div id="h2" hx-vals='&nbsp;${x}'></div>
<div id="h3" hx-vals='&ensp;&#xfeff;${x}'></div>
