package app.familytv.iptv

import okhttp3.HttpUrl.Companion.toHttpUrl

/** URL shapes of the Xtream Codes API. [server] is the base, e.g. http://host:8080 */
object XtreamUrls {
    fun api(server: String, user: String, pass: String, action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val b = "${server.trimEnd('/')}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
        if (action != null) b.addQueryParameter("action", action)
        extra.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    fun xmltv(server: String, user: String, pass: String): String =
        "${server.trimEnd('/')}/xmltv.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user).addQueryParameter("password", pass).build().toString()

    fun live(server: String, user: String, pass: String, id: String, ext: String = "ts") = "${server.trimEnd('/')}/live/${enc(user)}/${enc(pass)}/$id.$ext"
    fun movie(server: String, user: String, pass: String, id: String, ext: String?) = "${server.trimEnd('/')}/movie/${enc(user)}/${enc(pass)}/$id.${ext ?: "mp4"}"
    fun episode(server: String, user: String, pass: String, id: String, ext: String?) = "${server.trimEnd('/')}/series/${enc(user)}/${enc(pass)}/$id.${ext ?: "mp4"}"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
