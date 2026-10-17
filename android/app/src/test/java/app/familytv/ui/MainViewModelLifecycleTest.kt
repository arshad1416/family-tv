package app.familytv.ui

import android.app.Application
import android.content.Context
import app.familytv.FamilyTvApp
import app.familytv.diag.DiagnosisReport
import app.familytv.diag.Verdict
import app.familytv.portal.DeviceConfig
import app.familytv.portal.Profile
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainViewModelLifecycleTest {
    private fun application(): FamilyTvApp {
        val app = FamilyTvApp()
        val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
        attach.isAccessible = true
        val context = object : android.content.ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = app
        }
        attach.invoke(app, context)
        app.portal.cachedConfig = DeviceConfig(true, profile = Profile())
        return app
    }
    private class PendingMaintenance : MainMaintenance {
        val diagnosisPending = CompletableDeferred<Unit>()
        val reloadPending = CompletableDeferred<Unit>()
        var diagnoses = 0
        var reloads = 0
        val syncJobs = mutableListOf<Job>()
        private val syncPending = CompletableDeferred<Unit>()
        override suspend fun syncTrakt() { syncJobs += currentCoroutineContext()[Job]!!; syncPending.await() }
        override suspend fun syncProgress(sequence: Long?) { syncJobs += currentCoroutineContext()[Job]!!; syncPending.await() }
        val report = DiagnosisReport(Verdict.OK, "Synthetic", emptyList(), emptyList(), null, emptyMap(), 1)
        override suspend fun clearCatalog() { reloads++; withContext(NonCancellable) { reloadPending.await() } }
        override suspend fun diagnose(profile: Profile): DiagnosisReport {
            diagnoses++; withContext(NonCancellable) { diagnosisPending.await() }; return report
        }
    }

    @Test fun stopCancelsStartupAccountSyncJobsOwnedByActualViewModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val maintenance = PendingMaintenance()
            val vm = MainViewModel(application(), maintenance)
            MainViewModel::class.java.getDeclaredMethod("startAccountSync").apply { isAccessible = true }.invoke(vm)
            runCurrent()
            assertEquals(2, maintenance.syncJobs.size)
            vm.stop()
            val cancelled = maintenance.syncJobs.all { it.isCancelled }
            maintenance.syncJobs.forEach { it.cancel() }
            runCurrent()
            assertTrue("Startup account sync must stop with the foreground", cancelled)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun heartbeatTriggeredProgressSyncStopsWithoutCancellingIndependentFinalSave() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val maintenance = PendingMaintenance()
            val vm = MainViewModel(application(), maintenance)
            val finalSave = backgroundScope.launch { awaitCancellation() }
            MainViewModel::class.java.getDeclaredMethod("startProgressSync", java.lang.Long::class.java).apply { isAccessible = true }.invoke(vm, 42L)
            runCurrent()
            assertEquals(1, maintenance.syncJobs.size)
            vm.stop()
            val cancelled = maintenance.syncJobs.single().isCancelled
            maintenance.syncJobs.forEach { it.cancel() }
            runCurrent()
            assertTrue("Heartbeat sync must stop with the foreground", cancelled)
            assertTrue(finalSave.isActive)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun stopDuringDiagnosisCancelsActualViewModelJobAndRejectsLatePublication() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val maintenance = PendingMaintenance()
            val vm = MainViewModel(application(), maintenance)
            val job = vm.runDiagnosis()
            runCurrent()
            assertEquals(1, maintenance.diagnoses)
            assertTrue(vm.diagnosing.value)
            vm.stop()
            val stopped = job.isCancelled
            job.cancel() // Cleanup also makes the RED run entirely local.
            maintenance.diagnosisPending.complete(Unit); job.join()
            assertTrue("Diagnosis must belong to the stopped foreground", stopped)
            assertNull(vm.diagnosis.value)
            assertFalse(vm.diagnosing.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun stopDuringReloadCancelsActualViewModelJobBeforeAnotherBootCanStart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val maintenance = PendingMaintenance()
            val vm = MainViewModel(application(), maintenance)
            val job = vm.reload()
            runCurrent()
            assertEquals(1, maintenance.reloads)
            vm.stop()
            val stopped = job.isCancelled
            job.cancel()
            maintenance.reloadPending.complete(Unit); job.join()
            assertTrue("Reload must belong to the stopped foreground", stopped)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(Screen.Loading, vm.screen.value)
            assertEquals(0, maintenance.diagnoses)
            val episodeField = MainViewModel::class.java.getDeclaredField("episodeCheck").apply { isAccessible = true }
            assertNull(episodeField.get(vm))
        } finally { Dispatchers.resetMain() }
    }

    @Test fun repeatedMaintenanceActionsSharePendingWorkAndHiddenActionsDoNotStart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val maintenance = PendingMaintenance()
            val vm = MainViewModel(application(), maintenance)
            val diagnosis = vm.runDiagnosis()
            val duplicateDiagnosis = vm.runDiagnosis()
            val reload = vm.reload()
            val duplicateReload = vm.reload()
            runCurrent()
            vm.stop()
            diagnosis.cancel(); duplicateDiagnosis.cancel(); reload.cancel(); duplicateReload.cancel()
            maintenance.diagnosisPending.complete(Unit); maintenance.reloadPending.complete(Unit)
            diagnosis.join(); duplicateDiagnosis.join(); reload.join(); duplicateReload.join()
            assertEquals(1, maintenance.diagnoses)
            assertEquals(1, maintenance.reloads)
            assertSame(diagnosis, duplicateDiagnosis)
            assertSame(reload, duplicateReload)
            assertTrue(vm.runDiagnosis().isCancelled)
            assertTrue(vm.reload().isCancelled)
            assertEquals(1, maintenance.diagnoses)
            assertEquals(1, maintenance.reloads)
        } finally { Dispatchers.resetMain() }
    }
}
