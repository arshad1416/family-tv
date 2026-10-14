package app.familytv.iptv

import app.familytv.data.AppDb
import app.familytv.data.ChannelEntity
import app.familytv.data.MovieEntity
import app.familytv.portal.Profile

/** Builds playable URLs from catalog rows plus the profile's credentials (kept out of the DB). */
class Streams(private val db: AppDb, private val profile: Profile) {
    private suspend fun xtream(sourceId: String): Triple<String, String, String>? {
        val s = profile.sources.firstOrNull { it.id == sourceId && it.type == "xtream" } ?: return null
        val server = db.catalog().source(sourceId)?.activeServer ?: s.serverUrls?.firstOrNull() ?: return null
        return Triple(server, s.username.orEmpty(), s.password.orEmpty())
    }

    suspend fun live(ch: ChannelEntity, ext: String = "ts"): String? =
        ch.url ?: xtream(ch.sourceId)?.let { (srv, u, p) -> XtreamUrls.live(srv, u, p, ch.streamId, ext) }

    suspend fun movie(m: MovieEntity): String? =
        m.url ?: xtream(m.sourceId)?.let { (srv, u, p) -> XtreamUrls.movie(srv, u, p, m.streamId, m.ext) }

    suspend fun episode(sourceId: String, e: XtreamParser.Episode): String? =
        xtream(sourceId)?.let { (srv, u, p) -> XtreamUrls.episode(srv, u, p, e.id, e.ext) }
}
