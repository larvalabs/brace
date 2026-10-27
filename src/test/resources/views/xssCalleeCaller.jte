@param String u
<a id="c1" href="@template.xssCallee(u = u)">x</a>
<a id="c2" title="@template.xssCallee(u = u)">x</a>
<a id="c3" title='@template.xssCallee(u = u)'>x</a>
<button id="c4" onclick="go('@template.xssCallee(u = u)')">x</button>
<div id="c5" hx-vals='@template.xssCallee(u = u)'></div>
<p id="c6">@template.xssCallee(u = u)</p>
<div id="j1" hx-vals='${u}'></div>
<div id="j2" hx-headers="${u}"></div>
