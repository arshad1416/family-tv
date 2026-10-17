package app.familytv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.familytv.app
import app.familytv.core.ForegroundWork
import app.familytv.diag.DiagnosisReport
import app.familytv.portal.DeviceConfig
import app.familytv.portal.LatestApp
import app.familytv.portal.Profile
import app.familytv.sync.PlaybackStatus
import app.familytv.update.Updater
import kotlinx.coroutines.CancellationException
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

internal interface MainMaintenance {
    suspend fun clearCatalog()
    suspend fun diagnose(profile: Profile): DiagnosisReport
    suspend fun syncTrakt()
    suspend fun syncProgress(sequence: Long?)
}

private class AppMainMaintenance(private val app: app.familytv.FamilyTvApp) : MainMaintenance {
    override suspend fun clearCatalog() = app.db.clearAllTablesSafely()
    override suspend fun diagnose(profile: Profile) = app.diagnoser.runAndReport(profile, playingHere = false)
    override suspend fun syncTrakt() = app.trakt.sync.now()
    override suspend fun syncProgress(sequence: Long?) {
        if (sequence == null) app.sharedProgress.sync()
        else app.sharedProgress.syncIfNewer(sequence)
    }
}

class MainViewModel internal constructor(application: Application, private val maintenance: MainMaintenance) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppMainMaintenance(application.app))
    private val app = application.app
    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    val diagnosis = MutableStateFlow<DiagnosisReport?>(null)
    val diagnosing = MutableStateFlow(false)

    private val foregroundWork by lazy { ForegroundWork(viewModelScope) }
    private var loop: Job? = null
    private var episodeCheck: Job? = null
    private var reloadJob: Job? = null
    private var diagnosisJob: Job? = null
    private var traktSyncJob: Job? = null
    private var progressSyncJob: Job? = null

    val profile: Profile? get() = (screen.value as? Screen.Ready)?.config?.profile

    fun start() {
        foregroundWork.start()
        if (loop?.isActive == true) return
        loop = foregroundWork.launch {
            boot(::ensureCurrent)
            // While the app is open, check in every 30 s so portal actions land quickly.
            while (true) {
                delay(30_000)
                checkIn(::ensureCurrent)
            }
        }
    }

    fun stop() {
        foregroundWork.stop()
        diagnosing.value = false
    }

    private suspend fun boot(current: () -> Unit) {
        current()
        val config = try {
            app.portal.config()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current()
            // Portal unreachable: keep working from the last known setup.
            app.portal.cachedConfig ?: run {
                _screen.value = Screen.Failed("Can't reach the setup service. Check the internet connection.")
                return
            }
        }
        current()
        if (!config.paired) {
            _screen.value = Screen.Pairing(config.pairCode.orEmpty(), config.pairExpires ?: 0)
            return
        }
        val profile = config.profile ?: return
        if (app.db.catalog().channelCount() == 0) {
            current()
            _screen.value = Screen.Syncing("Loading channels")
            try {
                app.catalog.refresh(profile) { current(); _screen.value = Screen.Syncing(it.step, it.fraction, it.detail) }
                current()
                PlaybackStatus.lastRefresh = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                current()
                PlaybackStatus.lastRefreshError = e.message
                _screen.value = Screen.Ready(config)
                runDiagnosis()
                return
            }
        }
        current()
        _screen.value = Screen.Ready(config)
        // Pull favourites, watched marks and resume points from Trakt on every start and reload.
        app.trakt.refreshState()
        startAccountSync()
        app.realDebrid.onConfig(config.realDebrid)
        // Recount the episodes waiting on favourite shows once Trakt's watched marks have had a moment.
        episodeCheck?.cancel()
        episodeCheck = foregroundWork.launch {
            delay(60_000)
            ensureCurrent()
            runCatching { app.newEpisodes.checkIfDue(profile) }.onFailure { if (it is CancellationException) throw it }
        }
        app.commands.runAll(config.commands, profile)
        current()
        offerUpdate(config.latestApp, current)
    }

    private fun startAccountSync() {
        if (traktSyncJob?.isActive != true) traktSyncJob = foregroundWork.launch {
            ensureCurrent()
            runCatching { maintenance.syncTrakt() }.onFailure { if (it is CancellationException) throw it }
        }
        startProgressSync(null)
    }

    private fun startProgressSync(sequence: Long?) {
        if (progressSyncJob?.isActive == true) return
        progressSyncJob = foregroundWork.launch {
            ensureCurrent()
            runCatching { maintenance.syncProgress(sequence) }.onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun checkIn(current: () -> Unit) {
        current()
        when (val s = _screen.value) {
            is Screen.Pairing, is Screen.Failed -> boot(current)
            is Screen.Ready -> runCatching {
                val hb = app.portal.heartbeat(PlaybackStatus.snapshot())
                current()
                // Unpaired in the portal: show the pairing code again (same device, no re-register).
                if (!hb.paired) { boot(current); return }
                app.commands.runAll(hb.commands, s.config.profile)
                current()
                hb.progressSeq?.let { startProgressSync(it) }
                offerUpdate(hb.latestApp, current)
            }.onFailure { if (it is CancellationException) throw it }
            else -> Unit
        }
    }

    private suspend fun offerUpdate(latest: LatestApp?, current: () -> Unit) {
        current()
        runCatching { Updater.check(app, latest, visible = true) }
            .onSuccess { result -> current(); result?.let { PlaybackStatus.update = it } }
            .onFailure {
                if (it is CancellationException) throw it
                current()
                PlaybackStatus.update = "failed: ${it.message}"
            }
    }

    fun reload(): Job {
        reloadJob?.takeIf { it.isActive }?.let { return it }
        return foregroundWork.launch {
            ensureCurrent()
            _screen.value = Screen.Loading
            maintenance.clearCatalog()
            ensureCurrent()
            boot(::ensureCurrent)
        }.also { reloadJob = it }
    }

    fun runDiagnosis(): Job {
        diagnosisJob?.takeIf { it.isActive }?.let { return it }
        return foregroundWork.launch {
            ensureCurrent()
            val p = profile ?: app.portal.cachedConfig?.profile ?: return@launch
            diagnosing.value = true
            try {
                val report = maintenance.diagnose(p)
                dispatch { diagnosis.value = report }
            } finally {
                dispatch { diagnosing.value = false }
            }
        }.also { diagnosisJob = it }
    }
}

private suspend fun app.familytv.data.AppDb.clearAllTablesSafely() =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { clearAllTables() }
