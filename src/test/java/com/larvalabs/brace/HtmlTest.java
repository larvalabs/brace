package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HtmlTest {

    @Test
    void escapesAllFiveCharacters() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", Html.escape("&<>\"'"));
    }

    @Test
    void escapesAMarkupPayload() {
        assertEquals("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;", Html.escape("<script>alert('x')</script>"));
        assertEquals("&quot; onmouseover=&quot;x", Html.escape("\" onmouseover=\"x"));
    }

    @Test
    void escapesAmpersandOnceNotTwice() {
        assertEquals("a &amp; b &lt; c", Html.escape("a & b < c"));
        assertEquals("&amp;lt;", Html.escape("&lt;"));
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertEquals("Hello, world 123 é中", Html.escape("Hello, world 123 é中"));
    }

    @Test
    void nullAndEmptyBecomeEmpty() {
        assertEquals("", Html.escape(null));
        assertEquals("", Html.escape(""));
    }

    @Test
    void worksWithResultHtml() {
        var result = Result.html("<p>" + Html.escape("<b>hi</b>") + "</p>");
        assertEquals("<p>&lt;b&gt;hi&lt;/b&gt;</p>", result.body());
    }
}
