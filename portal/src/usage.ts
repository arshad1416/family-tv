// Anonymous usage stats: the names both TV apps may send. Anything else is dropped, so a TV can
// never store a channel, title, category or search text here.

export const USAGE_APPS = ["kotlin", "rust"] as const;

export const USAGE_SCREENS = [
  "home", "live", "guide", "movies", "series", "series_page", "movie_page", "categories", "sports", "search", "settings",
  "player", "player_menu", "pairing", "update",
] as const;

export const USAGE_ACTIONS = [
  "open", "leave", "back", "select", "hold_ok", "favourite_add", "favourite_remove", "mark_watched",
  "zap_up", "zap_down", "play_start", "play_fail", "play_switch_copy", "search_submit", "search_pick",
  "search_empty", "setting_change", "update_install", "exit_prompt", "exit", "error",
] as const;

/** The only words `value` may be besides a number: setting keys, player panels, what started, error kinds. */
export const USAGE_WORDS = [
  // setting_change
  "stream_quality_live", "stream_quality_movies", "stream_quality_series", "picture_size", "audio_track", "subtitles",
  "run_check", "reload_channels", "parental_unlock", "category_order", "trakt", "open_on_start",
  // player_menu open
  "info", "menu",
  // play_start
  "live", "movie", "episode",
  // play_fail, error
  "network", "decoder", "timeout", "source", "other",
  // error: the app moved to a backup server
  "server_switch",
] as const;

export const USAGE_BATCH_MAX = 500;
export const USAGE_KEEP_MS = 30 * 24 * 3600_000;

export interface UsageEvent {
  at: number;
  screen: string;
  action: string;
  value: string | null;
}

const has = (list: readonly string[], v: unknown): v is string => typeof v === "string" && list.includes(v);

/**
 * One event as a TV sent it, or null when its screen or action isn't a known name or its time is
 * outside the 30 days kept. A value that isn't a number or a known word is dropped, not the event.
 */
export function usageEvent(e: unknown, now: number): UsageEvent | null {
  if (!e || typeof e !== "object") return null;
  const r = e as Record<string, unknown>;
  if (!has(USAGE_SCREENS, r.screen) || !has(USAGE_ACTIONS, r.action)) return null;
  if (typeof r.at !== "number" || !Number.isFinite(r.at)) return null;
  const at = Math.min(Math.round(r.at), now);
  if (at < now - USAGE_KEEP_MS) return null;
  const v = r.value;
  const value = typeof v === "number" && Number.isFinite(v) ? String(Math.round(v * 1000) / 1000) : has(USAGE_WORDS, v) ? v : null;
  return { at, screen: r.screen, action: r.action, value };
}
