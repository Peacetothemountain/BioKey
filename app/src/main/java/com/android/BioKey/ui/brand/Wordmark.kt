package com.android.BioKey.ui.brand

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BioKey's wordmark, drawn as vectors so the vine can move.
 *
 * Art Deco chrome letters with an engraved inline (a ring for the i's dot, key teeth on the K,
 * a key bit on the y), and a fingerprint-ridged vine coiled around the word as a double helix.
 * The vine slithers along its coil like a snake: in front of the letters on the way down,
 * behind them on the way up, tapering to a tail and a rounded tip.
 *
 * Plain android.graphics, so the Compose logo and the lock cover's Views share one drawing, and
 * the phase comes from the uptime clock, so both covers show the same frame at the hand-off.
 */
object Wordmark {
    /** Width / height of the artwork. */
    const val ASPECT = VIEW_W / VIEW_H

    /** Where the vine rests when animations are off. */
    const val STILL_PHASE = 0.34f

    /** One trip of the vine round its coil. */
    private const val LOOP_MS = 24_000L

    fun phaseAt(uptimeMs: Long): Float = (uptimeMs % LOOP_MS) / LOOP_MS.toFloat()
}

// Artwork space. Letters sit between cap line 100 and baseline 340; the y descends to 455.
private const val VIEW_X = -6f
private const val VIEW_Y = 48f
private const val VIEW_W = 1224f
private const val VIEW_H = 424f

private const val LETTER_W = 36f
private const val INLINE_LINE = 3f
private const val GAP = 5f

// The coil: a double helix around the word's middle line. The two strands cross at its ends and
// are joined there by a curl. Every switch between in front and behind happens at the top or
// bottom of a wave, clear of the letters.
private const val COIL_Y = 220f
private const val COIL_AMP = 150f
private const val COIL_START = 80f
private const val COIL_END = 1130f
private const val COIL_HALVES = 4
private const val COIL_HALF = (COIL_END - COIL_START) / COIL_HALVES
/** Length of the curl's control arms: sets its size. */
private const val CURL_REACH = 180f
private const val TRACK_STEP = 2f

private const val VINE_W = 32f
private const val VINE_SAMPLES = 480
/** How many samples along the body each ridge spans from one side to the other. */
private const val RIDGE_TWIST = 4
/** How much of the coil the vine covers. */
private const val VINE_FRACTION = 0.5f

private class LetterStroke(val path: Path, val width: Float, val inline: Boolean, val clip: RectF? = null)

private object Geometry {

    val letters: List<LetterStroke> = buildLetters()

    // The coil, resampled every TRACK_STEP units of length: position, depth (+ in front), normal.
    val length: Float
    val count: Int
    val x: FloatArray
    val y: FloatArray
    val z: FloatArray
    val nx: FloatArray
    val ny: FloatArray

