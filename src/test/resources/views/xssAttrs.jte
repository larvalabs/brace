@param String u
<a id="a" href="${u}">a</a>
<a id="prefixed" href="/users/${u}">p</a>
<img id="img" src="${u}">
<iframe id="iframe" src="${u}"></iframe>
<form id="form" action="${u}"><button id="btn" formaction="${u}">b</button></form>
<div id="hxget" hx-get="${u}"></div>
<div id="dhxpost" data-hx-post="${u}"></div>
<svg><a id="xlink" xlink:href="${u}"></a></svg>
<button id="onclick" onclick="go('${u}')">x</button>
<button id="hxon" hx-on:click="go('${u}')">x</button>
<button id="hxondash" hx-on-click="go('${u}')">x</button>
<button id="dhxon" data-hx-on:click="go('${u}')">x</button>
<div id="vals" hx-vals='js:{v: "${u}"}'></div>
<iframe id="srcdoc" srcdoc="${u}"></iframe>
<p id="text">${u}</p>
<input id="value" value="${u}">
