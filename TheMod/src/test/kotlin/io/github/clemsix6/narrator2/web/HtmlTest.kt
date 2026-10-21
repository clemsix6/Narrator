package io.github.clemsix6.narrator2.web

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlTest {
    private fun html(body: Html.() -> Unit): String = StringBuilder().also { Html(it).body() }.toString()

    @Test
    fun `text is escaped, whatever it holds`() {
        assertEquals("&lt;img src=x onerror=alert(1)&gt;", escape("<img src=x onerror=alert(1)>"))
        assertEquals("&quot;&gt;&lt;b&gt;bold&lt;/b&gt;", escape("\"><b>bold</b>"))
        assertEquals("Tom &amp; Jerry&#39;s", escape("Tom & Jerry's"))
        assertEquals("a�b", escape("a\u0000b"))
        assertEquals("Bartok, ta pioche pleure.", escape("Bartok, ta pioche pleure."))
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", html { p("<script>alert(1)</script>") })
        assertEquals("<td>&lt;b&gt;</td>", html { element("td") { text("<b>") } })
    }

    @Test
    fun `attribute values are escaped, quotes included`() {
        val out = html { element("input", "type" to "text", "name" to "q", "value" to "\" onfocus=\"alert(1)") }
        assertEquals("<input type=\"text\" name=\"q\" value=\"&quot; onfocus=&quot;alert(1)\">", out)
        assertFalse("onfocus=\"" in out)
        val title = html { element("svg") { element("title") { text("'><script>") } } }
        assertEquals("<svg><title>&#39;&gt;&lt;script&gt;</title></svg>", title)
    }

    @Test
    fun `no element can run a script or carry a style, and links stay inside the dashboard`() {
        assertFailsWith<IllegalArgumentException> { html { element("script") { text("alert(1)") } } }
        assertFailsWith<IllegalArgumentException> { html { element("img", "src" to "/x") } }
        assertFailsWith<IllegalArgumentException> { html { element("div", "onclick" to "alert(1)") } }
        assertFailsWith<IllegalArgumentException> { html { element("div", "OnMouseOver" to "alert(1)") } }
        assertFailsWith<IllegalArgumentException> { html { element("div", "style" to "color: red") } }
        assertFailsWith<IllegalArgumentException> { html { element("div", "x=\"1\" onclick" to "alert(1)") } }
        assertFailsWith<IllegalArgumentException> { html { element("a", "href" to "javascript:alert(1)") } }
        assertFailsWith<IllegalArgumentException> { html { element("a", "href" to "https://example.com/") } }
        assertFailsWith<IllegalArgumentException> { html { element("a", "href" to "//example.com/") } }
        assertFailsWith<IllegalArgumentException> { html { element("form", "action" to "http://127.0.0.1:1/") } }
        assertEquals("<a href=\"/turns/42\">#42</a>", html { a(link("turns", 42), "#42") })
        assertEquals("<a href=\"#top\">top</a>", html { element("a", "href" to "#top") { text("top") } })
    }

    @Test
    fun `no URL leaves the dashboard by a backslash, a blank, another case or a form button, and no page writes a meta`() {
        // Browsers read `\` as `/` and drop tabs and line breaks: each of these is `//evil.example/`.
        listOf("/\\evil.example/", "\\/evil.example/", "/\t/evil.example/", "/\n/evil.example/", "/\r/evil.example/", "/ /evil.example/").forEach { url ->
            assertFailsWith<IllegalArgumentException>(url) { html { element("a", "href" to url) } }
        }
        assertFailsWith<IllegalArgumentException> { html { element("a", "HREF" to "https://evil.example/") } }
        assertFailsWith<IllegalArgumentException> { html { element("button", "formaction" to "https://evil.example/") } }
        assertFailsWith<IllegalArgumentException> { html { element("button", "formAction" to "//evil.example/") } }
        assertEquals("<button formaction=\"/sql\">Run</button>", html { element("button", "formaction" to "/sql") { text("Run") } })
        // A refresh to a URL would leave the dashboard: only `document` writes a <meta>, with a number of seconds.
        assertFailsWith<IllegalArgumentException> { html { element("meta", "http-equiv" to "refresh", "content" to "0; url=https://evil.example/") } }
        assertFailsWith<IllegalArgumentException> { html { element("meta", "charset" to "utf-8") } }
        // The SQL page's query: a textarea, whose text is escaped.
        assertEquals("<textarea name=\"q\">&lt;/textarea&gt;&lt;b&gt;</textarea>", html { element("textarea", "name" to "q") { text("</textarea><b>") } })
    }

    @Test
    fun `a link encodes its segments and its query, and the request reads them back`() {
        val tricky = "Bé a/b?c#d%"
        val built = link("players", tricky, query = mapOf("page" to 2, "q" to "a&b=c d", "none" to null))
        assertEquals("/players/B%C3%A9%20a%2Fb%3Fc%23d%25?page=2&q=a%26b%3Dc+d", built.href)
        val parsed = Routes.parse(WebCall("GET", built.href.substringBefore('?'), built.href.substringAfter('?'), "127.0.0.1:1"))!!
        assertEquals(listOf("players", tricky), parsed.segments)
        assertEquals(mapOf("page" to "2", "q" to "a&b=c d"), parsed.query)
        assertEquals("/", link().href)
        assertEquals(listOf("a+b"), Routes.parse(WebCall("GET", "/a+b", null, null))!!.segments, "a + of a path is a +")
        assertNull(Routes.parse(WebCall("GET", "/players/%zz", null, null)), "a bad escape")
        assertNull(Routes.parse(WebCall("GET", "/", "q=%", null)))
    }

    @Test
    fun `a request rebuilds its link with other parameters`() {
        val request = WebRequest(listOf("turns"), mapOf("trigger" to "chat", "page" to "3"))
        assertEquals("/turns?trigger=chat&page=4", request.with("page" to 4).href)
        assertEquals("/turns?trigger=chat", request.with("page" to null).href)
        assertEquals("chat", request.param("trigger"))
        assertNull(WebRequest(listOf("turns"), mapOf("trigger" to "")).param("trigger"), "an empty field is no filter")
        assertEquals(7, WebRequest(emptyList(), mapOf("page" to "x")).int("page", 7))
    }

    @Test
    fun `paging asks one row more than it shows and links the pages around`() {
        val third = Paging.of(WebRequest(listOf("decisions"), mapOf("page" to "3")))
        assertEquals(100, third.offset)
        assertEquals(51, third.limit)
        assertEquals((1..50).toList() to true, third.rows((1..51).toList()))
        assertEquals((1..7).toList() to false, third.rows((1..7).toList()))
        assertEquals(1, Paging.of(WebRequest(emptyList(), mapOf("page" to "-4"))).page)
        assertEquals(Paging.MAX_PAGE, Paging.of(WebRequest(emptyList(), mapOf("page" to "999999999"))).page)

        val request = WebRequest(listOf("decisions"), mapOf("kind" to "guard", "page" to "3"))
        val pager = html { pager(request, third, hasNext = true) }
        assertContains(pager, "href=\"/decisions?kind=guard&amp;page=2\"")
        assertContains(pager, "href=\"/decisions?kind=guard&amp;page=4\"")
        assertContains(html { pager(request, Paging(2), hasNext = false) }, "href=\"/decisions?kind=guard\"", message = "page 1 without its parameter")
        assertEquals("", html { pager(WebRequest(listOf("decisions")), Paging(1), hasNext = false) }, "a single page has no pager")
    }

    @Test
    fun `a form of filters keeps the values chosen, escaped`() {
        val request = WebRequest(listOf("decisions"), mapOf("kind" to "guard", "subject" to "\"><b>"))
        val form = html {
            filters(link("decisions"), request, listOf(Filter.Choice("kind", "Kind", listOf("wake", "guard")), Filter.Field("subject", "Subject")))
        }
        assertContains(form, "<form method=\"get\" action=\"/decisions\" class=\"filters\">")
        assertContains(form, "<option value=\"guard\" selected=\"selected\">guard</option>")
        assertContains(form, "<option value=\"wake\">wake</option>")
        assertContains(form, "value=\"&quot;&gt;&lt;b&gt;\"")
        assertContains(form, ">clear</a>")
    }

    @Test
    fun `a page has its frame, its stylesheet and no script`() {
        val page = document("Overview", listOf(NavEntry("Overview", link()), NavEntry("Turns", link("turns"))), link(), refresh = 15) { p("hello <you>") }
        assertTrue(page.startsWith("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"))
        assertContains(page, "<meta http-equiv=\"refresh\" content=\"15\">")
        assertContains(page, "<link rel=\"stylesheet\" href=\"/style.css\">")
        assertContains(page, "<a href=\"/\" class=\"current\">Overview</a><a href=\"/turns\">Turns</a>")
        assertContains(page, "<h1>Overview</h1><p>hello &lt;you&gt;</p>")
        assertFalse("<script" in page.lowercase())
        assertFalse("refresh" in document("Turns", emptyList(), null) {}, "no refresh unless asked")
    }

    @Test
    fun `charts draw with attributes, never a style, and escape their labels`() {
        val chart = html { bars("Cost by role", listOf(Bar("<director>", 2.0, "$2.00"), Bar("reflex", 0.0, "$0"))) }
        assertContains(chart, "<svg class=\"chart\" viewBox=\"0 0 720 56\" role=\"img\" aria-label=\"Cost by role\"><title>Cost by role</title>")
        assertContains(chart, "&lt;director&gt;")
        assertContains(chart, "<rect x=\"150\" y=\"8\" width=\"400.0\" height=\"16\" class=\"bar\"/>")
        assertContains(chart, "width=\"0.0\"", message = "a bar of nothing")
        assertFalse("style" in chart)
        assertEquals("", html { bars("Nothing", emptyList()) })

        val curve = html { curve("Calls by hour", listOf(0.0, 2.0, 1.0), "18:00", "20:00", "2 calls") }
        assertContains(curve, "<polyline points=\"8.0,140.0 360.0,16.0 712.0,78.0\" class=\"line\"/>")
        assertFalse("style" in curve)
        assertEquals("", html { curve("One point", listOf(1.0), "a", "b", "1") })
    }
}
