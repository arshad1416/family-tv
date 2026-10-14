package app.familytv

import android.app.Application
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

    override fun onCreate() {
        super.onCreate()
        // Background check-in while the app is closed: picks up portal changes and keeps lists fresh.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).build(),
        )
    }
}

val android.content.Context.app: FamilyTvApp get() = applicationContext as FamilyTvApp
