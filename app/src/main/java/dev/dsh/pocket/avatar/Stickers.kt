package dev.dsh.pocket.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import androidx.core.graphics.PathParser
import org.json.JSONObject
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The chibi sticker avatars: each character has nine traced states (from the 3x3 sheets: idle, blink, work, listen,
 * call, ack, happy, sleep, eat), each a few filled layers of SVG path data, kept gzip'd as assets/avatars/<key>.av (the
 * build unpacks assets named .gz). Rendered here
 * into bitmaps with a white sticker rim, for the app (Compose animates them) and for notification icons. Geometry
 * follows the design page (research/gui-redesign/avatars.js): every state sits on one square canvas, head-centred.
 * Thread-safe; parsing and drawing belong off the main thread.
 */
object Stickers {
    class State(val w: Int, val h: Int, val head: Float, val layers: List<Pair<Int, Path>>)
    class Character(val key: String, val side: Int, val states: Map<String, State>)

    private val characters = object : LruCache<String, Character>(3) {}
    /** Rendered bitmaps by key, size and crop; bounded in bytes. */
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    @Synchronized fun load(context: Context, key: String): Character? {
        characters.get(key)?.let { return it }
        val parsed = runCatching {
            val text = context.assets.open("avatars/$key.av").use { GZIPInputStream(it).bufferedReader().readText() }
            val states = JSONObject(text).getJSONObject("states")
            val map = states.keys().asSequence().associateWith { name ->
                val s = states.getJSONObject(name)
                val paths = s.getJSONArray("paths")
                State(s.getInt("w"), s.getInt("h"), s.getDouble("head").toFloat(), (0 until paths.length()).map { i ->
                    val layer = paths.getJSONArray(i)
                    Color.parseColor(layer.getString(0)) to PathParser.createPathFromPathData(layer.getString(1)).apply { fillType = Path.FillType.EVEN_ODD }
                })
            }
            val side = (map.values.maxOf { max(it.w, it.h) } * 1.1f).roundToInt()
            Character(key, side, map)
        }.onFailure { android.util.Log.w("Stickers", "avatar $key unavailable: ${it.message}") }.getOrNull() ?: return null
        characters.put(key, parsed)
        return parsed
    }

    /**
     * [state] of [key] as a [px] square bitmap: the whole canvas, or only the head ([head], for round icons, on a soft
     * [background] disc when one is given). Null when the character cannot be read.
     */
    fun render(context: Context, key: String, state: String, px: Int, head: Boolean = false, rim: Boolean = true, background: Int? = null): Bitmap? {
        val id = "$key|$state|$px|$head|$rim|$background"
        bitmaps.get(id)?.let { return it }
        val character = load(context, key) ?: return null
        val s = character.states[state] ?: character.states["idle"] ?: return null
        val side = character.side.toFloat()
        val dx = (side / 2 - s.head).roundToInt().toFloat()
        val dy = ((side - s.h) * 0.62f).roundToInt().toFloat()
        // Head crop: a square around the head, a little above the body's top edge.
        val crop = min(s.h, s.w) * 0.86f
        val (vx, vy, view) = if (head) Triple(side / 2 - crop / 2, dy - crop * 0.02f, crop) else Triple(0f, 0f, side)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (background != null) canvas.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        // A round icon shows only what is inside its disc.
        if (head) canvas.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
        val scale = px / view
        canvas.scale(scale, scale)
        canvas.translate(dx - vx, dy - vy)
        if (rim) {
            // The white rim: the silhouette of every layer, widened (the design page dilates it by 1.2 % of the side).
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; strokeWidth = side * 0.024f + 1.6f
            }
            for ((_, path) in s.layers) canvas.drawPath(path, white)
        }
        // Each layer is filled and outlined in its own colour, which closes the hairline gaps between layers.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 1.6f; strokeJoin = Paint.Join.ROUND }
        for ((color, path) in s.layers) { paint.color = color; canvas.drawPath(path, paint) }
        bitmaps.put(id, bitmap)
        return bitmap
    }
}
