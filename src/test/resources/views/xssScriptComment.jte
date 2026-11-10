@param String x
@param String y
<script id="w1">
<!--
document.write("<script src='/x.js'></script>");
var b = "${y}"; foo('${x}');
//-->
</script><p id="after1">${x}</p><a id="l1" href="${x}">l</a>
<script id="w2"><!--<script>var a="${x}";</script>var b="${y}";--></script><a id="l2" href="${x}">l</a>
<script id="w3"><!-- var c = "${x}"; --></script><p id="after3">${x}</p>
<script id="w4">var s = "<!--", d = "${x}";</script><p id="after4">${x}</p>
