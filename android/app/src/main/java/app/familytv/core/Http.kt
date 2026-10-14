package app.familytv.core

import app.familytv.BuildConfig
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "FamilyTV/${BuildConfig.VERSION_NAME} (Android TV)"

    /** One client for the whole app: shared connection pool and threads keep low-end sticks responsive. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") != null) chain.proceed(req)
                else chain.proceed(req.newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    /** Short timeouts for diagnosis probes so a check finishes in seconds, not minutes. */
    val probeClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()
    }
}

/** Strips credentials from URLs before they are logged or sent to the portal. */
fun redact(url: String): String =
    url.replace(Regex("(username|password)=[^&]*"), "$1=***")
        .replace(Regex("/(live|movie|series|timeshift)/[^/]+/[^/]+/"), "/$1/***/***/")
