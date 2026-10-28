package app.familytv.trailer

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity

/**
 * A YouTube trailer played here, in YouTube's own embedded player, so the YouTube app's "Who's watching?" picker never
 * comes between the viewer and the trailer (Google TV's YouTube dropped the link behind it, round 22). OK pauses and
 * plays, Left and Right skip 10 seconds, Back closes it; it closes itself when the trailer ends. A trailer whose owner
 * doesn't allow embedding, or that doesn't start, opens in the YouTube app instead.
 */
class TrailerActivity : ComponentActivity() {
    private var web: WebView? = null
    private val main = Handler(Looper.getMainLooper())
    private var playing = false
    private var handedOff = false
    private lateinit var id: String

    @SuppressLint("SetJavaScriptEnabled") // YouTube's player is a script; the page is ours and loads nothing else.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = Trailers.youtubeId(intent.getStringExtra(EXTRA_ID)) ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // No WebView on the box (or a broken one): the YouTube app it is.
        val view = runCatching { WebView(this) }.getOrNull() ?: return handOff()
        web = view
        view.setBackgroundColor(Color.BLACK)
        // The remote's keys come to this screen, not into the player's page.
        view.isFocusable = false
        view.settings.javaScriptEnabled = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.addJavascriptInterface(Bridge(), "FamilyTv")
        setContentView(view)
        view.loadDataWithBaseURL(ORIGIN, page(id), "text/html", "utf-8", null)
        main.postDelayed({ if (!playing) handOff() }, START_MS)
    }

    // androidx marks this restricted, but overriding the platform method is fine.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val js = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> "toggle()"
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> "seek(-$SKIP_S)"
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "seek($SKIP_S)"
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) web?.evaluateJavascript(js, null)
        return true
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /** Opens the trailer in the YouTube app instead, once, and closes this screen. */
    private fun handOff() {
        if (handedOff || isFinishing) return
        handedOff = true
        openInYouTube(this, id)
        finish()
    }

    private inner class Bridge {
        /** YouTube's player state: 0 ended, 1 playing. */
        @JavascriptInterface fun state(s: Int) = main.post {
            when (s) {
                0 -> finish()
                1 -> playing = true
            }
        }

        /** The trailer can't play here (not embeddable, removed, or the player failed). */
        @JavascriptInterface fun error(@Suppress("UNUSED_PARAMETER") code: Int) = main.post { handOff() }
    }

    companion object {
        const val EXTRA_ID = "youtubeId"
        /** The page's address, which YouTube's embedded player needs to see as the page it's in. */
        private const val ORIGIN = "https://app.familytv"
        /** How long the trailer gets to start before it goes to the YouTube app instead. */
        private const val START_MS = 20_000L
        private const val SKIP_S = 10

        /** The player page for [id], already checked as an 11-character YouTube id. */
        internal fun page(id: String) = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style></head>
<body><div id="p"></div><script>
var p;
function onYouTubeIframeAPIReady(){p=new YT.Player('p',{width:'100%',height:'100%',videoId:'$id',
playerVars:{autoplay:1,controls:0,rel:0,fs:0,playsinline:1,iv_load_policy:3,disablekb:1,origin:'$ORIGIN'},
events:{onReady:function(e){e.target.playVideo()},onStateChange:function(e){FamilyTv.state(e.data)},onError:function(e){FamilyTv.error(e.data)}}})}
function toggle(){if(!p||!p.getPlayerState)return;if(p.getPlayerState()==1)p.pauseVideo();else p.playVideo()}
function seek(s){if(!p||!p.getCurrentTime)return;p.seekTo(Math.max(0,p.getCurrentTime()+s),true)}
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>"""
    }
}
