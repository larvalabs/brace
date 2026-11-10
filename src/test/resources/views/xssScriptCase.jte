@param String x
<Script id="m1">foo('${x}')</Script>
<sCRIPT id="m2">var a = "${x}";</sCRIPT>
<Script id="m3">bar('@template.xssCallee(u = x)')</Script>
<p id="after">${x}</p>
<script id="m4">baz('${x}')</script>
