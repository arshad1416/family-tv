package app.familytv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.familytv.core.CrashLog
import app.familytv.data.AppDb
import app.familytv.debrid.RealDebrid
import app.familytv.discovery.ProfileRealDebrid
import app.familytv.diag.Diagnoser
import app.familytv.iptv.Catalog
import app.familytv.portal.PortalClient
import app.familytv.sync.CommandRunner
import app.familytv.sync.NewEpisodes
import app.familytv.sync.PlaybackStatus
import app.familytv.sync.SyncWorker
import app.familytv.trailer.MediaExtras
import app.familytv.trakt.Trakt
import app.familytv.user.Favourites
import app.familytv.user.MyCategoryOrder
import app.familytv.user.SharedProgress
import app.familytv.user.UserDb
import app.familytv.user.WatchedMarks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

class FamilyTvApp : Application() {
    val db by lazy { AppDb.create(this) }
    val portal by lazy { PortalClient(this) }
    val catalog by lazy { Catalog(db) }
    val diagnoser by lazy { Diagnoser(this, db, portal) }
    val commands by lazy { CommandRunner(this) }
    /** Favourites, resume points and watched marks; kept apart from the catalog so reloads never touch them. */
    val userDb by lazy { UserDb.create(this) }
    val favourites by lazy { Favourites(this) }
    val trakt by lazy { Trakt(this) }
    /** The profile's Real-Debrid account: a backup copy of movies and episodes the person added there. */
    val realDebrid by lazy { RealDebrid(this) }
    /** External sources use the owner's API key synchronized only within this profile. */
    val profileRealDebrid by lazy { ProfileRealDebrid(portal) }
    /** Trailers and episode details beyond the provider (TMDB, TheTVDB). */
    val extras by lazy { MediaExtras(this) }
    val watched by lazy { WatchedMarks(this) }
    val sharedProgress by lazy { SharedProgress(this) }
    val newEpisodes by lazy { NewEpisodes(this) }
    val myCategoryOrder by lazy { MyCategoryOrder(this) }

    /** For work that must outlive a screen, like the stop scrobble sent as the player closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True while one of our screens is showing; background updates wait for the app to close. */
    val visible: Boolean get() = started > 0
    @Volatile private var started = 0

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        CrashLog.last(this)?.let { (trace, at) -> PlaybackStatus.lastCrash = trace; PlaybackStatus.lastCrashAt = at }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++ }
            override fun onActivityStopped(a: Activity) { started-- }
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
}

val android.content.Context.app: FamilyTvApp get() = applicationContext as FamilyTvApp
