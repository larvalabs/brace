@param String a
@param String b
<div id="r1" hx-request='${a}'></div>
<div id="r2" data-hx-request="${a}"></div>
<div id="r3" hx-request=' ${a}'></div>
<div id="r4" hx-request='${a}${b}'></div>
<div id="r5" hx-request='&#32;${a}'></div>
<div id="k1" hx-request='{"timeout": ${a}}'></div>
<div id="k2" hx-request='js:{timeout: "${a}"}'></div>
<div id="k3" hx-request='{"credentials": "${a}"}'></div>
