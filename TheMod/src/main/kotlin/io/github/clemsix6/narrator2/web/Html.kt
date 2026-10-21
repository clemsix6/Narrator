package io.github.clemsix6.narrator2.web

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/*
 * The dashboard's HTML (M6 §8.6). Every page is built with [Html], where text is escaped by construction: [Html.text]
 * and every attribute value go through [escape]. Element and attribute names are checked against fixed lists: no
 * attribute can run a script (`on…`) or carry a style (`style`, which the CSP forbids), and a link can only point inside
 * the dashboard ([Link]), never to `javascript:` or another site. Nothing here writes HTML from a text.
 *
 * The rest of the file is what every page shares: the frame of a page ([document]), the pagination ([Paging], [pager])
 * and the forms of filters ([filters]).
 */

/** `<`, `>`, `&`, `"` and `'` as entities, and the NUL character replaced: a text that can neither open a tag nor end an attribute. */
fun escape(text: String): String {
    if (text.none { it in "<>&\"'\u0000" }) return text
    return buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '\u0000' -> append('�')
                else -> append(c)
            }
        }
    }
}

/**
 * A link inside the dashboard: a path from its root, each segment percent-encoded, and a query, form-encoded. Built by
 * [link] or [WebRequest.with] only, so that it always starts with one `/`.
 */
@JvmInline
value class Link internal constructor(val href: String) {
    override fun toString() = href
}

/**
 * The link to [segments] (`link("players", name)` → `/players/B%C3%A9a`) with [query] (null values left out). Any
 * text can be a segment: a name or a ref is encoded, never read as a path.
 */
fun link(vararg segments: Any, query: Map<String, Any?> = emptyMap()): Link {
    val path = segments.joinToString("/", prefix = "/") { encodeSegment(it.toString()) }
    val parameters = query.entries.filter { it.value != null }
        .joinToString("&") { (name, value) -> "${encodeQuery(name)}=${encodeQuery(value.toString())}" }
    return Link(if (parameters.isEmpty()) path else "$path?$parameters")
}

/** A path segment, percent-encoded: only the unreserved characters of RFC 3986 stay as they are. */
internal fun encodeSegment(segment: String): String = buildString {
    for (byte in segment.toByteArray(StandardCharsets.UTF_8)) {
        val c = byte.toInt() and 0xFF
        if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-._~")) append(c.toChar())
        else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
    }
}

