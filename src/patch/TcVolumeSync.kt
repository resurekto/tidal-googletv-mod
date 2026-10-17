package io.github.resurekto.tidalgtvmod

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import com.aspiro.wamp.tidalconnect.discovery.TcBroadcastProvider
import h32
import i32
import k32

/**
 * New: keeps the Connect receiver's volume equal to the TV's media volume, on every volume
 * change and whenever a receiver connects (including the reconnect at startup).
 *
 * App types: i32.a() is the broadcast manager, h32 its listener, k32 a provider.
 */
object TcVolumeSync : h32 {

    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private lateinit var audio: AudioManager

    @JvmStatic
    fun install(app: Application) {
        audio = app.getSystemService(AudioManager::class.java)
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) == AudioManager.STREAM_MUSIC) sync()
            }
        }, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
        i32.a().addListener(this)
        sync()
    }

    /** Sets the receiver to the TV's media volume, in percent. */
    fun sync() {
        // Connect receivers only; the local provider's volume control is the TV's.
        val provider = i32.a().i() as? TcBroadcastProvider ?: return
        if (!provider.isConnected) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max <= 0 || audio.isStreamMute(AudioManager.STREAM_MUSIC)) 0 else level * 100 / max
        provider.volumeControl.updateVolume(percent)
    }

    override fun a(provider: k32) = sync()          // connected
    override fun b(provider: k32, reason: Int) {}   // disconnected
    override fun c(provider: k32) {}                // connecting
    override fun d() {}                             // device list changed
}