    init {
        val raw = ArrayList<FloatArray>(8000)
        fun add(px: Double, py: Double, pz: Double) = raw.add(floatArrayOf(px.toFloat(), py.toFloat(), pz.toFloat()))
        fun angle(px: Double) = PI * (px - COIL_START) / COIL_HALF

        /**
         * A teardrop loop from the crossing at ([px], COIL_Y) back to it: leaves along
         * ([ax], [ay]), returns along ([bx], [by]), and passes from depth [z0] to its opposite.
         */
        fun curl(px: Double, ax: Double, ay: Double, bx: Double, by: Double, z0: Double) {
            val c1x = px + ax * CURL_REACH
            val c1y = COIL_Y + ay * CURL_REACH
            val c2x = px - bx * CURL_REACH
            val c2y = COIL_Y - by * CURL_REACH
            for (i in 0 until 240) {
                val t = i / 240.0
                val u = 1 - t
                val qx = u * u * u * px + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * px
                val qy = u * u * u * COIL_Y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * COIL_Y
                add(qx, qy, z0 * cos(PI * t))
            }
        }

        // Direction of a strand where the two cross, heading right and down.
        val slope = COIL_AMP * PI / COIL_HALF
        val dx = 1 / sqrt(1 + slope * slope)
        val dy = slope * dx

        // Strand A, left to right: in front while heading down, behind while heading up.
        var px = COIL_START.toDouble()
        while (px < COIL_END) {
            val t = angle(px)
            add(px, COIL_Y + COIL_AMP * sin(t), cos(t))
            px += 0.5
        }
        // Curl round past the y, from in front to behind.
        curl(COIL_END.toDouble(), dx, dy, -dx, dy, 1.0)
        // Strand B, right to left, half a turn out of step: the second strand of the helix.
        px = COIL_END.toDouble()
        while (px > COIL_START) {
            val t = angle(px)
            add(px, COIL_Y - COIL_AMP * sin(t), -cos(t))
            px -= 0.5
        }
        // Curl round before the B, from behind to in front.
        curl(COIL_START.toDouble(), -dx, dy, dx, dy, -1.0)

        val n = raw.size
        val cumulative = FloatArray(n + 1)
        for (i in 0 until n) {
            val a = raw[i]
            val b = raw[(i + 1) % n]
            val dx = b[0] - a[0]
            val dy = b[1] - a[1]
            cumulative[i + 1] = cumulative[i] + sqrt(dx * dx + dy * dy)
        }
        length = cumulative[n]
        count = floor(length / TRACK_STEP).toInt()
        x = FloatArray(count)
        y = FloatArray(count)
        z = FloatArray(count)
        var seg = 0
        for (j in 0 until count) {
            val s = j * TRACK_STEP
            while (cumulative[seg + 1] < s) seg++
            val a = raw[seg]
            val b = raw[(seg + 1) % n]
            val f = (s - cumulative[seg]) / (cumulative[seg + 1] - cumulative[seg]).coerceAtLeast(1e-4f)
            x[j] = a[0] + (b[0] - a[0]) * f
            y[j] = a[1] + (b[1] - a[1]) * f
            z[j] = a[2] + (b[2] - a[2]) * f
        }
        nx = FloatArray(count)
        ny = FloatArray(count)
        for (j in 0 until count) {
            val tx = x[(j + 1) % count] - x[(j - 1 + count) % count]
            val ty = y[(j + 1) % count] - y[(j - 1 + count) % count]
            val len = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-4f)
            nx[j] = -ty / len
            ny[j] = tx / len
        }
    }

    private fun buildLetters(): List<LetterStroke> {
        val out = ArrayList<LetterStroke>()
        fun letter(dx: Float, width: Float = LETTER_W, inline: Boolean = true, clip: RectF? = null, build: Path.() -> Unit) {
            val p = Path().apply(build)
            p.offset(dx, 0f)
            out += LetterStroke(p, width, inline, clip?.let { RectF(it.left + dx, it.top, it.right + dx, it.bottom) })
        }
        fun Path.line(x0: Float, y0: Float, x1: Float, y1: Float) { moveTo(x0, y0); lineTo(x1, y1) }
        /** Short bars standing off a stroke, like the bit of a key. */
        fun Path.teeth(x0: Float, y0: Float, x1: Float, y1: Float, side: Float, at: FloatArray, from: Float, to: Float) {
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val ux = (x1 - x0) / len
            val uy = (y1 - y0) / len
            val sx = -uy * side
            val sy = ux * side
            for (t in at) {
                val bx = x0 + ux * len * t
                val by = y0 + uy * len * t
                line(bx + sx * from, by + sy * from, bx + sx * to, by + sy * to)
            }
        }

        // B
        letter(110f) {
            moveTo(18f, 322f)
            lineTo(18f, 118f)
            lineTo(100f, 118f)
            arcTo(RectF(50f, 118f, 150f, 218f), -90f, 180f, false)
            lineTo(110f, 218f)
            arcTo(RectF(58f, 218f, 162f, 322f), -90f, 180f, false)
            close()
            line(18f, 218f, 110f, 218f)
        }
        // i, with a ring for its dot
        letter(318f) { line(18f, 190f, 18f, 340f) }
        letter(318f, width = 22f) { addCircle(18f, 135f, 24f, Path.Direction.CW) }
        // o
        letter(386f) { addCircle(75f, 265f, 57f, Path.Direction.CW) }
        // K, its leg cut like a key
        val kBox = RectF(0f, 100f, 200f, 340f)
        letter(564f, clip = kBox) {
            line(18f, 100f, 18f, 340f)
            line(34f, 252f, 178f, 88f)
            line(80f, 205f, 190f, 352f)
        }
        letter(564f, width = 10f, inline = false, clip = kBox) {
            teeth(80f, 205f, 190f, 352f, side = -1f, at = floatArrayOf(0.52f, 0.66f, 0.8f), from = 16f, to = 34f)
        }
        // e
        letter(782f) {
            arcTo(RectF(18f, 208f, 132f, 322f), 0f, -315f, true)
            line(18f, 265f, 150f, 265f)
        }
        // y, its descender ending in a key bit
        val yBox = RectF(-20f, 190f, 180f, 462f)
        letter(960f, clip = yBox) {
            line(4f, 170f, 80f, 350f)
            line(146f, 170f, 54f, 460f)
        }
        letter(960f, width = 10f, inline = false, clip = yBox) {
            teeth(146f, 170f, 54f, 460f, side = 1f, at = floatArrayOf(0.8f, 0.9f), from = 16f, to = 36f)
        }
        return out
    }
}

