package app.familytv.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import app.familytv.app
import app.familytv.core.Http
import app.familytv.discovery.*
import app.familytv.portal.Profile
import app.familytv.portal.PortalException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Metadata season/episode browser and profile API-key settings. */
@Composable
internal fun ExternalDiscovery(profile: Profile, query: String, onClose: () -> Unit, accountOnly: Boolean = false, initialTitle: DiscoveryTitle? = null) {
    val ctx = LocalContext.current
    val app = ctx.app
    val scope = rememberCoroutineScope()
    var unlocked by remember(profile) { mutableStateOf(profile.parentalPin.isNullOrEmpty()) }
    var session by remember(profile) { mutableStateOf<DiscoverySession?>(null) }
    var titles by remember(profile, query) { mutableStateOf<List<DiscoveryTitle>?>(null) }
    var show by remember(profile) { mutableStateOf<DiscoveryTitle?>(null) }
    var episodes by remember(profile) { mutableStateOf<List<DiscoveryEpisode>?>(null) }
    var target by remember(profile) { mutableStateOf<DiscoveryTarget?>(null) }
    var sources by remember(profile) { mutableStateOf<List<SourceCandidate>?>(null) }
    var confirmSource by remember(profile) { mutableStateOf<SourceCandidate?>(null) }
    var keyStatus by remember(profile) { mutableStateOf<ProfileRdStatus?>(null) }
    var enteredKey by remember(profile) { mutableStateOf("") }
    var confirmRemove by remember(profile) { mutableStateOf(false) }
    var connected by remember(profile) { mutableStateOf(false) }
    var preparationProgress by remember(profile) { mutableStateOf<PreparationProgress?>(null) }
    var selectedRelease by remember(profile) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val client = remember { DiscoveryClient(Http.client, app.portal.cachedConfig?.tmdbApiKey.orEmpty()) }
    val guard = remember(profile) { CatalogDiscoveryGuard(app.db, profile) }
    val currentClose by rememberUpdatedState(onClose)

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        busy = false
        preparationProgress = null
        selectedRelease = null
    }
    fun work(block: suspend (DiscoverySession) -> Unit) {
        cancel()
        confirmRemove = false
        val own = generation
        busy = true
        message = null
        job = scope.launch {
            try {
                val s = session ?: DiscoverySession.open(app, profile).also { session = it }
                s.verify()
                if (accountOnly) {
                    keyStatus = app.profileRealDebrid.status(s)
                    connected = keyStatus?.configured == true
                } else connected = true // Key is checked only when a viewer selects a release.
                block(s)
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (own == generation) {
                    message = (e as? SourceUnavailable)?.message ?: if (e is PortalException) e.message else "This request could not be completed. Try again later."
                    if (e is PortalException && e.code == 409) { keyStatus = null; connected = false; enteredKey = "" }
                }
            } finally {
                if (own == generation) busy = false
            }
        }
    }
    fun search() = work { s ->
        if (!accountOnly) {
            val results = client.search(query)
            s.verify()
            titles = guard.filterTitles(results)
            if (titles.isNullOrEmpty()) message = "No available movies or series matched."
        }
    }
    fun choose(t: DiscoveryTarget) = work { s ->
        if (!guard.allowed(t.title)) throw SourceUnavailable("This title is hidden by the current catalog policy.")
        s.verify()
        currentCoroutineContext().ensureActive()
        val found = client.sources(t)
        s.verify()
        target = t
        sources = found
        confirmSource = null
        if (found.isEmpty()) message = "No sources were returned for this title. Try again later."
    }
    fun play(source: SourceCandidate) = work { s ->
        val t = target ?: throw SourceUnavailable("Choose a title first.")
        if (!guard.allowed(t.title)) throw SourceUnavailable("This title is hidden by the current catalog policy.")
        selectedRelease = source.label
        preparationProgress = PreparationProgress("Checking the profile key…")
        app.profileRealDebrid.prepare(s, source, t, { message = it }, progress = { preparationProgress = it }, beforeOpen = {
            s.verify()
            currentCoroutineContext().ensureActive()
            if (!guard.allowed(t.title)) throw SourceUnavailable("The catalog policy changed. Unlock again.")
        }) { url ->
            ctx.startActivity(t.playItem().putInto(Intent(ctx, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_URL, url).putExtra(PlayerActivity.EXTRA_TITLE, t.label)
                .putExtra(PlayerActivity.EXTRA_REAL_DEBRID, true)))
            enteredKey = ""
            currentClose()
        }
    }

    fun openTitle(t: DiscoveryTitle) = work { s ->
                                    val details = client.details(t)
                                    s.verify()
                                    if (!guard.allowed(details)) throw SourceUnavailable("This title is hidden by the catalog policy.")
                                    if (details.kind == "tv") { show = details; episodes = null; if (details.seasons.isEmpty()) message = "No seasons are listed for this series." }
                                    else {
                                        val next = DiscoveryTarget(details)
                                        s.verify()
                                        currentCoroutineContext().ensureActive()
                                        val found = client.sources(next)
                                        s.verify()
                                        target = next; sources = found
                                        if (found.isEmpty()) message = "No sources were returned."
                                    }
                                }

    // LifecycleScope survives STOP. Explicitly cancel this flow when Home/another app takes over.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, profile, query) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { cancel(); unlocked = profile.parentalPin.isNullOrEmpty(); session = null; currentClose() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { job?.cancel(); owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(unlocked, initialTitle) { if (unlocked) { if (initialTitle != null) openTitle(initialTitle) else search() } }

    val height = LocalConfiguration.current.screenHeightDp.dp * 0.92f
    Dialog(onDismissRequest = { cancel(); onClose() }, properties = DialogProperties(usePlatformDefaultWidth = accountOnly)) {
        val content: @Composable ColumnScope.() -> Unit = {
            Text(if (accountOnly) "Real-Debrid API key for this profile" else "Choose a Torrentio release", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (!unlocked) {
                Text("External titles are not age rated. Unlock this window before discovery and playback.", color = Tv.Muted)
                PinPrompt(profile) { unlocked = true }
            } else {
                if (!accountOnly) Text("Online metadata result. For an IPTV link, choose the catalog movie or episode from Search.", fontSize = 13.sp, color = Tv.Muted)
                Text("TMDB metadata · Torrentio sources · the account owner’s Real-Debrid account on devices in this profile. Only choose sources you are entitled to play.", fontSize = 13.sp, color = Tv.Muted)
                val first = remember { FocusRequester() }
                LaunchedEffect(titles, show, episodes, sources, busy, connected, confirmSource) { if (!busy) runCatching { first.requestFocus() } }
                message?.let { Text(it, color = if (busy) Tv.Muted else Tv.Warn, fontSize = 15.sp) }
                if (busy) {
                    val progress = preparationProgress
                    if (!accountOnly && progress != null) {
                        SourcePreparationCard(target?.label ?: query, selectedRelease.orEmpty(), progress, null) {
                            cancel(); message = "Waiting cancelled. Preparation may continue in Real-Debrid; you can return later."
                        }
                    } else {
                        Text(if (accountOnly) "Checking profile key status…" else "Checking metadata and sources…", color = Tv.Muted)
                        TvButton("Cancel request", { cancel(); message = "Request cancelled. Changes already submitted may have completed." }, Modifier.focusRequester(first))
                    }
                } else {
                    if (accountOnly) {
                        Text(if (connected) "API key saved for this profile (hidden; authentication unverified)." else "No external-source API key is configured for this profile.", fontSize = 14.sp)
                        Text("Save, replace or remove affects all authorized devices in this same profile. Use your own devices only; Real-Debrid accounts must not be shared with other people. The existing library sign-in is separate.", fontSize = 13.sp, color = Tv.Muted)
                        if (accountOnly) Text("Remove deletes the saved profile key; it does not revoke the key at Real-Debrid or stop streams already playing.", fontSize = 13.sp, color = Tv.Muted)
                        if (accountOnly) {
                            Text("${if (connected) "Replacement" else "Real-Debrid"} API key", fontSize = 14.sp)
                            BasicTextField(enteredKey, { if (it.length <= 512) enteredKey = it }, Modifier.fillMaxWidth().background(Tv.Panel, Tv.Shape).padding(16.dp),
                                textStyle = TextStyle(color = Tv.Text, fontSize = 18.sp), singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                            TvButton("Paste API key", {
                                val value = runCatching {
                                    val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = clipboard?.primaryClip
                                    pastedApiKey(clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text)
                                }.getOrNull()
                                if (value == null) message = "Clipboard must contain a 16–512 character API key without spaces."
                                else { enteredKey = value; message = "API key pasted (hidden). Review and save when ready." }
                            })
                            TvButton(if (connected) "Replace key for all profile devices" else "Save key for all profile devices", {
                                val expected = keyStatus
                                val value = enteredKey
                                enteredKey = ""
                                if (expected == null) { search(); message = "Review the refreshed account status and enter the key again." }
                                else work { s ->
                                    keyStatus = app.profileRealDebrid.replace(s, expected, value)
                                    connected = true
                                    message = "Key saved for the account owner’s devices in this profile; authentication is unverified."
                                }
                            }, Modifier.focusRequester(first), enabled = ProfileRealDebrid.validKey(enteredKey))
                            if (connected) TvButton(if (confirmRemove) "Confirm removal from every profile device" else "Remove key from all profile devices", {
                                if (!confirmRemove) confirmRemove = true
                                else {
                                    val expected = keyStatus
                                    confirmRemove = false
                                    if (expected != null) work { s -> keyStatus = app.profileRealDebrid.remove(s, expected); connected = false; message = "Profile key removed." }
                                }
                            })
                        } else if (!connected) Text("Open Settings → External movie & series sources to enter the API key.", color = Tv.Warn)
                        TvButton("Reload profile key status", ::search)
                    }
                    when {
                        sources != null -> {
                            Text(target?.label.orEmpty(), fontWeight = FontWeight.Bold)
                            Text("1080p / 720p SDR AVC candidates appear first. Labels come from release names; device compatibility and availability are unverified. Playback may add the selected torrent to the profile owner’s account.", fontSize = 13.sp, color = Tv.Muted)
                            val confirm = confirmSource
                            if (confirm != null) {
                                Text("This source may use 4K, HDR or a codec this TV cannot play. ${confirm.label}", color = Tv.Warn)
                                TvButton("Play selected source anyway", { play(confirm) }, enabled = connected)
                                TvButton("Back to sources", { confirmSource = null })
                            } else sources.orEmpty().forEachIndexed { i, source ->
                                SourceChoiceCard((if (source.compatible) "▶  SDR AVC candidate · " else "Compatibility unverified · ") + source.label,
                                    { if (source.compatible) play(source) else confirmSource = source },
                                    if (i == 0 && connected) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth())
                            }
                            TvButton("Back to titles", { sources = null; target = null; confirmSource = null }, if (!connected || sources.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier)
                        }
                        episodes != null -> {
                            episodes.orEmpty().forEachIndexed { i, e ->
                                TvButton("S${e.season} E${e.number} · ${e.name}", { show?.let { choose(DiscoveryTarget(it, e.season, e.number, e.name)) } }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                            }
                            TvButton("Back to seasons", { episodes = null })
                        }
                        show != null -> {
                            show?.seasons.orEmpty().forEachIndexed { i, season ->
                                TvButton(if (season == 0) "Specials" else "Season $season", { work { s ->
                                    val t = show ?: return@work
                                    val own = generation
                                    loadVerifiedEpisodes(t, season, client::episodes, s::verify, guard::allowed,
                                        current = { generation == own && show === t },
                                        publish = { loaded ->
                                            episodes = loaded
                                            if (loaded.isEmpty()) message = "No episodes are listed in this season."
                                        })
                                } }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                            }
                            TvButton("Back to titles", { show = null })
                        }
                        !accountOnly -> titles.orEmpty().forEachIndexed { i, t ->
                            TvButton("${t.title}${t.year?.let { " ($it)" }.orEmpty()} · ${if (t.kind == "tv") "Series" else "Movie"}", {
                                openTitle(t)
                            }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                        }
                    }
                    if (!accountOnly && titles == null) TvButton("Try search again", ::search)
                }
            }
            TvButton("Close", { cancel(); onClose() })
        }
        if (accountOnly) Column(Modifier.widthIn(min = 300.dp, max = 760.dp).heightIn(max = height).background(Tv.Raised, Tv.Shape)
            .verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        else {
            val title = target?.title ?: show ?: initialTitle
            SourceChoiceLayout(target?.label ?: title?.title ?: query, title?.title ?: query, title?.year, title?.poster, title?.overview,
                Modifier.fillMaxWidth(0.94f).widthIn(max = 1100.dp).heightIn(max = height), content)
        }
    }
}
