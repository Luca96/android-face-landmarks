@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.facelandmarks.camera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.dev.anzalone.luca.facelandmarks.utils.mapTo

/**
 * The CameraOverlay class is placed on-top of the CameraPreview,
 * in order to draw the face region and landmarks easily.
 * Created by Luca on 10/04/2018.
 */

class CameraOverlay(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val rect = RectF()
    private val r = Rect()
    var face: Rect? = null
    private var landmarks: LongArray? = null
    lateinit var preview: CameraPreview

    fun setFaceAndLandmarks(face: Rect?, landmarks: LongArray?) {
        this.face = face
        this.landmarks = when (face) {
            null -> null
            else -> landmarks ?: this.landmarks // to avoid flickering while drawing
        }
    }

    /** map the given point to preview coordinate space */
    private fun adjustPoint(x0: Long, y0: Long) : Pair<Float, Float> {
        r.set(face)
        r.mapTo(preview.previewWidth, preview.previewHeight, preview.displayRotation)

        val x1 = x0.toFloat() / r.left
        val y1 = y0.toFloat() / r.top

        val x = x1 * rect.left
        val y = y1 * rect.top

        return Pair(x, y)
    }

    /** All the drawings occur here. onDraw is triggered when invalidate() is called */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        face?.let {
            r.set(it)
            r.mapTo(width, height, preview.displayRotation)
            rect.set(r)

            canvas.drawRect(rect, rPaint)

            landmarks?.let {
//                var count = 0
                for (i in it.indices step 2) {
                    val (x, y) = adjustPoint(it[i], it[i + 1])

                    canvas.drawCircle(x, y, 8f, pPaint)

//                    canvas.drawText(count.toString(), x, y, white)
//                    count++
                }
            }
        }

        face = null
    }

    companion object {
        private val rPaint = Paint()
        private val pPaint = Paint()
        private val white = Paint()

        init {
            rPaint.color = Color.rgb(255, 160, 0)
            rPaint.style = Paint.Style.STROKE
            rPaint.strokeWidth = 5f

            pPaint.color = Color.YELLOW
            pPaint.style = Paint.Style.FILL

            white.color = Color.WHITE
            white.style = Paint.Style.STROKE
            white.textSize = 30f
        }
    }
}