/** Draws the wordmark. Keeps its own buffers, so use one per view; not thread-safe. */
class WordmarkPainter {

    private val lx = FloatArray(VINE_SAMPLES)
    private val ly = FloatArray(VINE_SAMPLES)
    private val rx = FloatArray(VINE_SAMPLES)
    private val ry = FloatArray(VINE_SAMPLES)
    private val cx = FloatArray(VINE_SAMPLES)
    private val cy = FloatArray(VINE_SAMPLES)
    private val sx = FloatArray(VINE_SAMPLES)
    private val sy = FloatArray(VINE_SAMPLES)
    private val front = BooleanArray(VINE_SAMPLES)

    private val body = Path()
    private val edges = Path()
    private val ridges = Path()
    private val shine = Path()

    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.MITER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var metal: Shader? = null
    private var metalColor = 0

    /**
     * Draws into [width] x [height], centred and scaled to fit. [letters] and [vine] are the
     * fills (primary and tertiary); [ground] is the colour the logo sits on, which shades the
     * engraving and the strands behind the word.
     */
    fun draw(canvas: Canvas, width: Float, height: Float, phase: Float, letters: Int, vine: Int, ground: Int) {
        if (width <= 0f || height <= 0f) return
        val scale = min(width / VIEW_W, height / VIEW_H)
        canvas.save()
        canvas.translate((width - VIEW_W * scale) / 2f, (height - VIEW_H * scale) / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-VIEW_X, -VIEW_Y)
        // One layer, so the gaps between letters and vine are cut through to whatever is behind.
        canvas.saveLayer(VIEW_X, VIEW_Y, VIEW_X + VIEW_W, VIEW_Y + VIEW_H, null)

        layoutVine(phase)
        drawVine(canvas, inFront = false, color = vine, ground)
        drawLetters(canvas, letters, ground)
        drawVine(canvas, inFront = true, color = vine, ground)

        canvas.restore()
        canvas.restore()
    }

    private fun layoutVine(phase: Float) {
        val g = Geometry
        val bodyLength = g.length * VINE_FRACTION
        val head = phase * g.length
        for (k in 0 until VINE_SAMPLES) {
            val u = k / (VINE_SAMPLES - 1f)
            var s = (head - bodyLength * (1f - u)) % g.length
            if (s < 0f) s += g.length
            val f = s / TRACK_STEP
            val i0 = f.toInt() % g.count
            val i1 = (i0 + 1) % g.count
            val t = f - floor(f)
            val px = g.x[i0] + (g.x[i1] - g.x[i0]) * t
            val py = g.y[i0] + (g.y[i1] - g.y[i0]) * t
            val pz = g.z[i0] + (g.z[i1] - g.z[i0]) * t
            // Nearer strands a touch thicker; a long tapering tail and a rounded tip.
            var w = VINE_W * (0.88f + 0.12f * pz)
            w *= 0.22f + 0.78f * smooth(u / 0.2f)
            if (u > 0.93f) {
                val q = (u - 0.93f) / 0.07f
                w *= sqrt((1f - q * q).coerceAtLeast(0f))
            }
            val half = w / 2f
            val nx = g.nx[i0]
            val ny = g.ny[i0]
            lx[k] = px + nx * half
            ly[k] = py + ny * half
            rx[k] = px - nx * half
            ry[k] = py - ny * half
            cx[k] = px
            cy[k] = py
            sx[k] = px + nx * half * 0.45f
            sy[k] = py + ny * half * 0.45f
            front[k] = pz > 0f
        }
    }

