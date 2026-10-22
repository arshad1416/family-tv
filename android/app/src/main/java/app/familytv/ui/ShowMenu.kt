package app.familytv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.familytv.app
import app.familytv.data.Kind
import app.familytv.data.SeriesEntity
import app.familytv.iptv.XtreamParser
import app.familytv.portal.Profile
import app.familytv.trakt.ProgressRules
import app.familytv.usage.Usage
import app.familytv.user.ProgressEntity
import app.familytv.user.ProgressKey
import app.familytv.user.SeriesLanguages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long hold-OK on a show waits for its episode list before opening the menu without it. */
private const val EPISODES_WAIT_MS = 6_000L

/**
 * Hold-OK, Menu or a long press on a show's poster: opens its [ItemMenu] ([showMenu]) once the
 * episode list is in. [favourite] is whether the show is a favourite now.
 */
@Composable
internal fun rememberShowMenu(profile: Profile): (SeriesGroup, Boolean) -> Unit {
    val ctx = LocalContext.current
    val menus = LocalItemMenus.current
    val scope = rememberCoroutineScope()
    return remember(profile, menus, scope) { { g, favourite -> scope.launch { menus.open = showMenu(ctx, profile, g, favourite, scope) } } }
}

/**
 * A show's actions, the same copy and episode its page would pick: Continue (or Play) the episode
 * Continue watching leads to, favourite it, and mark the whole series watched or unwatched. With
 * no episode list (the provider didn't answer) only the favourite is offered.
 */
internal suspend fun showMenu(ctx: Context, profile: Profile, g: SeriesGroup, favourite: Boolean, scope: CoroutineScope): ItemMenu {
    val app = ctx.app
    val progress = app.userDb.user().progress().first().associateBy { it.key }
    val v = g.copies.getOrElse(defaultCopy(g.copies, progress, SeriesLanguages(ctx), app.quality[Kind.SERIES])) { g.first }
    val episodes = episodesOf(ctx, profile, v)
    fun progressOf(e: XtreamParser.Episode): ProgressEntity? = progress[ProgressKey.episode(v.sourceId, v.seriesId, e.season, e.episode)]
    val next = nextEpisode(episodes, ::progressOf)
    val begun = episodes.any { progressOf(it)?.watched == true || ProgressRules.started(progressOf(it)) }
    val watched = episodes.isNotEmpty() && episodes.all { progressOf(it)?.watched == true }
    return ItemMenu(
        g.title,
        buildList {
            next?.let { e -> add("▶  ${if (begun) "Continue" else "Play"} S${e.season} E${e.episode}" to { playEpisode(scope, ctx, profile, v, e) }) }
            add((if (favourite) "♥  Remove from Favourites" else "♡  Add to Favourites") to { toggleShowFavourite(ctx, g, favourite, v) })
            if (episodes.isNotEmpty()) add((if (watched) "Mark series as unwatched" else "✓  Mark series as watched") to { markSeries(ctx, v, episodes, !watched) })
        },
    )
}

private suspend fun episodesOf(ctx: Context, profile: Profile, v: SeriesEntity): List<XtreamParser.Episode> {
    val src = profile.sources.firstOrNull { it.id == v.sourceId && it.type == "xtream" } ?: return emptyList()
    return withTimeoutOrNull(EPISODES_WAIT_MS) { runCatching { ctx.app.catalog.seriesInfo(v.sourceId, v.seriesId, src).episodes }.getOrNull() }.orEmpty()
}

/** Every episode of [v] watched or unwatched (unwatched also clears where each was left), as the page's season button does. */
private fun markSeries(ctx: Context, v: SeriesEntity, episodes: List<XtreamParser.Episode>, watched: Boolean) {
    val app = ctx.app
    app.scope.launch {
        app.watched.episodes(v.sourceId, v.seriesId, episodes.map { it.season to it.episode }, watched)
        Usage.log(Usage.Action.MARK_WATCHED, if (watched) 1 else 0)
        toast(ctx, if (watched) "Series marked as watched" else "Series marked as unwatched")
    }
}
