package app.familytv.ui

import androidx.compose.runtime.mutableStateOf
import app.familytv.discovery.ProfileRdStatus
import kotlinx.coroutines.Job

/** UI acknowledgement only; a cancelled mutation may already have reached the portal. */
internal class ProfileKeyRequestState {
    val status = mutableStateOf<ProfileRdStatus?>(null)
    fun cancel(job: Job?) {
        status.value = null
        job?.cancel()
    }
}
