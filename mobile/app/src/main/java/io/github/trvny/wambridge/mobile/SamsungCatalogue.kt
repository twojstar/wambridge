package io.github.trvny.wambridge.mobile

import android.content.Context
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Element
import org.w3c.dom.Text
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource

/**
 * Browsing and searching the speaker's own TuneIn catalogue.
 *
 * The phone-side twin of `src/wambridge/catalogue.py`, against the same measured
 * firmware. Three of its properties shape everything here:
 *
 *  * The browse cursor lives in the speaker, not in this process. No request
 *    carries a path, so every call is relative to wherever the cursor was left -
 *    including by another client. Call [open] before anything else.
 *  * `contentid` is the row's index within the current page and restarts at 0 on
 *    every level, so it only means anything against the page it came from. The
 *    stable identifier is `mediaid`.
 *  * The CPM subsystem wedges under a fast series of requests: it answers
 *    `totallistcount=0` for levels that do have content, then goes quiet for
 *    twenty to thirty seconds while UIC keeps answering. It recovers on its own,
 *    so an empty page is retried rather than believed.
 */
internal object SamsungCatalogue {
    /** Folders and stations are told apart by `type` on `menuitem`. */
    private const val ITEM_FOLDER = "0"
    private const val ITEM_STATION = "2"

    /**
     * `root` names which of the two trees the cursor is in. They are separate
     * roots and both answer `isroot="1"`, so `isroot` alone cannot tell them apart.
     */
    const val BROWSE_ROOT = "Browse"

    data class Entry(
        val contentId: String,
        val title: String,
        val itemType: String,
        val mediaId: String? = null,
        val description: String? = null,
        val thumbnail: String? = null,
    ) {
        /** Whether descending into this row is meaningful. */
        val isFolder: Boolean get() = itemType == ITEM_FOLDER

        /**
         * Whether this row can yield a playable URL.
         *
         * `type="2"` is not enough. Measured on the M5, 2026-08-28: descending into
         * a podcast programme found under a search - "Newshour" beneath a search for
         * BBC - lists 50 episodes that are all `type="2"` and carry `mediaid` like
         * `t573501779`. Those are not station ids, and the resolver only accepts the
         * `s…` shape, so offering them would be offering a button that cannot play.
         */
        val isStation: Boolean
            get() = itemType == ITEM_STATION && mediaId != null && isTuneInStationId(mediaId)

        /** [contentId] as the number the commands expect. */
        val index: Int
            get() = contentId.toIntOrNull()
                ?: throw IOException("Invalid catalogue content ID: $contentId")
    }

    data class Page(
        val category: String? = null,
        val isRoot: Boolean = false,
        val root: String? = null,
        val total: Int? = null,
        val startIndex: Int = 0,
        val entries: List<Entry> = emptyList(),
    ) {
        /** Whether the cursor sits on a root that is not the catalogue. */
        val isForeignRoot: Boolean
            get() = root != null && root != BROWSE_ROOT

        /** Whether the level continues past this page. */
        val hasMore: Boolean
            get() = total != null && startIndex + entries.size < total
    }

    data class StationDetail(
        val title: String? = null,
        val stationUrl: String? = null,
        val mediaId: String? = null,
        val description: String? = null,
        val thumbnail: String? = null,
    )

    /**
     * Select TuneIn and walk the cursor back to the catalogue root.
     *
     * Throws when the cursor cannot be normalised: every later call would then be
     * relative to an unknown level and would report the wrong thing rather than fail.
     *
     * A search leaves the cursor in a second tree whose root is `Search`, which
     * also answers `isroot="1"`. Only `BrowseMain` crosses back - `SetSelectRadio`,
     * repeated `GetUpperRadioList`, a descend-and-ascend round trip and
     * `SetCpService` were each measured and each left the cursor where it was.
     */
    fun open(context: Context, speakerIp: String): Page {
        cpm(context, speakerIp, "SetSelectRadio")
        Thread.sleep(SETTLE_MS)

        var page = Page()
        for (attempt in 0 until OPEN_ATTEMPTS) {
            page = parsePage(
                cpm(context, speakerIp, "GetUpperRadioList", paged(0)),
            )
            // `isRoot` alone is not enough to accept the answer. A recovering CPM
            // reports `totallistcount=0` for a level that does have content - the
            // trap `fetchPage` already retries around - and the Browse root is never
            // genuinely empty, so an empty one there means "ask again", not "done".
            //
            // A *foreign* root is accepted empty, and must be: a search that matched
            // nothing leaves the cursor on a legitimately empty `Search` root, and
            // only `BrowseMain` below crosses back out of it. Retrying that one
            // instead would strand the cursor in the search tree for good, failing
            // every later browse the same way.
            if (page.isRoot && (page.entries.isNotEmpty() || page.isForeignRoot)) break
            if (attempt + 1 < OPEN_ATTEMPTS) Thread.sleep(SETTLE_MS)
        }
        if (!page.isRoot) {
            throw IOException(
                "Could not return the speaker's browse cursor to a root; " +
                    "results from any deeper level would be misleading",
            )
        }
        if (page.isForeignRoot) {
            page = leaveForeignRoot(context, speakerIp, page)
        }
        if (page.entries.isEmpty()) {
            throw IOException(
                "The speaker's browse root came back empty; results from any " +
                    "other level would be misleading",
            )
        }
        return page
    }

