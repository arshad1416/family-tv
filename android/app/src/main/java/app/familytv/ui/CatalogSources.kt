package app.familytv.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.core.Http
import app.familytv.discovery.*
import app.familytv.portal.Profile
import app.familytv.trakt.PlayItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** The original catalog identity and IPTV action remain unchanged across source choices. */
internal data class CatalogSourceRequest(
    val profile: Profile, val kind: String, val tmdb: String?, val name: String, val year: Int?,
    val item: PlayItem, val season: Int = 0, val episode: Int = 0, val label: String,
    val poster: String? = null, val overview: String? = null, val episodeName: String? = null,
    val iptv: IptvSourcePlayback,
) {
    fun target(title: DiscoveryTitle): DiscoveryTarget {
        if (title.kind != kind) throw SourceUnavailable("Choose metadata for the same title type.")
        return DiscoveryTarget(title, season, episode, episodeName)
    }
}

@Composable
internal fun CatalogSources(request: CatalogSourceRequest, close: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val currentClose by rememberUpdatedState(close)
    var unlocked by remember(request) { mutableStateOf(request.profile.parentalPin.isNullOrEmpty()) }
    var lookupSession by remember(request) { mutableStateOf<DiscoverySession?>(null) }
    var candidates by remember(request) { mutableStateOf<List<DiscoveryTitle>>(emptyList()) }
    var selectedMetadata by remember(request) { mutableStateOf<DiscoveryTitle?>(null) }
    var selectedRelease by remember(request) { mutableStateOf<SourceCandidate?>(null) }
    var progress by remember(request) { mutableStateOf<PreparationProgress?>(null) }
    var target by remember(request) { mutableStateOf<DiscoveryTarget?>(null) }
    var sources by remember(request) { mutableStateOf<List<SourceCandidate>?>(null) }
    var message by remember(request) { mutableStateOf<String?>(null) }
    var expanded by remember(request) { mutableStateOf(true) }
    var retry by remember(request) { mutableIntStateOf(0) }
    var busy by remember(request) { mutableStateOf(false) }
    var confirmation by remember(request) { mutableStateOf<SourceCandidate?>(null) }
    val client = remember(request) { DiscoveryClient(Http.client, app.portal.cachedConfig?.tmdbApiKey.orEmpty()) }
    val selection = remember(request) { ManualSourceSelection() }
    var playbackJob by remember(request) { mutableStateOf<Job?>(null) }
    fun cancelPlayback() { selection.cancel(); playbackJob?.cancel(); playbackJob = null; busy = false }
    fun dismiss() { cancelPlayback(); currentClose() }
    val guard = remember(request) { CatalogDiscoveryGuard(app.db, request.profile) }
    fun currentProfile() = ctx.sourceActivity()?.sourceRequestCurrent(request) == true && app.portal.cachedConfig?.profile?.let { DiscoverySession.policyKey(it) } == DiscoverySession.policyKey(request.profile)

    DisposableEffect(owner, request) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dismiss() }
        owner.lifecycle.addObserver(observer)
        onDispose { cancelPlayback(); owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(request, unlocked, retry) {
        if (!unlocked) return@LaunchedEffect
        sources = null; message = null; target = null; lookupSession = null; candidates = emptyList(); confirmation = null
        try {
            val session = DiscoverySession.open(app, request.profile)
            val known = request.tmdb?.takeIf { Regex("[1-9][0-9]{0,9}").matches(it) }
            val loaded = catalogReleaseLookup(metadata = {
                when {
                    selectedMetadata != null -> client.details(selectedMetadata!!)
                    known != null -> client.catalogTitle(request.kind, known, request.name, request.year)
                    else -> {
                        val matches = guard.filterTitles(client.catalogCandidates(request.kind, request.name))
                        session.verify()
                        currentCoroutineContext().ensureActive()
                        val exact = request.year?.let { year -> matches.filter { it.year == year }.singleOrNull() }
                        if (exact == null) {
                            candidates = matches
                            sources = emptyList()
                            message = if (matches.isEmpty()) "No exact metadata matches. Try searching the title or use IPTV." else "Choose the correct title and year before checking releases."
                            return@catalogReleaseLookup null
                        }
                        client.details(exact)
                    }
                }
            }, allowed = guard::allowed, target = request::target, verify = {
                session.verify()
                if (!currentProfile()) throw SourceUnavailable("The profile changed. Choose the title again.")
            }, sources = client::sources) ?: return@LaunchedEffect
            val (t, found) = loaded
            target = t; sources = found; lookupSession = session
            if (found.isEmpty()) message = "No Torrentio releases found. Try again later or play IPTV."
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            message = (e as? SourceUnavailable)?.message ?: "External sources are unavailable. Retry or play IPTV."
            sources = emptyList()
        }
    }
    fun playSource(source: SourceCandidate) {
        if (busy) return
        cancelPlayback()
        val ticket = selection.begin()
        busy = true
        selectedRelease = source
        progress = PreparationProgress("Checking the profile key…")
        playbackJob = scope.launch {
            try {
                val t = target ?: throw SourceUnavailable("Retry source lookup first.")
                val session = lookupSession ?: throw SourceUnavailable("Retry the source lookup first.")
                session.verify()
                if (!guard.allowed(t.title)) throw SourceUnavailable("The catalog policy changed. Close and choose the title again.")
                app.profileRealDebrid.prepare(session, source, t, { selection.check(ticket); message = it }, progress = { selection.check(ticket); progress = it }, beforeOpen = {
                    selection.check(ticket)
                    session.verify()
                    currentCoroutineContext().ensureActive()
                    if (!currentProfile() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !guard.allowed(t.title))
                        throw SourceUnavailable("The profile or catalog policy changed. Choose the title again.")
                }) { url ->
                    selection.check(ticket)
                    if (!currentProfile()) throw SourceUnavailable("The profile changed. Choose the title again.")
                    ctx.startActivity(request.item.putInto(Intent(ctx, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_URL, url).putExtra(PlayerActivity.EXTRA_TITLE, request.label)
                        .putExtra(PlayerActivity.EXTRA_REAL_DEBRID, true)))
                    currentClose()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                message = (e as? SourceUnavailable)?.message ?: "Cannot prepare this source. Check the profile API key in Settings, or choose IPTV."
            } finally {
                runCatching { selection.check(ticket) }.onSuccess { busy = false }
            }
        }
    }
    fun playIptv() {
        cancelPlayback()
        val ticket = selection.begin()
        playbackJob = scope.launch {
            if (!currentProfile()) { message = "The profile changed. Close and choose the title again."; return@launch }
            try {
                request.iptv {
                    selection.check(ticket)
                    if (!currentProfile() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        throw SourceUnavailable("The profile or source selection changed. Choose the title again.")
                }
                selection.check(ticket)
                currentClose()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                runCatching { selection.check(ticket) }.onSuccess {
                    message = "This IPTV link is unavailable. Try another catalog copy or Torrentio release."
                }
            }
        }
    }
    val height = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SourceChoiceLayout(request.label, request.name, request.year, request.poster, request.overview,
            Modifier.fillMaxWidth(0.94f).widthIn(max = 1100.dp).heightIn(max = height)) {
            if (!busy) SourceChoiceHeader(request.label, request.name, ::playIptv, { cancelPlayback(); expanded = !expanded })
            if (!unlocked) {
                Text("Unlock to discover external releases. IPTV remains available.", color = Tv.Muted)
                PinPrompt(request.profile) { unlocked = true }
            } else {
                if (sources == null) Text("Checking metadata and Torrentio releases…", color = Tv.Muted)
                message?.let { Text(it, color = Tv.Warn) }
                if (busy) {
                    SourcePreparationCard(request.label, selectedRelease?.label.orEmpty(), progress ?: PreparationProgress("Checking the selected source…"), ::playIptv) {
                        cancelPlayback(); message = "Waiting cancelled. A torrent already added may remain in the account."
                    }
                }
                if (expanded && !busy) {
                    candidates.forEach { candidate ->
                        SourceChoiceCard("${candidate.title} · ${candidate.year ?: "year unknown"} · ${if (candidate.kind == "tv") "Series" else "Movie"}", {
                            cancelPlayback(); selectedMetadata = candidate; retry++
                        }, Modifier.fillMaxWidth())
                    }
                    val confirm = confirmation
                    if (confirm != null) {
                        Text("Compatibility unverified: ${confirm.label}", color = Tv.Warn)
                        TvButton("Play this release anyway", { confirmation = null; playSource(confirm) })
                        TvButton("Back to releases", { confirmation = null })
                    } else sources.orEmpty().forEach { source ->
                        SourceChoiceCard(source.label, { if (source.compatible) playSource(source) else { cancelPlayback(); confirmation = source } }, Modifier.fillMaxWidth())
                    }
                }
                if (sources != null && !busy) TvButton("Retry external lookup", { cancelPlayback(); retry++ })
            }
            TvButton("Close", ::dismiss)
        }
    }
}

/** Shared, network-independent manual choices: IPTV is available even during provider failures. */
@Composable
internal fun SourceChoiceHeader(label: String, iptvName: String, onIptv: () -> Unit, onReleases: () -> Unit) {
    val first = remember(label, iptvName) { FocusRequester() }
    Text("Play $label from…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    SourceChoiceCard("▶  IPTV · $iptvName", onIptv, Modifier.fillMaxWidth().focusRequester(first))
    Text("Your existing IPTV link · original resume and watched history", fontSize = 13.sp, color = Tv.Muted)
    SourceChoiceCard("Real-Debrid / Torrentio · Choose a release", onReleases, Modifier.fillMaxWidth())
    Text("Release quality and size come from Torrentio. Availability, compatibility and any quality improvement are unverified. A saved profile key is not proof of authentication.", fontSize = 13.sp, color = Tv.Muted)
    LaunchedEffect(label, iptvName) {
        withFrameNanos { }
        first.requestFocus()
    }
}
