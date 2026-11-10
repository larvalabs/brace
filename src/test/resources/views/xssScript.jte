@param String x
@param String y
<script id="s1">var a = "${x}";</script>
<script id="s2">const name = '${x}';</script>
<script id="s3">var a = "${x}", b = "${y}";</script>
<script id="s4">foo("${x}");</script>
<script id="s5">let src = "${x}", href = '${y}';</script>
<p id="body">it's a = "5</p><script id="s6">var q = '${x}', r = '${y}';</script>
<p id="text">a = "${x}" and b = '${y}'</p>
<a id="after" href="${x}" title="${y}">x</a>
<button id="click" onclick="go('${x}')">x</button>
