@param String a
@param String b
<div id="v1" hx-vals='${a}'></div>
<div id="v2" hx-vals=' ${a}'></div>
<div id="v3" hx-vals='${a}${b}'></div>
<div id="v4" hx-headers="${a}${b}"></div>
<div id="v5" data-hx-vals=' ${a} '></div>
<div id="v6" hx-vals='@template.xssCallee(u = a)@template.xssCallee(u = b)'></div>
<div id="v7" hx-headers="j${b}"></div>
<div id="k1" hx-vals='js:{v: "${a}"}'></div>
<div id="k2" hx-vals='{"q": "${a}"}'></div>
<div id="k3" hx-vals='{"q": "${a}", "r": "${b}"}'></div>
