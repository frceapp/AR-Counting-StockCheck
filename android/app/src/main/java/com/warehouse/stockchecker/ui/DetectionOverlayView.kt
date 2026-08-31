package com.warehouse.stockchecker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.warehouse.stockchecker.tracking.WorldTrackedDetection
import kotlin.math.max

/**
 * Draws world-anchored detection boxes and labels on top of the camera preview.
 *
 * Each [WorldTrackedDetection.box] is already projected into the *current* camera frame by
 * [com.warehouse.stockchecker.tracking.WorldAnchorTracker] using the rotation-vector sensor, so the
 * box rides on its real-world point as the user pans the camera. Drawing [track.detection.box]
 * (the captured-time rectangle) would freeze the marker to the screen, which is the bug this
 * view exists to prevent.
 *
 * `PreviewView` uses `FILL_CENTER` (centre crop) scaling, so the same centre crop mapping is
 * applied here to keep the boxes locked onto the preview.
 *
 * Boxes are placed by an explicit capture and then stay put, so an anchor found in the
 * newest capture is drawn solid while previously placed ones are drawn dashed. Nothing
 * ever disappears on its own.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = dp(13f)
        isFakeBoldText = true
    }

    /** Dashes mark an anchor that is being held without a fresh sighting. */
    private val heldDashEffect = DashPathEffect(floatArrayOf(dp(9f), dp(6f)), 0f)

    private val boxRect = RectF()
    private val labelRect = RectF()
    private val textBounds = android.graphics.Rect()

    private var tracks: List<WorldTrackedDetection> = emptyList()
    private var frameWidth = 0
    private var frameHeight = 0

    /**
     * @param tracks world-anchored detections to draw. `track.box` is the projection for the
     *   current frame; `track.detection.box` is the rectangle captured at placement time and
     *   must not be used for drawing (it would pin the marker to the captured pixel position).
     * @param frameWidth width of the upright analysis frame the boxes were projected against.
     *   Zero keeps the previous value, so idle re-projection publishes (no new model output)
     *   can still draw onto the last known frame mapping.
     */
    fun setTracks(tracks: List<WorldTrackedDetection>, frameWidth: Int, frameHeight: Int) {
        this.tracks = tracks
        if (frameWidth > 0) this.frameWidth = frameWidth
        if (frameHeight > 0) this.frameHeight = frameHeight
        contentDescription = buildContentDescription(tracks)
        invalidate()
    }

    fun clear() = setTracks(emptyList(), 0, 0)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tracks.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return
        if (width == 0 || height == 0) return

        // Centre crop: scale by the larger ratio, then centre the overflow.
        val scale = max(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val offsetX = (width - frameWidth * scale) / 2f
        val offsetY = (height - frameHeight * scale) / 2f

        for (track in tracks) {
            // Use the world-projected box, NOT the captured-time detection.box. The captured
            // rectangle stays glued to the screen pixel where the anchor first appeared; the
            // projected box follows the camera motion so the marker rides on its world point.
            val projectedBox = track.box
            boxRect.set(
                projectedBox.left * frameWidth * scale + offsetX,
                projectedBox.top * frameHeight * scale + offsetY,
                projectedBox.right * frameWidth * scale + offsetX,
                projectedBox.bottom * frameHeight * scale + offsetY
            )

            val color = ClassColors.forClass(track.detection.classId)
            boxPaint.color = if (track.isLive) color else fade(color, HELD_ALPHA)
            boxPaint.pathEffect = if (track.isLive) null else heldDashEffect
            canvas.drawRoundRect(boxRect, dp(6f), dp(6f), boxPaint)

            drawLabel(canvas, track.detection.displayText, boxPaint.color)
        }
    }

    /** Keeps the hue but drops the opacity, so held anchors recede without vanishing. */
    private fun fade(color: Int, alpha: Int): Int = Color.argb(
        alpha,
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun drawLabel(canvas: Canvas, text: String, color: Int) {
        labelTextPaint.getTextBounds(text, 0, text.length, textBounds)
        val padH = dp(6f)
        val padV = dp(3f)
        val labelWidth = textBounds.width() + padH * 2f
        val labelHeight = textBounds.height() + padV * 2f

        // Prefer above the box; flip inside when it would clip the top edge.
        val top = if (boxRect.top - labelHeight >= 0f) boxRect.top - labelHeight else boxRect.top
        val left = boxRect.left.coerceIn(0f, max(0f, width - labelWidth))
        labelRect.set(left, top, left + labelWidth, top + labelHeight)

        labelBackgroundPaint.color = color
        canvas.drawRoundRect(labelRect, dp(4f), dp(4f), labelBackgroundPaint)
        canvas.drawText(
            text,
            labelRect.left + padH,
            labelRect.bottom - padV - textBounds.bottom,
            labelTextPaint
        )
    }

    private fun buildContentDescription(tracks: List<WorldTrackedDetection>): CharSequence =
        if (tracks.isEmpty()) {
            context.getString(com.warehouse.stockchecker.R.string.detection_overlay_description)
        } else {
            tracks.joinToString(separator = ", ") { it.detection.displayText }
        }

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        /** Opacity of an anchor that is held from an earlier frame. */
        private const val HELD_ALPHA = 150
    }
}
