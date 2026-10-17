import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.aspiro.wamp.nowplaying.widgets.BroadcastButton
import com.aspiro.wamp.nowplaying.widgets.FavoriteMediaItemButton
import com.aspiro.wamp.tv.nowplaying.tvcontrols.TvControls
import com.aspiro.wamp.tv.nowplaying.tvcontrols.TvShuffleButton
import com.aspiro.wamp.`R$dimen` as Dimens
import com.aspiro.wamp.`R$drawable` as Drawables
import com.aspiro.wamp.`R$id` as Ids
import com.aspiro.wamp.`R$string` as Strings

/**
 * Replaces `bxh`, the view holder of the player's [TvControls], created right after the
 * layout inflates. Adds the output button: the phone's [BroadcastButton] at the right edge,
 * right of Lyrics; it fades with the bar. The seven fields are stock.
 */
class bxh(controls: TvControls) {

    @JvmField val a: TextView = controls.findViewById(Ids.artistNames)
    @JvmField val b: View = controls.findViewById(Ids.playPauseButton)
    @JvmField val c: View = controls.findViewById(Ids.seekBarAndTime)
    @JvmField val d: TextView = controls.findViewById(Ids.title)
    @JvmField val e: FavoriteMediaItemButton = controls.findViewById(Ids.favoriteButton)
    @JvmField val f: View = controls.findViewById(Ids.lyricsButton)
    @JvmField val g: TvShuffleButton = controls.findViewById(Ids.shuffleButton)

    init {
        addOutputButton(controls)
    }

    private fun addOutputButton(controls: TvControls) {
        val context = controls.context
        val density = context.resources.displayMetrics.density
        val size = context.resources.getDimensionPixelSize(Dimens.tv_control_button_size)
        val padding = (16 * density).toInt()
        val gap = (36 * density).toInt()

        // BroadcastButton paints its background with the connection state, so focus and
        // the Leanback background live on a holder that forwards the click.
        val button = BroadcastButton(context, null).apply {
            isFocusable = false
            isClickable = false
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(padding, padding, padding, padding)
        }
        val holder = FrameLayout(context).apply {
            id = Ids.broadcast   // phone layout id, unused on TV
            isFocusable = true
            background = ContextCompat.getDrawable(context, Drawables.lb_control_button_primary)
            contentDescription = context.getString(Strings.broadcast)
            nextFocusRightId = Ids.shuffleButton   // wraps around, as Lyrics did
            nextFocusDownId = Ids.seekBarAndTime
            setOnClickListener { button.performClick() }
            addView(button, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        controls.addView(holder, RelativeLayout.LayoutParams(size, size).apply {
            addRule(RelativeLayout.ALIGN_TOP, Ids.playPauseButton)
            addRule(RelativeLayout.ALIGN_END, Ids.seekBarAndTime)
        })

        // Lyrics moves from the right edge to the left of the output button.
        (f.layoutParams as RelativeLayout.LayoutParams).apply {
            removeRule(RelativeLayout.ALIGN_END)
            addRule(RelativeLayout.START_OF, holder.id)
            marginEnd = gap
        }
        // D-pad: favorite -> lyrics (if shown) -> output. Lyrics shows per track, so the
        // links are refreshed on every layout.
        f.nextFocusRightId = holder.id
        controls.viewTreeObserver.addOnGlobalLayoutListener {
            val lyricsShown = f.visibility == View.VISIBLE
            e.nextFocusRightId = if (lyricsShown) f.id else holder.id
            holder.nextFocusLeftId = if (lyricsShown) f.id else e.id
        }
    }
}
