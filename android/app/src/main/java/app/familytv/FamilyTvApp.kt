package app.familytv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.familytv.core.CrashLog
import app.familytv.data.AppDb
import app.familytv.diag.Diagnoser
import app.familytv.iptv.Catalog
import app.familytv.iptv.ServerSwitch
import app.familytv.perf.Perf
import app.familytv.portal.PortalClient
import app.familytv.scores.LiveScores
import app.familytv.sync.CommandRunner
import app.familytv.sync.NewEpisodes
import app.familytv.sync.PlaybackStatus
import app.familytv.sync.SyncWorker
import app.familytv.sync.TvLock
import app.familytv.trailer.MediaExtras
import app.familytv.trakt.Trakt
import app.familytv.usage.Usage
import app.familytv.user.Favourites
import app.familytv.user.GlassPrefs
import app.familytv.user.LastChannels
import app.familytv.user.MyCategoryOrder
import app.familytv.user.SharedProgress
import app.familytv.user.UserDb
import app.familytv.user.Viewers
import app.familytv.user.QualityPrefs
import app.familytv.user.WatchedMarks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class FamilyTvApp : Application(), ImageLoaderFactory {
    val db by lazy { AppDb.create(this) }
    val portal by lazy { PortalClient(this) }
    val catalog by lazy { Catalog(db, getSharedPreferences("catalog", MODE_PRIVATE)) }
    val diagnoser by lazy { Diagnoser(this, db, portal) }
    val commands by lazy { CommandRunner(this) }
    /** Favourites, resume points and watched marks; kept apart from the catalog so reloads never touch them. */
    val userDb by lazy { UserDb.create(this) }
    val favourites by lazy { Favourites(this) }
    val trakt by lazy { Trakt(this) }
    /** Trailers and episode details beyond the provider (TMDB, TheTVDB). */
    val extras by lazy { MediaExtras(this) }
    val watched by lazy { WatchedMarks(this) }
    val sharedProgress by lazy { SharedProgress(this) }
    val newEpisodes by lazy { NewEpisodes(this) }
    val myCategoryOrder by lazy { MyCategoryOrder(this) }
    /** Settings' Stream quality for Live TV, Movies and Series. */
    val quality by lazy { QualityPrefs(this) }
    /** The TV home's Appearance, weather city and world clocks. */
    val glassPrefs by lazy { GlassPrefs(this) }
    /** The last two live channels per profile, for Left's previous channel. */
    val lastChannels by lazy { LastChannels(this) }
    val liveScores by lazy { LiveScores(portal) }
    val viewers by lazy { Viewers(this) }
    /** The parental PIN was entered (Settings or Adult Content): adult content opens without it until the app restarts. */
    val adultUnlocked = MutableStateFlow(false)

    /** For work that must outlive a screen, like the stop scrobble sent as the player closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while one of our screens is showing; background updates wait for the app to close. */
    val visible: Boolean get() = started > 0
    @Volatile private var started = 0

    /** Counts each time one of our screens comes back into view, so a delayed step can tell the viewer returned. */
    @Volatile var visits = 0
        private set

    override fun onCreate() {
        super.onCreate()
        Perf.init(this)
        CrashLog.install(this)
        CrashLog.last(this)?.let { (trace, at) -> PlaybackStatus.lastCrash = trace; PlaybackStatus.lastCrashAt = at }
        Usage.enabled = portal.cachedConfig?.profile?.usageStats == true
        ServerSwitch.init(this)
        TvLock.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; visits++ }
            override fun onActivityStopped(a: Activity) {
                // Leaving the app (or Exit): send the usage stats now, the next check-in may be a while.
                if (--started == 0) scope.launch { Usage.send(portal) }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        // Background check-in while the app is closed: picks up portal changes and keeps lists fresh.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build(),
        )
    }

    /**
     * Posters and logos: Coil's default memory cache takes a quarter of the app's memory, which
     * on a 2-3 GB TV box like the onn pushes the system into swap while video plays.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.1).build() }
        .build()
}

val android.content.Context.app: FamilyTvApp get() = applicationContext as FamilyTvApp
