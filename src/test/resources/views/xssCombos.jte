@param String a
@param String b
@param int n
<a id="ab" href="${a}${b}">x</a>
<a id="scheme" href="${a}://${b}">x</a>
<a id="mailto" href="mailto:${a}">x</a>
<a id="sms" href="sms:${a}">x</a>
<a id="num" href="${n}${b}">x</a>
<a id="quoted" href='/r?x="${b}'>x</a>
<a id="loop" href="@for(var p : java.util.List.of(a, b))${p}@endfor">x</a>
<svg><a id="svga"><set id="set" attributeName="href" to="${b}"/>t</a><animate id="anim" attributeName="href" values="/x;${b}"/></svg>
<button id="vars" hx-get="/r" hx-vars="v: '${b}'">x</button>
<button id="req" hx-get="/r" hx-request='js:{timeout: "${b}"}'>x</button>
<div id="json" hx-vals='{"q": "${b}", "other": "kept"}'></div>
<div id="hdrs" hx-headers='{"X-Q": "${b}"}'></div>
<p id="after">${b}</p>