    /**
     * Cross from the search tree back into the catalogue with `BrowseMain`.
     *
     * The read after the crossing is retried like any other, because it is the
     * first one aimed at the Browse root and can meet the transient empty answer
     * a recovering CPM gives. Raising on that would make a healthy speaker look
     * broken for the one path that has just spent its settle time crossing.
     */
    private fun leaveForeignRoot(context: Context, speakerIp: String, page: Page): Page {
        val was = page.root
        cpm(context, speakerIp, "BrowseMain", paged(0))
        var crossed = Page()
        for (attempt in 0 until OPEN_ATTEMPTS) {
            Thread.sleep(SETTLE_MS)
            crossed = parsePage(
                cpm(context, speakerIp, "GetCurrentRadioList", paged(0)),
            )
            if (crossed.entries.isNotEmpty() || crossed.root != BROWSE_ROOT) break
        }
        if (crossed.root != BROWSE_ROOT) {
            throw IOException(
                "The speaker's radio cursor is in the $was tree and BrowseMain did not " +
                    "return it to $BROWSE_ROOT (it is now ${crossed.root}); " +
                    "search again, or clear it from the Samsung app.",
            )
        }
        return crossed
    }

    /** Return the level the cursor is on. */
    fun currentPage(context: Context, speakerIp: String, startIndex: Int = 0): Page =
        fetchPage(context, speakerIp, "GetCurrentRadioList", paged(startIndex))

    /**
     * Move the cursor into one row of the current page.
     *
     * Named `Get` by the firmware, which hides that it moves the cursor.
     */
    fun descend(context: Context, speakerIp: String, contentId: Int): Page =
        fetchPage(
            context,
            speakerIp,
            "GetSelectRadioList",
            listOf(SamsungTuneIn.Argument("contentid", contentId.toString(), SamsungTuneIn.Kind.DEC)) +
                paged(0),
        )

    /** Move the cursor back up one level. */
    fun ascend(context: Context, speakerIp: String): Page =
        fetchPage(context, speakerIp, "GetUpperRadioList", paged(0))

    /**
     * Search the catalogue by name.
     *
     * Results are mixed: stations carry a `mediaid`, while headings such as
     * "Artist: Trojka" do not and cannot be descended into.
     */
    fun search(context: Context, speakerIp: String, query: String, startIndex: Int = 0): Page {
        if (query.isBlank()) throw IOException("A catalogue search needs a non-empty query")
        return parsePage(
            cpm(
                context,
                speakerIp,
                "SearchQuery",
                listOf(SamsungTuneIn.Argument("query", query, SamsungTuneIn.Kind.STR)) +
                    paged(startIndex),
                timeoutMs = SEARCH_TIMEOUT_MS,
            ),
        )
    }

    /**
     * Return the detail of one station on the current page.
     *
     * [contentId] is the row's index on the page it was read from, so this only
     * means anything while the cursor is still on that level.
     *
     * The `stationurl` carries the speaker's own TuneIn partner id and serial. It
     * is a credential: log it and it ends up in a bug report.
     */
    fun stationDetail(context: Context, speakerIp: String, contentId: Int): StationDetail =
        parseStationDetail(
            cpm(
                context,
                speakerIp,
                "GetStationData",
                listOf(
                    SamsungTuneIn.Argument("selectitemid", contentId.toString(), SamsungTuneIn.Kind.DEC),
                ),
            ),
        )

    /**
     * Fetch one page, retrying the empty answer that means CPM is recovering.
     *
     * A level that really is empty - Favorites and Recents on a speaker nobody has
     * used that way - looks identical to a wedged subsystem, so the last answer is
     * returned as it stands once the attempts run out rather than throwing.
     */
    private fun fetchPage(
        context: Context,
        speakerIp: String,
        method: String,
        arguments: List<SamsungTuneIn.Argument>,
    ): Page {
        var page = Page()
        for (attempt in 0 until PAGE_ATTEMPTS) {
            page = parsePage(cpm(context, speakerIp, method, arguments))
            if (page.entries.isNotEmpty() || (page.total ?: 0) > 0) return page
            if (attempt + 1 < PAGE_ATTEMPTS) Thread.sleep(PAGE_SETTLE_MS)
        }
        return page
    }

