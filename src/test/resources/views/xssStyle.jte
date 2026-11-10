@param String x
<style id="st1">.a { color: ${x}; }</style>
<style id="st2">.b::after { content: "${x}"; }</style>
<Style id="st3">.c-${x} { top: 0 }</Style>
<div id="st4" style="color: ${x}">x</div>
<div id="st5" style='background-image: url("${x}")'>x</div>
<div id="st6" style="${x}">x</div>
<div id="st7" style="width: @template.xssCallee(u = x)">x</div>
<p id="st8" class="${x}">${x}</p>
<form><input type="hidden" name="_csrf" value="secret"></form>