private fun encodeQuery(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private const val HEX = "0123456789ABCDEF"

@DslMarker
annotation class HtmlMarker

/**
 * A piece of HTML being written. Pages receive one (`Page(title) { h2("Players"); p { text(name) } }`) and write
 * through its elements: [text] escapes, and so does every attribute. Not thread-safe: one page, one thread.
 */
@HtmlMarker
class Html internal constructor(private val out: StringBuilder) {

    /** [value] as text, escaped; nothing for null. */
    fun text(value: Any?) {
        if (value != null) out.append(escape(value.toString()))
    }

    /**
     * An element [name] with [attributes] (a null value leaves the attribute out) and [body]; without a body, a void
     * element (`<br>`, `<input>`) or an empty one. [name] must be one of the known elements; an attribute name must not
     * start with `on` nor be `style`, and `href`, `action` and `src` must point inside the dashboard.
     */
    internal fun element(name: String, vararg attributes: Pair<String, Any?>, body: (Html.() -> Unit)? = null) {
        require(name in ELEMENTS) { "unknown element <$name>" }
        out.append('<').append(name)
        for ((attribute, value) in attributes) {
            if (value == null) continue
            require(ATTRIBUTE.matches(attribute) && !attribute.startsWith("on", ignoreCase = true) && !attribute.equals("style", ignoreCase = true)) {
                "attribute $attribute is not allowed"
            }
            val text = value.toString()
            require(attribute !in URL_ATTRIBUTES || (text.startsWith("/") && !text.startsWith("//")) || text.startsWith("#")) {
                "$attribute must point inside the dashboard: $text"
            }
            out.append(' ').append(attribute).append("=\"").append(escape(text)).append('"')
        }
        when {
            name in VOID -> out.append('>')
            body == null && name in SVG -> out.append("/>")
            else -> {
                out.append('>')
                body?.invoke(this)
                out.append("</").append(name).append('>')
            }
        }
    }

    fun h1(title: String) = element("h1") { text(title) }

    fun h2(title: String) = element("h2") { text(title) }

    fun h3(title: String) = element("h3") { text(title) }

    fun p(cls: String? = null, body: Html.() -> Unit) = element("p", "class" to cls, body = body)

    fun p(value: String, cls: String? = null) = element("p", "class" to cls) { text(value) }

    fun div(cls: String? = null, body: Html.() -> Unit) = element("div", "class" to cls, body = body)

    fun section(cls: String? = null, body: Html.() -> Unit) = element("section", "class" to cls, body = body)

    fun span(cls: String? = null, body: Html.() -> Unit) = element("span", "class" to cls, body = body)

    fun span(value: Any?, cls: String? = null) = element("span", "class" to cls) { text(value) }

    fun strong(value: Any?) = element("strong") { text(value) }

    fun em(value: Any?) = element("em") { text(value) }

    fun small(value: Any?) = element("small") { text(value) }

    fun code(value: Any?) = element("code") { text(value) }

    /** Preformatted text: a transcript, a module's source, a JSON argument. */
    fun pre(value: Any?, cls: String? = null) = element("pre", "class" to cls) { text(value) }

    fun br() = element("br")

    /** A link inside the dashboard. */
    fun a(link: Link, cls: String? = null, body: Html.() -> Unit) = element("a", "href" to link.href, "class" to cls, body = body)

    fun a(link: Link, value: Any?, cls: String? = null) = a(link, cls) { text(value) }

    /** A folded block: [summary] shows, the rest opens on a click (the system prompt of a transcript, a long result). */
    fun details(summary: String, open: Boolean = false, cls: String? = null, body: Html.() -> Unit) =
        element("details", "class" to cls, "open" to (if (open) "open" else null)) {
            element("summary") { text(summary) }
            body()
        }

    fun ul(cls: String? = null, body: Html.() -> Unit) = element("ul", "class" to cls, body = body)

    fun li(body: Html.() -> Unit) = element("li", body = body)

    fun li(value: Any?) = element("li") { text(value) }

    /** A list of terms and what they are: `dl { item("State") { text("waiting") } }`. */
    fun dl(cls: String? = null, body: Html.() -> Unit) = element("dl", "class" to cls, body = body)

    fun item(term: String, body: Html.() -> Unit) {
        element("dt") { text(term) }
        element("dd", body = body)
    }

    fun item(term: String, value: String?) = item(term) { text(value) }

    /** A table: [headers] (a header starting with `#` is a number column, right-aligned, its `#` left out), then [body]'s rows. */
    fun table(headers: List<String>, cls: String? = null, body: Table.() -> Unit) = element("table", "class" to cls) {
        element("thead") {
            element("tr") {
                headers.forEach { header ->
                    val number = header.startsWith("#")
                    element("th", "class" to (if (number) "num" else null)) { text(header.removePrefix("#")) }
                }
            }
        }
        element("tbody") { Table(this).body() }
    }

    /** The rows of a [table]. */
    @HtmlMarker
    class Table internal constructor(private val html: Html) {
        /** One row; [cls] marks it (`failure`, `warning`, `muted`). */
        fun row(cls: String? = null, cells: Row.() -> Unit) = html.element("tr", "class" to cls) { Row(this).cells() }
    }

    /** The cells of a [Table.row]. */
    @HtmlMarker
    class Row internal constructor(private val html: Html) {
        fun cell(value: Any?, cls: String? = null) = html.element("td", "class" to cls) { text(value) }

        fun cell(cls: String? = null, body: Html.() -> Unit) = html.element("td", "class" to cls, body = body)

        /** A number, right-aligned. */
        fun number(value: Any?) = cell(value, "num")
    }

    internal companion object {
        /** Elements without an end tag. */
        val VOID = setOf("br", "hr", "input", "meta", "link")

        /** The elements of the inline charts ([bars], [curve]): those without content close themselves. */
        val SVG = setOf("svg", "g", "rect", "line", "polyline", "text", "title")

        val ELEMENTS = VOID + SVG + setOf(
            "html", "head", "body", "nav", "main", "header", "footer", "section", "div", "span", "p", "h1", "h2", "h3",
            "a", "table", "thead", "tbody", "tr", "th", "td", "details", "summary", "pre", "code", "ul", "li", "dl", "dt", "dd",
            "form", "label", "select", "option", "button", "textarea", "strong", "em", "small",
        )

        /** An attribute name: letters, digits and dashes (`viewBox`, `http-equiv`, `text-anchor`). */
        val ATTRIBUTE = Regex("[a-zA-Z][a-zA-Z0-9-]*")

        /** Attributes holding a URL: they must point inside the dashboard. */
        val URL_ATTRIBUTES = setOf("href", "action", "src")
    }
}

/** One entry of the navigation bar: its label and its link. */
data class NavEntry(val label: String, val link: Link)

/**
 * A whole page, from `<!doctype html>` to `</html>`: [title], the stylesheet (`/style.css`, the only resource), the
 * navigation bar ([nav], [current] marked), then [body] in `<main>`. [refresh] (seconds) reloads the page by
 * `<meta http-equiv="refresh">`: no script needed.
 */
fun document(title: String, nav: List<NavEntry>, current: Link?, refresh: Int? = null, body: Html.() -> Unit): String {
    val out = StringBuilder(8_192).append("<!doctype html>")
    Html(out).element("html", "lang" to "en") {
        element("head") {
            element("meta", "charset" to "utf-8")
            element("meta", "name" to "viewport", "content" to "width=device-width, initial-scale=1")
            element("meta", "name" to "referrer", "content" to "no-referrer")
            if (refresh != null) element("meta", "http-equiv" to "refresh", "content" to refresh)
            element("title") { text("$title · Narrator") }
            element("link", "rel" to "stylesheet", "href" to "/style.css")
        }
        element("body") {
            element("nav") {
                element("span", "class" to "brand") { text("Narrator") }
                nav.forEach { entry -> element("a", "href" to entry.link.href, "class" to (if (entry.link == current) "current" else null)) { text(entry.label) } }
            }
            element("main") {
                h1(title)
                body()
            }
        }
    }
    return out.toString()
}

/** The rows a list shows per page (M6 §8.3). */
const val PAGE_SIZE = 50

/**
 * Where a list stands: its [page] (from 1, the request's `?page=`) of [size] rows. A query asks for [limit] rows, one
 * more than it shows, to know whether a next page exists ([rows]).
 */
data class Paging(val page: Int, val size: Int = PAGE_SIZE) {
    val offset: Int get() = (page - 1) * size

    val limit: Int get() = size + 1

    /** The rows of this page among [fetched] (at most [limit]), and whether a next page exists. */
    fun <T> rows(fetched: List<T>): Pair<List<T>, Boolean> = fetched.take(size) to (fetched.size > size)

    companion object {
        /** The paging of [request] (`?page=`, 1 when absent or invalid). */
        fun of(request: WebRequest, size: Int = PAGE_SIZE) = Paging(request.int("page", 1).coerceIn(1, MAX_PAGE), size)

        /** Deep enough for any list; an offset cannot overflow. */
        const val MAX_PAGE = 100_000
    }
}

/** `← newer · page 3 · older →`: links to the pages around [paging], every other parameter of [request] kept. */
fun Html.pager(request: WebRequest, paging: Paging, hasNext: Boolean) {
    if (paging.page == 1 && !hasNext) return
    p("pager") {
        if (paging.page > 1) a(request.with("page" to (paging.page - 1).takeIf { it > 1 }), "← newer") else span("← newer", "muted")
        text(" · page ${paging.page} · ")
        if (hasNext) a(request.with("page" to paging.page + 1), "older →") else span("older →", "muted")
    }
}

/**
 * A filter of a list: a [Choice] among fixed values (empty: any), or a free [Field]. [name] is the query parameter, read
 * back by the page with `request.param(name)`.
 */
sealed interface Filter {
    val name: String
    val label: String

    data class Choice(override val name: String, override val label: String, val options: List<String>) : Filter

    data class Field(override val name: String, override val label: String, val placeholder: String = "") : Filter
}

/** A form that reloads [root] (`GET`) with the [fields] chosen, filled with the values of [request]; a new filter starts at page 1. */
fun Html.filters(root: Link, request: WebRequest, fields: List<Filter>) {
    element("form", "method" to "get", "action" to root.href, "class" to "filters") {
        fields.forEach { filter ->
            val value = request.param(filter.name).orEmpty()
            element("label") {
                text(filter.label)
                text(" ")
                when (filter) {
                    is Filter.Choice -> element("select", "name" to filter.name) {
                        element("option", "value" to "", "selected" to (if (value.isEmpty()) "selected" else null)) { text("any") }
                        filter.options.forEach { option ->
                            element("option", "value" to option, "selected" to (if (option == value) "selected" else null)) { text(option) }
                        }
                    }
                    is Filter.Field -> element("input", "type" to "text", "name" to filter.name, "value" to value, "placeholder" to filter.placeholder.ifEmpty { null })
                }
            }
        }
        element("button", "type" to "submit") { text("Filter") }
        if (fields.any { request.param(it.name) != null }) a(root, "clear", "muted")
    }
}
