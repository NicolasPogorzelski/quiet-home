package io.github.nicolaspogorzelski.quiethome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders single-colour SVG marks such as Simple Icons: the viewBox and every path's data, filled
 * white. Supports all path commands, including arcs; ignores styles, transforms and other shapes.
 */
object SvgPath {
    private val viewBoxPattern = Regex("""viewBox\s*=\s*"([^"]+)"""")
    private val pathPattern = Regex("""<path\b[^>]*?\sd\s*=\s*"([^"]+)"""")

    fun render(svg: String, size: Int): Bitmap? {
        val paths = pathPattern.findAll(svg).map { it.groupValues[1] }.toList()
        if (paths.isEmpty()) return null
        val box = viewBoxPattern.find(svg)?.groupValues?.get(1)
            ?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 } ?: listOf(0f, 0f, 24f, 24f)
        val path = Path()
        paths.forEach { path.addPath(parse(it)) }
        val scale = size / maxOf(box[2], box[3])
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate((size - box[2] * scale) / 2, (size - box[3] * scale) / 2)
        canvas.scale(scale, scale)
        canvas.translate(-box[0], -box[1])
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return bitmap
    }

    fun parse(data: String): Path {
        val path = Path()
        val r = Reader(data)
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        var ctrlX = 0f
        var ctrlY = 0f
        var previous = ' '
        while (true) {
            val command = r.command() ?: break
            val relative = command.isLowerCase()
            var first = true
            do {
                val ox = if (relative) x else 0f
                val oy = if (relative) y else 0f
                when (command.uppercaseChar()) {
                    'M' -> {
                        x = ox + r.number(); y = oy + r.number()
                        // Further pairs after a moveto are implicit linetos.
                        if (first) { path.moveTo(x, y); startX = x; startY = y } else path.lineTo(x, y)
                    }
                    'L' -> { x = ox + r.number(); y = oy + r.number(); path.lineTo(x, y) }
                    'H' -> { x = ox + r.number(); path.lineTo(x, y) }
                    'V' -> { y = oy + r.number(); path.lineTo(x, y) }
                    'C' -> {
                        val x1 = ox + r.number(); val y1 = oy + r.number()
                        ctrlX = ox + r.number(); ctrlY = oy + r.number()
                        x = ox + r.number(); y = oy + r.number()
                        path.cubicTo(x1, y1, ctrlX, ctrlY, x, y)
                    }
                    'S' -> {
                        val smooth = previous.uppercaseChar() in "CS"
                        val x1 = if (smooth) 2 * x - ctrlX else x
                        val y1 = if (smooth) 2 * y - ctrlY else y
                        ctrlX = ox + r.number(); ctrlY = oy + r.number()
                        x = ox + r.number(); y = oy + r.number()
                        path.cubicTo(x1, y1, ctrlX, ctrlY, x, y)
                    }
                    'Q' -> {
                        ctrlX = ox + r.number(); ctrlY = oy + r.number()
                        x = ox + r.number(); y = oy + r.number()
                        path.quadTo(ctrlX, ctrlY, x, y)
                    }
                    'T' -> {
                        val smooth = previous.uppercaseChar() in "QT"
                        ctrlX = if (smooth) 2 * x - ctrlX else x
                        ctrlY = if (smooth) 2 * y - ctrlY else y
                        x = ox + r.number(); y = oy + r.number()
                        path.quadTo(ctrlX, ctrlY, x, y)
                    }
                    'A' -> {
                        val rx = r.number(); val ry = r.number(); val rotation = r.number()
                        val large = r.flag(); val sweep = r.flag()
                        val endX = ox + r.number(); val endY = oy + r.number()
                        arc(path, x, y, rx, ry, rotation, large, sweep, endX, endY)
                        x = endX; y = endY
                    }
                    'Z' -> { path.close(); x = startX; y = startY }
                    else -> return path
                }
                previous = command
                first = false
            } while (command.uppercaseChar() != 'Z' && r.hasNumber())
        }
        return path
    }

    /** SVG elliptical arc as cubic Béziers (SVG 1.1, appendix F.6.5), at most 90° per segment. */
    private fun arc(
        path: Path, x0: Float, y0: Float, rxIn: Float, ryIn: Float, rotation: Float,
        large: Boolean, sweep: Boolean, x: Float, y: Float,
    ) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { path.lineTo(x, y); return }
        val phi = Math.toRadians(rotation.toDouble())
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)
        val dx = (x0 - x) / 2.0
        val dy = (y0 - y) / 2.0
        val x1 = cosPhi * dx + sinPhi * dy
        val y1 = -sinPhi * dx + cosPhi * dy
        val lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coefficient = sqrt(maxOf(0.0, numerator / denominator))
        if (large == sweep) coefficient = -coefficient
        val cxp = coefficient * rx * y1 / ry
        val cyp = -coefficient * ry * x1 / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0
        val theta = angle(1.0, 0.0, (x1 - cxp) / rx, (y1 - cyp) / ry)
        var delta = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI
        val segments = ceil(abs(delta) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = delta / segments
        val t = 4.0 / 3.0 * tan(step / 4)
        fun mapX(ux: Double, uy: Double) = (cx + rx * ux * cosPhi - ry * uy * sinPhi).toFloat()
        fun mapY(ux: Double, uy: Double) = (cy + rx * ux * sinPhi + ry * uy * cosPhi).toFloat()
        var a1 = theta
        repeat(segments) {
            val a2 = a1 + step
            val c1x = cos(a1) - t * sin(a1); val c1y = sin(a1) + t * cos(a1)
            val c2x = cos(a2) + t * sin(a2); val c2y = sin(a2) - t * cos(a2)
            path.cubicTo(
                mapX(c1x, c1y), mapY(c1x, c1y), mapX(c2x, c2y), mapY(c2x, c2y),
                mapX(cos(a2), sin(a2)), mapY(cos(a2), sin(a2)),
            )
            a1 = a2
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    private class Reader(private val s: String) {
        private var i = 0

        private fun skip() {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        }

        fun command(): Char? {
            skip()
            return if (i < s.length && s[i].isLetter()) s[i++] else null
        }

        fun hasNumber(): Boolean {
            skip()
            return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.')
        }

        fun number(): Float {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            var exponent = false
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isDigit() -> i++
                    c == '.' && !dot && !exponent -> { dot = true; i++ }
                    (c == 'e' || c == 'E') && !exponent -> {
                        exponent = true; i++
                        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                    }
                    else -> break
                }
            }
            return s.substring(start, i).toFloatOrNull() ?: 0f
        }

        /** Arc flags may be written without separators ("a1 1 0 011 1"). */
        fun flag(): Boolean {
            skip()
            return i < s.length && s[i++] == '1'
        }
    }
}
