package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Whether "Up" has to re-open the catalogue instead of climbing one level.
 *
 * `ascend` cannot leave the search tree - measured, along with three other
 * commands that also could not, see [SamsungCatalogue.open]. Only re-opening
 * crosses back, and from the top of a result list that is what "up" means anyway.
 */
internal fun catalogueUpNeedsReopen(inSearch: Boolean, atRoot: Boolean): Boolean =
    inSearch && atRoot

/**
 * Folds a freshly fetched page into what is already on screen.
 *
 * Paging appends so a long level reads as one list; everything else replaces.
 * The fresh page wins on the level metadata, because it is the speaker's account
 * of where the cursor now is - **except** `startIndex`, which has to keep
 * describing the accumulated list rather than the last fetch.
 *
 * Taking the fresh `startIndex` alongside the accumulated entries counts part of
 * the level twice. On a 90-row level fetched 30 at a time, the second merge would
 * hold `startIndex=30` with 60 rows, `hasMore` would compute 90 of 90 and hide the
 * button with rows 60-89 never fetched.
 */
internal fun mergedCataloguePage(
    previous: SamsungCatalogue.Page?,
    fresh: SamsungCatalogue.Page,
    append: Boolean,
): SamsungCatalogue.Page =
    if (append && previous != null) {
        fresh.copy(startIndex = previous.startIndex, entries = previous.entries + fresh.entries)
    } else {
        fresh
    }

/**
 * Browses the speaker's own TuneIn catalogue.
 *
 * **The cursor lives in the speaker, not here.** Every call moves one shared
 * position, so catalogue work runs on one process-wide thread and a tap is refused
 * while a request is in flight. Firing two would interleave on the speaker and the
 * answers would describe levels neither request asked for. The worker is
 * process-scoped rather than per-activity because a rotation destroys the screen
 * without stopping the request it started.
 *
 * For the same reason "Up" sends [SamsungCatalogue.ascend] instead of popping a
 * local stack: a stack would only describe where this screen believes it is.
 * The breadcrumb below is a display of what the speaker last reported, and is
 * rebuilt from its answers rather than maintained as truth.
 *
 * Playing needs no `GetStationData`: `mediaid` on a listed station is its TuneIn
 * id, and [RadioService] resolves that to a stream and relays it from the phone,
 * which is the road every other station here takes.
 */
class CatalogueActivity : Activity() {
    private lateinit var searchInput: EditText
    private lateinit var breadcrumbView: TextView
    private lateinit var statusView: TextView
    private lateinit var entriesView: LinearLayout
    private lateinit var upButton: Button
    private lateinit var moreButton: Button

    /** Last page the speaker reported, or null before the first answer. */
    private var page: SamsungCatalogue.Page? = null

    /** Refuses a tap while this screen already has a request out. */
    private var busy = false

