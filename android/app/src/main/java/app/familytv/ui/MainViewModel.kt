package app.familytv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.familytv.app
import app.familytv.diag.DiagnosisReport
import app.familytv.portal.DeviceConfig
import app.familytv.portal.LatestApp
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import app.familytv.update.Updater
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Loading : Screen
    data class Pairing(val code: String, val expires: Long) : Screen
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

    private suspend fun boot() {
        val config = try {
            app.portal.config()
        } catch (e: Exception) {
            // Portal unreachable: keep working from the last known setup.
            app.portal.cachedConfig ?: run {
                _screen.value = Screen.Failed("Can't reach the setup service. Check the internet connection.")
                return
            }
        }
        if (!config.paired) {
            _screen.value = Screen.Pairing(config.pairCode.orEmpty(), config.pairExpires ?: 0)
            return
        }
        val profile = config.profile ?: return
        if (app.db.catalog().channelCount() == 0) {
            _screen.value = Screen.Syncing("Loading channels")
            try {
                app.catalog.refresh(profile) { _screen.value = Screen.Syncing(it.step, it.fraction, it.detail) }
                PlaybackStatus.lastRefresh = System.currentTimeMillis()
            } catch (e: Exception) {
                PlaybackStatus.lastRefreshError = e.message
                _screen.value = Screen.Ready(config)
                runDiagnosis()
                return
            }
        }
        _screen.value = Screen.Ready(config)
        // Pull favourites, watched marks and resume points from Trakt on every start and reload.
        app.trakt.refreshState()
        app.trakt.sync.soon(0)
        app.sharedProgress.soon(0)
        // Recount the episodes waiting on favourite shows once Trakt's watched marks have had a moment.
        app.scope.launch { delay(60_000); runCatching { app.newEpisodes.checkIfDue(profile) } }
        app.commands.runAll(config.commands, profile)
        offerUpdate(config.latestApp)
    }

    private suspend fun checkIn() {
        when (val s = _screen.value) {
            is Screen.Pairing, is Screen.Failed -> boot()
            is Screen.Ready -> runCatching {
                val hb = app.portal.heartbeat(PlaybackStatus.snapshot())
                // Unpaired in the portal: show the pairing code again (same device, no re-register).
                if (!hb.paired) { boot(); return }
                app.commands.runAll(hb.commands, s.config.profile)
                app.sharedProgress.onSeq(hb.progressSeq)
                offerUpdate(hb.latestApp)
            }
            else -> Unit
        }
    }

    private suspend fun offerUpdate(latest: LatestApp?) {
        runCatching { Updater.check(app, latest, visible = true) }
            .onSuccess { it?.let { s -> PlaybackStatus.update = s } }
            .onFailure { PlaybackStatus.update = "failed: ${it.message}" }
    }

    fun reload() = viewModelScope.launch {
        _screen.value = Screen.Loading
        app.db.clearAllTablesSafely()
        boot()
    }

    fun runDiagnosis() = viewModelScope.launch {
        val p = profile ?: app.portal.cachedConfig?.profile ?: return@launch
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
