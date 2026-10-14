package app.familytv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.familytv.data.AppDb
import app.familytv.diag.Diagnoser
import app.familytv.iptv.Catalog
import app.familytv.portal.PortalClient
import app.familytv.sync.CommandRunner
import app.familytv.sync.SyncWorker
import java.util.concurrent.TimeUnit

class FamilyTvApp : Application() {
    val db by lazy { AppDb.create(this) }
    val portal by lazy { PortalClient(this) }
    val catalog by lazy { Catalog(db) }
    val diagnoser by lazy { Diagnoser(this, db, portal) }
    val commands by lazy { CommandRunner(this) }

    /** True while one of our screens is showing; background updates wait for the app to close. */
    val visible: Boolean get() = started > 0
    @Volatile private var started = 0

    override fun onCreate() {
        super.onCreate()
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