    /** Set while the cursor sits in the search tree, which needs a different way back. */
    private var inSearch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)

        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "TuneIn catalogue",
                "Browse the catalogue reported by the M5 itself, then play through the phone relay.",
            ),
        )

        val searchCard = MobileUi.card(this)
        searchCard.addView(MobileUi.label(this, "Search"))
        searchInput = MobileUi.field(this, "Station, genre, show…").apply { setSingleLine(true) }
        searchCard.addView(searchInput)
        searchCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@CatalogueActivity, 10), 0, 0)
            MobileUi.addWeighted(this, MobileUi.button(this@CatalogueActivity, "Search", MobileUi.ButtonKind.PRIMARY) { runSearch() }, 1.25f)
            upButton = MobileUi.button(this@CatalogueActivity, "Up") { goUp() }
            MobileUi.addWeighted(this, upButton)
            MobileUi.addWeighted(this, MobileUi.button(this@CatalogueActivity, "Top") { openRoot() }, marginDp = 0)
        })
        content.addView(searchCard)

        breadcrumbView = TextView(this).apply {
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.wam_muted))
            setPadding(MobileUi.dp(this@CatalogueActivity, 2), MobileUi.dp(this@CatalogueActivity, 4), 0, MobileUi.dp(this@CatalogueActivity, 8))
        }
        content.addView(breadcrumbView)

        statusView = MobileUi.status(this)
        content.addView(statusView)
        content.addView(MobileUi.sectionTitle(this, "Browse"))

        entriesView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(entriesView)

        moreButton = MobileUi.button(this, "Load more") { loadMore() }.apply {
            visibility = View.GONE
        }
        content.addView(moreButton)

        setContentView(ScrollView(this).apply { addView(content) })
        openRoot()
    }

    // ---- speaker-side navigation -------------------------------------------------

    private fun openRoot() = onSpeaker("Opening the catalogue…") { context, ip ->
        inSearch = false
        SamsungCatalogue.open(context, ip)
    }

    private fun goUp() {
        if (catalogueUpNeedsReopen(inSearch, page?.isRoot != false)) {
            openRoot()
            return
        }
        onSpeaker("Going up…") { context, ip -> SamsungCatalogue.ascend(context, ip) }
    }

    private fun descend(entry: SamsungCatalogue.Entry) =
        onSpeaker("Opening ${entry.title}…") { context, ip ->
            SamsungCatalogue.descend(context, ip, entry.index)
        }

    private fun runSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) {
            statusView.text = "Type something to search for."
            return
        }
        onSpeaker("Searching for $query…") { context, ip ->
            inSearch = true
            SamsungCatalogue.search(context, ip, query)
        }
    }

    private fun loadMore() {
        val current = page ?: return
        val next = current.startIndex + current.entries.size
        onSpeaker("Loading more…", append = true) { context, ip ->
            SamsungCatalogue.currentPage(context, ip, next)
        }
    }

    /**
     * Runs one catalogue call off the UI thread and renders whatever comes back.
     *
     * Refuses to start while another is in flight rather than queueing: a queued
     * call would be relative to a cursor the user has since moved.
     */
    private fun onSpeaker(
        message: String,
        append: Boolean = false,
        work: (Context, String) -> SamsungCatalogue.Page,
    ) {
        if (busy) {
            statusView.text = "Still working on the last request…"
            return
        }
        // The application context, not this activity: the request outlives a rotation
        // or a back press, and holding the activity from a background thread leaks it.
        val appContext = applicationContext
        busy = true
        statusView.text = message
        // Process-scoped, not per-activity: `busy` dies with the instance, so a
        // rotation mid-request would let the recreated activity open the root while
        // the old thread is still walking the cursor, and the speaker owns only one.
        // Queueing is right here, unlike for a tap: the new instance does want its
        // request, just not at the same time as the old one.
        CATALOGUE_WORKER.execute {
            val result = runCatching {
                // Browsing talks to 55001, and the M5 allows one owner of that
                // connection. A renderer or a radio relay still holding it is what
                // "extra listeners have broken a working stream" in AGENTS.md means,
                // so let go first - the same thing TuneInActivity does before every
                // speaker interaction.
                releasePlaybackOwners()
                // Resolved rather than read from preferences: DHCP moves the speaker,
                // and a saved address is only a hint. This also covers the case of no
                // saved address at all, where discovery is the only way to a target.
                val target = SpeakerTarget.resolve(appContext)
                    ?: throw java.io.IOException(
                        "No WAM speaker found. Set its address in WAM Bridge.",
                    )
                work(appContext, target)
            }
            runOnUiThread {
                busy = false
                // The activity may be gone by now - the work was allowed to outlive it.
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { fresh ->
                        page = mergedCataloguePage(page, fresh, append)
                        render()
                    },
                    onFailure = { error ->
                        statusView.text =
                            error.message ?: "Catalogue request failed (${error.javaClass.simpleName})"
                    },
                )
            }
        }
    }

    // ---- rendering ---------------------------------------------------------------

    private fun render() {
        val current = page
        entriesView.removeAllViews()
        if (current == null) {
            statusView.text = "Nothing loaded."
            return
        }

        val where = current.category ?: current.root ?: SamsungCatalogue.BROWSE_ROOT
        val counted = current.total?.let { " — ${current.entries.size} of $it" }.orEmpty()
        breadcrumbView.text = where + counted
        MobileUi.setEnabled(upButton, !current.isRoot || inSearch)
        moreButton.visibility =
            if (current.hasMore) View.VISIBLE else View.GONE

        if (current.entries.isEmpty()) {
            statusView.text = "This level is empty."
            return
        }
        statusView.text = ""

        current.entries.forEach { entry ->
            entriesView.addView(rowFor(entry))
        }
    }

    private fun rowFor(entry: SamsungCatalogue.Entry): LinearLayout {
        val card = MobileUi.card(this)
        if (entry.isFolder) {
            card.setOnClickListener { descend(entry) }
        }
        card.addView(MobileUi.row(this).apply {
            addView(TextView(this@CatalogueActivity).apply {
                text = if (entry.isFolder) "${entry.title}  ›" else entry.title
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.wam_text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (entry.isStation) {
                addView(MobileUi.button(this@CatalogueActivity, "Play", MobileUi.ButtonKind.PRIMARY) { play(entry) })
            }
        })
        entry.description?.takeIf { it.isNotBlank() }?.let { description ->
            card.addView(MobileUi.body(this, description).apply {
                setPadding(0, MobileUi.dp(this@CatalogueActivity, 4), 0, 0)
            })
        }
        return card
    }

    // ---- playing -----------------------------------------------------------------

    private fun play(entry: SamsungCatalogue.Entry) {
        val tuneInId = entry.mediaId
        if (tuneInId.isNullOrBlank()) {
            // isStation already excludes this, so reaching it means the listing
            // changed shape rather than that the user did anything wrong.
            statusView.text = "${entry.title} has no TuneIn id to play."
            return
        }
        startForegroundService(
            Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY
                putExtra(RadioService.EXTRA_ALIAS, entry.title)
                putExtra(RadioService.EXTRA_TUNEIN_ID, tuneInId)
            },
        )
        statusView.text = "Starting ${entry.title}…"
        window.decorView.postDelayed({
            if (!busy) statusView.text = "● ${RadioService.lastStatus}"
        }, 1_200)
    }

    /**
     * Stops whatever else owns the speaker's control connection, and waits for it.
     *
     * Runs on the catalogue worker, never on the UI thread. Throws when an owner
     * will not let go, which surfaces as the request's own failure message rather
     * than as a browse that quietly fights a live stream.
     */
    private fun releasePlaybackOwners() {
        releaseOwner("Renderer", { RendererService.busy }) {
            Intent(this, RendererService::class.java).apply { action = RendererService.ACTION_STOP }
        }
        releaseOwner("Radio", { RadioService.active }) {
            Intent(this, RadioService::class.java).apply { action = RadioService.ACTION_STOP }
        }
    }

    private fun releaseOwner(name: String, busyNow: () -> Boolean, stop: () -> Intent) {
        if (!busyNow()) return
        startService(stop())
        val deadline = SystemClock.elapsedRealtime() + OWNER_STOP_TIMEOUT_MS
        while (busyNow() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        check(!busyNow()) { "$name did not release the WAM control channel" }
    }

    private companion object {
        /** How long an owner gets to let go of the speaker before browsing gives up. */
        const val OWNER_STOP_TIMEOUT_MS = 2_500L

        /**
         * One worker for the whole process, because there is one cursor in the
         * speaker. It outlives any single activity on purpose: that is what keeps a
         * request started before a rotation from running beside the one started after.
         */
        val CATALOGUE_WORKER = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "wam-catalogue").apply { isDaemon = true }
        }
    }
}
