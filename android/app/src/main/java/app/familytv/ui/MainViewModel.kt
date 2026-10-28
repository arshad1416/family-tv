package app.familytv.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.familytv.app
import app.familytv.diag.DiagnosisReport
import app.familytv.core.Http
import app.familytv.iptv.CatalogWork
import app.familytv.iptv.ServerSwitch
import app.familytv.perf.Perf
import app.familytv.portal.Command
import app.familytv.portal.DeviceConfig
import app.familytv.portal.LatestApp
import app.familytv.portal.PortalException
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import app.familytv.trailer.Tmdb
import app.familytv.transfer.Transfer
import app.familytv.update.Updater
import app.familytv.usage.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Loading : Screen
    data class Pairing(val code: String, val expires: Long, val unpaired: Boolean = false) : Screen
    data class Syncing(val step: String, val fraction: Float = 0f, val detail: String? = null) : Screen
    data class Ready(val config: DeviceConfig) : Screen
    data class Failed(val message: String) : Screen
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    val diagnosis = MutableStateFlow<DiagnosisReport?>(null)
    val diagnosing = MutableStateFlow(false)

    private var loop: Job? = null
    /** Portal commands that came in while lists were loading. */
    private var heldCommands: List<Command> = emptyList()

    val profile: Profile? get() = (screen.value as? Screen.Ready)?.config?.profile

    fun start() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            boot()
            // While the app is open, check in every 30 s so portal actions land quickly.
            while (isActive) {
                delay(30_000)
                checkIn()
            }
        }
    }

    fun stop() { loop?.cancel() }

    /**
     * Opened by the Rust app with a move code ([Transfer]): taken in at once on a TV that is set up, else on the
     * next boot (at once if pairing shows now).
     */
    fun transfer(code: String?) {
        if (!Transfer.offer(code)) return
        when {
            loop?.isActive != true -> Unit // the boot takes it
            _screen.value is Screen.Pairing -> { stop(); start() }
            app.portal.cachedConfig != null -> viewModelScope.launch {
                takeTransfer()
                app.portal.cachedConfig?.profile?.let { applyTransfer(it) }
            }
        }
    }

    /**
     * Takes a waiting move code: claimed on a TV that isn't set up yet (the pairing screen shows as usual if that
     * fails), absorbed on one that is, which removes the Rust app's registration.
     */
    private suspend fun takeTransfer() {
        val code = Transfer.take() ?: return
        val paired = app.portal.cachedConfig != null
        runCatching { if (paired) app.portal.absorbTransfer(code) else app.portal.claimTransfer(code) }
            .onSuccess { Transfer.claimed = Transfer.Claimed(Transfer.parse(it) ?: Transfer.Local(), paired) }
            .onFailure { Log.w("Transfer", "move from the Rust app failed: ${(it as? PortalException)?.code ?: it.javaClass.simpleName}") }
    }

    private suspend fun applyTransfer(profile: Profile) {
        val claimed = Transfer.claimed ?: return
        Transfer.claimed = null
        runCatching { Transfer.apply(app, claimed, profile) }
    }

    private suspend fun boot() {
        takeTransfer()
        val config = try {
            app.portal.config()
        } catch (e: Exception) {
            // Portal unreachable: keep working from the last known setup.
            app.portal.cachedConfig ?: run {
                _screen.value = Screen.Failed("Can't reach the setup service. Check the internet connection.")
                Perf.skipColdStart()
                return
            }
        }
        if (!config.paired) {
            _screen.value = Screen.Pairing(config.pairCode.orEmpty(), config.pairExpires ?: 0, app.portal.unpaired)
            Perf.skipColdStart()
            return
        }
        val profile = config.profile ?: return
        applyTransfer(profile)
        val firstLoad = app.db.catalog().channelCount() == 0
        if (firstLoad) {
            _screen.value = Screen.Syncing("Loading channels")
            Perf.skipColdStart()
            try {
                app.catalog.refresh(profile) { _screen.value = Screen.Syncing(it.step, it.fraction, it.detail) }
                PlaybackStatus.lastRefresh = System.currentTimeMillis()
            } catch (e: CancellationException) {
                // The app closed mid-load: not a line error. The next start finishes it (below).
                throw e
            } catch (e: Exception) {
                PlaybackStatus.lastRefreshError = e.message
                _screen.value = Screen.Ready(config)
                runDiagnosis()
                return
            }
        }
        _screen.value = Screen.Ready(config)
        // Lists from a load that was cut off: finish it behind the home, where closing the app can't stop it.
        if (!firstLoad && !app.catalog.complete) app.scope.launch { runCatching { app.commands.refreshCatalog(profile) } }
        // Pull favourites, watched marks and resume points from Trakt on every start and reload.
        app.trakt.refreshState()
        app.trakt.sync.soon(0)
        app.sharedProgress.soon(0)
        // Recount the episodes waiting on favourite shows once Trakt's watched marks have had a moment.
        app.scope.launch { delay(60_000); runCatching { app.newEpisodes.checkIfDue(profile) } }
        // TMDB's most-voted lists for the search order, weekly, well clear of the start; the fade timing waits it out.
        app.scope.launch {
            delay(MOST_VOTED_AFTER_MS)
            val key = app.portal.cachedConfig?.tmdbApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
            runCatching { CatalogWork.during { app.mostVoted.refreshIfDue(Tmdb(key, Http.client)) } }
        }
        app.commands.runAll(config.commands, profile)
        offerUpdate(config.latestApp)
    }

    private suspend fun checkIn() {
        when (val s = _screen.value) {
            is Screen.Pairing, is Screen.Failed -> boot()
            // Reload channels takes minutes on a Fire Stick: still check in, so the portal doesn't show the TV as
            // offline. What the portal sent waits for the home.
            is Screen.Syncing, Screen.Loading -> if (app.portal.cachedConfig != null) runCatching {
                heldCommands += app.portal.heartbeat(PlaybackStatus.snapshot()).commands.orEmpty()
            }
            is Screen.Ready -> runCatching {
                val hb = app.portal.heartbeat(PlaybackStatus.snapshot())
                // Unpaired in the portal: show the pairing code again (same device, no re-register).
                if (!hb.paired) { boot(); return }
                app.commands.runAll(heldCommands + hb.commands.orEmpty(), s.config.profile)
                heldCommands = emptyList()
                app.sharedProgress.onSeq(hb.progressSeq)
                offerUpdate(hb.latestApp)
                Usage.send(app.portal)
                s.config.profile?.let { ServerSwitch.retryFirst(it, app.db) }
            }.onFailure {
                // Token rejected: the portal forgot this TV, so show why with a new code.
                if ((it as? PortalException)?.code == 401) boot()
            }
        }
    }

    private suspend fun offerUpdate(latest: LatestApp?) {
        runCatching { Updater.check(app, latest, visible = true) }
            .onSuccess { PlaybackStatus.update = it }
            .onFailure { PlaybackStatus.update = "failed: ${it.message}" }
    }

    fun reload() = viewModelScope.launch {
        Usage.log(Usage.Action.SETTING_CHANGE, Usage.Word.RELOAD_CHANNELS)
        _screen.value = Screen.Loading
        app.db.clearAllTablesSafely()
        boot()
    }

    fun runDiagnosis() = viewModelScope.launch {
        val p = profile ?: app.portal.cachedConfig?.profile ?: return@launch
        Usage.log(Usage.Action.SETTING_CHANGE, Usage.Word.RUN_CHECK)
        diagnosing.value = true
        try {
            diagnosis.value = app.diagnoser.runAndReport(p, playingHere = false)
        } finally {
            diagnosing.value = false
        }
    }
}

private suspend fun app.familytv.data.AppDb.clearAllTablesSafely() =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { clearAllTables() }

/** How long after the start TMDB's most-voted lists may be fetched (100 small requests, once a week). */
private const val MOST_VOTED_AFTER_MS = 3 * 60_000L
