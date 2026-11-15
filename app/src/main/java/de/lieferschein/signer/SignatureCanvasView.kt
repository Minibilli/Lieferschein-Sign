package de.lieferschein.signer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Ein Strich der Unterschrift. Punkte als Anteil (0..1) der angezeigten Seite, Ursprung oben links. */
class Stroke(val points: MutableList<PointF> = mutableListOf())

/** Strichstärke in PDF-Punkten (1/72 Zoll) – identisch in Anzeige und gespeicherter Datei. */
const val INK_WIDTH_PT = 1.8f

/**
 * Zeigt eine gerenderte PDF-Seite an und nimmt darauf Unterschriften mit dem Stift entgegen.
 */
class SignatureCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var stylusOnly = true
    var onChanged: (() -> Unit)? = null

    private var bitmap: Bitmap? = null
    private var pageWidthPt = 595f
    private var strokes: MutableList<Stroke> = mutableListOf()
    private var current: Stroke? = null

    private val dst = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint().apply {
        color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 0, 140)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun setPage(bmp: Bitmap, pageWidthPoints: Float, pageStrokes: MutableList<Stroke>) {
        val old = bitmap
        bitmap = bmp
        pageWidthPt = pageWidthPoints
        strokes = pageStrokes
        current = null
        computeRect()
        invalidate()
        if (old != null && old !== bmp) old.recycle()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate(); onChanged?.invoke()
        }
    }

    fun clearPage() {
        strokes.clear()
        invalidate(); onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRect()
    }

    private fun computeRect() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val pad = 8f
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        val s = min(availW / b.width, availH / b.height)
        val w = b.width * s
        val h = b.height * s
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        dst.set(left, top, left + w, top + h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(220, 220, 220))
        val b = bitmap ?: return
        canvas.drawBitmap(b, null, dst, bmpPaint)
        canvas.drawRect(dst, borderPaint)

        inkPaint.strokeWidth = INK_WIDTH_PT * dst.width() / pageWidthPt
        for (s in strokes) {
            val pts = s.points
            if (pts.isEmpty()) continue
            path.reset()
            path.moveTo(dst.left + pts[0].x * dst.width(), dst.top + pts[0].y * dst.height())
            if (pts.size == 1) {
                path.lineTo(dst.left + pts[0].x * dst.width() + 0.5f, dst.top + pts[0].y * dst.height())
            } else {
                for (i in 1 until pts.size) {
                    path.lineTo(dst.left + pts[i].x * dst.width(), dst.top + pts[i].y * dst.height())
                }
            }
            canvas.drawPath(path, inkPaint)
        }
    }

    private fun toFrac(x: Float, y: Float) = PointF(
        ((x - dst.left) / dst.width()).coerceIn(0f, 1f),
        ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
    )

    private fun isPen(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_STYLUS
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        if (stylusOnly && !isPen(event)) return true // Finger / Handballen ignorieren

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!dst.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                val s = Stroke()
                s.points.add(toFrac(event.x, event.y))
                strokes.add(s)
                current = s
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val s = current ?: return true
                for (h in 0 until event.historySize) {
                    s.points.add(toFrac(event.getHistoricalX(0, h), event.getHistoricalY(0, h)))
                }
                s.points.add(toFrac(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (current != null) {
                    current = null
                    onChanged?.invoke()
                }
            }
        }
        return true
    }
}