    /** Draws each stretch of the vine on one side of the word, in order from tail to tip. */
    private fun drawVine(canvas: Canvas, inFront: Boolean, color: Int, ground: Int) {
        var k = 0
        while (k < VINE_SAMPLES) {
            if (front[k] != inFront) {
                k++
                continue
            }
            var end = k
            while (end + 1 < VINE_SAMPLES && front[end + 1] == inFront) end++
            // Overlap the neighbouring stretch by a sample so there's no seam where it changes side.
            drawStretch(canvas, (k - 1).coerceAtLeast(0), (end + 1).coerceAtMost(VINE_SAMPLES - 1), color, ground)
            k = end + 1
        }
    }

    private fun drawStretch(canvas: Canvas, a: Int, b: Int, color: Int, ground: Int) {
        if (b - a < 1) return
        edges.rewind()
        edges.moveTo(lx[a], ly[a])
        for (k in a + 1..b) edges.lineTo(lx[k], ly[k])
        edges.moveTo(rx[a], ry[a])
        for (k in a + 1..b) edges.lineTo(rx[k], ry[k])
        body.rewind()
        body.moveTo(lx[a], ly[a])
        for (k in a + 1..b) body.lineTo(lx[k], ly[k])
        for (k in b downTo a) body.lineTo(rx[k], ry[k])
        body.close()

        // A clear gap along both sides, so it reads cleanly over letters and other strands.
        cut.strokeWidth = GAP * 2f
        canvas.drawPath(edges, cut)
        fill.color = color
        canvas.drawPath(body, fill)

        // Fingerprint ridges twisting round the body like rope; they travel with it.
        ridges.rewind()
        // Start a ridge-length early, so ridges run right up to where the stretch begins.
        for (k in (a - RIDGE_TWIST).coerceAtLeast(0) until min(b, VINE_SAMPLES - 1 - RIDGE_TWIST)) {
            ridges.moveTo(lx[k], ly[k])
            ridges.quadTo(cx[k + RIDGE_TWIST / 2], cy[k + RIDGE_TWIST / 2], rx[k + RIDGE_TWIST], ry[k + RIDGE_TWIST])
        }
        canvas.save()
        canvas.clipPath(body)
        line.strokeWidth = 1.8f
        line.color = mix(color, ground, 0.5f)
        canvas.drawPath(ridges, line)
        canvas.restore()

        shine.rewind()
        shine.moveTo(sx[a], sy[a])
        for (k in a + 1..b) shine.lineTo(sx[k], sy[k])
        line.strokeWidth = 2.6f
        line.color = withAlpha(mix(color, Color.WHITE, 0.55f), 0.55f)
        canvas.drawPath(shine, line)
    }

    private fun drawLetters(canvas: Canvas, letters: Int, ground: Int) {
        if (metal == null || metalColor != letters) {
            metalColor = letters
            // Brushed chrome: bright at the top, a dark horizon across the middle, a soft lift below.
            metal = LinearGradient(
                0f, 100f, 0f, 340f,
                intArrayOf(mix(letters, Color.WHITE, 0.34f), letters, mix(letters, Color.BLACK, 0.16f), mix(letters, Color.WHITE, 0.12f)),
                floatArrayOf(0f, 0.44f, 0.56f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val engraving = mix(letters, ground, 0.6f)
        // 0: cut a gap through the vine behind; 1: chrome; 2: engraved line; 3: chrome inside it.
        for (pass in 0..3) {
            for (s in Geometry.letters) {
                val inset = s.width * 0.2f
                val w = when (pass) {
                    0 -> s.width + GAP * 2f
                    1 -> s.width
                    2 -> if (s.inline) s.width - inset * 2f else continue
                    else -> if (s.inline) s.width - inset * 2f - INLINE_LINE * 2f else continue
                }
                canvas.save()
                s.clip?.let { canvas.clipRect(it) }
                if (pass == 0) {
                    cut.strokeWidth = w
                    canvas.drawPath(s.path, cut)
                } else {
                    stroke.strokeWidth = w
                    if (pass == 2) {
                        stroke.shader = null
                        stroke.color = engraving
                    } else {
                        stroke.shader = metal
                    }
                    canvas.drawPath(s.path, stroke)
                }
                canvas.restore()
            }
        }
        stroke.shader = null
    }

    private fun smooth(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