    private fun cpm(
        context: Context,
        speakerIp: String,
        method: String,
        arguments: List<SamsungTuneIn.Argument> = emptyList(),
        timeoutMs: Int = BROWSE_TIMEOUT_MS,
    ): String = SamsungTuneIn.request(
        context,
        speakerIp,
        apiType = "CPM",
        method = method,
        arguments = arguments,
        timeoutMs = timeoutMs,
    )

    private fun paged(startIndex: Int): List<SamsungTuneIn.Argument> = listOf(
        SamsungTuneIn.Argument("startindex", startIndex.toString(), SamsungTuneIn.Kind.DEC),
        SamsungTuneIn.Argument("listcount", LIST_COUNT.toString(), SamsungTuneIn.Kind.DEC),
    )

    /** Parse one `RadioList` answer into rows plus paging state. */
    fun parsePage(body: String): Page {
        val root = parseXml(body, "catalogue")
        val entries = mutableListOf<Entry>()
        var category: String? = null
        var isRoot = false
        var treeRoot: String? = null
        var total: Int? = null
        var startIndex = 0

        for (node in root.descendants()) {
            when (localName(node)) {
                "category" -> if (category == null) {
                    category = node.trimmedText()
                    isRoot = node.getAttribute("isroot") == "1"
                }

                "root" -> treeRoot = treeRoot ?: node.trimmedText()
                "totallistcount" -> total = total ?: node.trimmedText()?.toIntOrNull()
                "startindex" -> startIndex = node.trimmedText()?.toIntOrNull() ?: startIndex
                "menuitem" -> {
                    val values = node.childText()
                    val title = values["title"]
                    val contentId = values["contentid"]
                    // A row without either cannot be shown or acted on. Skipping it
                    // keeps one malformed entry from losing the whole page.
                    if (!title.isNullOrBlank() && !contentId.isNullOrBlank()) {
                        entries += Entry(
                            contentId = contentId,
                            title = title,
                            itemType = node.getAttribute("type").ifBlank { "?" },
                            mediaId = values["mediaid"],
                            description = values["description"],
                            thumbnail = values["thumbnail"],
                        )
                    }
                }
            }
        }

        return Page(
            category = category,
            isRoot = isRoot,
            root = treeRoot,
            total = total,
            startIndex = startIndex,
            entries = entries,
        )
    }

    /** Parse a `StationData` answer. */
    fun parseStationDetail(body: String): StationDetail {
        val values = mutableMapOf<String, String>()
        for (node in parseXml(body, "station").descendants()) {
            val text = node.trimmedText() ?: continue
            values.putIfAbsent(localName(node), text)
        }
        return StationDetail(
            title = values["title"],
            stationUrl = values["stationurl"],
            mediaId = values["mediaid"],
            description = values["description"],
            thumbnail = values["thumbnail"],
        )
    }

    private fun parseXml(body: String, what: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Fail closed: untrusted network XML must not allow any DOCTYPE.
            // If this parser cannot enforce it, refuse to parse.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            harden("http://xml.org/sax/features/external-general-entities", false)
            harden("http://xml.org/sax/features/external-parameter-entities", false)
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        return try {
            val builder = factory.newDocumentBuilder().apply {
                entityResolver = EntityResolver { _, _ ->
                    throw IOException("External entities are not allowed in $what XML")
                }
            }
            builder.parse(InputSource(StringReader(body))).documentElement
        } catch (error: Exception) {
            throw IOException("Samsung WAM returned invalid $what XML: ${body.take(200)}", error)
        }
    }

    /** Ask for one hardening feature, and carry on where the parser has never heard of it. */
    private fun DocumentBuilderFactory.harden(feature: String, value: Boolean = true) {
        try {
            setFeature(feature, value)
        } catch (_: ParserConfigurationException) {
            // Android's parser refuses several of these by name. Nothing to do:
            // the parse itself is what matters, and the remaining settings hold.
        }
    }

    private fun Element.descendants(): Sequence<Element> = sequence {
        yield(this@descendants)
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) yieldAll(child.descendants())
        }
    }

    /** Direct child elements as `name -> text`, first spelling winning. */
    private fun Element.childText(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                child.trimmedText()?.let { values.putIfAbsent(localName(child), it) }
            }
        }
        return values
    }

    /** The element's own text, or null when it only holds other elements. */
    private fun Element.trimmedText(): String? {
        val text = StringBuilder()
        val children = childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Text) text.append(child.data)
        }
        return text.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun localName(node: Element): String = SamsungTuneIn.localName(node.tagName)

    private const val LIST_COUNT = 30
    private const val OPEN_ATTEMPTS = 5
    private const val PAGE_ATTEMPTS = 3
    private const val SETTLE_MS = 1_000L
    private const val PAGE_SETTLE_MS = 2_000L
    private const val BROWSE_TIMEOUT_MS = 8_000
    private const val SEARCH_TIMEOUT_MS = 12_000
}
