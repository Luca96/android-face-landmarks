@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.facelandmarks.camera

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.graphics.*
import android.hardware.Camera
import android.media.MediaScannerConnection
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import com.dev.anzalone.luca.facelandmarks.Native
import com.dev.anzalone.luca.facelandmarks.utils.inBounds
import com.dev.anzalone.luca.facelandmarks.utils.mapTo
import com.dev.anzalone.luca.facelandmarks.utils.scale
import kotlinx.coroutines.android.UI
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import kotlin.math.absoluteValue

/**
 * @link https://stackoverflow.com/questions/11151911/camera-surface-view-images-look-stretched
 * Created by Luca on 10/04/2018.
 */

class CameraPreview(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var camera: Camera? = null //TODO: ADD PRIVATE
    private var running = false
    private var facing  = CameraUtils.front

    var previewWidth  = 0
    var previewHeight = 0
    var displayRotation = 0

    var faceListener: Camera.FaceDetectionListener? = null
    var previewCallback: Camera.PreviewCallback? = null

    init {
        holder.addCallback(this)
//        holder.setFormat(ImageFormat.NV21)
        holder.setFormat(ImageFormat.JPEG)
//        holder.setType(SurfaceHolder.SURFACE_TYPE_HARDWARE) // deprecated, thus ignored
    }

    private fun swapFacing() {
        facing = when (facing) {
            CameraUtils.front -> CameraUtils.back
            else -> CameraUtils.front
        }
    }

    private fun openCamera() {
        camera = CameraUtils.open(facing)

        if (camera == null) {
//            facing = CameraUtils.back
//            camera = CameraUtils.open(facing)

            // warn user
            launch(UI) {
                Toast.makeText(getContext(), "Cannot open camera!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun closeCamera() {
        stopPreview()
        camera?.release()
        camera = null
    }

    private fun findFpsRange(params: Camera.Parameters): Pair<Int, Int> {
        val fpsList = params.supportedPreviewFpsRange
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE

        fpsList.forEach {
            if (it[0] < min)
                min = it[0]

            if (it[1] > max)
                max = it[1]
        }

        println("min $min, max $max")

        return Pair(min, max)
    }

    private fun supportFeature(list: List<String>?, value: String): Boolean {
        list?.forEach {
            if (it == value)
                return true
        }

        return false
    }

    /** set the exposure compensation, based on the value gotten from the light sensor */
    fun setExposure(variation: Float) {
        camera?.let {
            val params = it.parameters
            val minExp = params.minExposureCompensation.toFloat()
            val maxExp = params.maxExposureCompensation.toFloat()
            val range = minExp.absoluteValue + maxExp
            val step = params.exposureCompensationStep

            val exposure = (maxExp - variation * range) * step
            params.exposureCompensation = exposure.toInt()

            it.parameters = params
        }
    }

    /** starts the camera capture preview */
    fun startPreview() {
        if (!running && camera != null) {
            val camera = camera as Camera

            try {
                val info = Camera.CameraInfo()
                Camera.getCameraInfo(facing, info)

                val rotationOffset = info.orientation
                val params = camera.parameters

                // handle rotation
                val rotation = (context.getSystemService(WINDOW_SERVICE) as WindowManager)
                        .defaultDisplay
                        .rotation

                displayRotation = when (rotation) {
                    Surface.ROTATION_0 -> 90    // portrait
                    Surface.ROTATION_90 -> 0    // landscape-left
                    Surface.ROTATION_270 -> 180 // landscape-right
                    else -> 90
                }

                camera.setDisplayOrientation(displayRotation)
                println("Rotation: display $displayRotation")

                //-- custom camera parameters --//
                val (w, h) = CameraUtils.optimalPreviewSize(params, width, height)
                previewWidth = w
                previewHeight = h
                params.setPreviewSize(w, h)

                if (params.isAutoWhiteBalanceLockSupported)
                    params.autoWhiteBalanceLock = true

//                     params.autoExposureLock = true

                if (params.isVideoStabilizationSupported)
                    params.videoStabilization = true

//                if (supportFeature(params.supportedAntibanding, antibanding_auto))
//                    params.antibanding = antibanding_auto

                if (supportFeature(params.supportedFocusModes, focus_mode_auto))
                    params.focusMode = focus_mode_auto

//                if (supportFeature(params.supportedWhiteBalance, white_balance_auto))
//                    params.whiteBalance = white_balance_auto
                //--

                println("SUPPORTED PREVIEW FORMATS")
                val previewFormats = params.supportedPreviewFormats
                previewFormats.forEach {
                    when (it) {
                        ImageFormat.NV21 -> println("NV21 $it")
                        ImageFormat.NV16 -> println("NV16 $it")
                        ImageFormat.YV12 -> println("YV12 $it")
                        ImageFormat.YUY2 -> println("YUY2 $it")
                        ImageFormat.YUV_420_888 -> println("YUV_420_888 $it")
                    }
                }

                if (previewFormats.contains(ImageFormat.NV21)) {
                    params.previewFormat = ImageFormat.NV21
                    Native.setImageFormat(ImageFormat.NV21)

                } else if (previewFormats.contains(ImageFormat.YV12)) {
                    params.previewFormat = ImageFormat.YV12
                    Native.setImageFormat(ImageFormat.YV12)
                } else
                    Toast.makeText(context, "Camera has a format different to NV21 and YV12", Toast.LENGTH_LONG)
                            .show()

                camera.parameters = params
                camera.setPreviewDisplay(holder)
                camera.startPreview()

                running = true

                if (previewCallback != null)
                    camera.setPreviewCallback(previewCallback)

                if (faceListener != null) {
                    camera.setFaceDetectionListener(faceListener)
                    camera.startFaceDetection()
                }

            } catch (e: Exception) {
                println("CameraPreview - ERROR: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /** stop the camera capture preview */
    fun stopPreview() {
        if (running && camera != null) {
            val camera = camera as Camera

            if (previewCallback != null)
                camera.setPreviewCallback(null)

            if (faceListener != null)
                camera.stopFaceDetection()

            camera.stopPreview()
            running = false
        }
    }

    /** swap the camera and return the current facing of the camera */
    fun swapCamera(): Int {
        closeCamera()
        swapFacing()
        openCamera()
        startPreview()

        return facing
    }

    /** make the camera capture the given region of interest */
    fun capture(data: ByteArray?, region: Rect) = async {
        val file = CameraUtils.outputMediaFile()
        val ctx  = this@CameraPreview.context

        if (data == null || camera == null)
            return@async

        if (file == null) {
            launch(UI) {
                Toast.makeText(ctx, "Cannot save a picture", Toast.LENGTH_SHORT).show()
            }
            return@async
        }

        try {
            // bitmap from raw data
            val bitmap = CameraUtils.bitmapFromYuv(data, camera as Camera)

            // prepare region
            region.scale(1.1f, 1.1f)
            region.inBounds(-1000, -1000, 1000, 1000)
            region.mapTo(bitmap.width, bitmap.height, displayRotation)

            when (displayRotation) {
                portrait -> region.inBounds(0, 0, bitmap.height, bitmap.width)
                else     -> region.inBounds(0, 0, bitmap.width, bitmap.height)
            }

            // crop and rotate bitmap
            val matrix = Matrix()
            matrix.postRotate(displayRotation * -1f)
            matrix.postScale(-1f, 1f) // flip x

            val rotated = Bitmap.createBitmap(bitmap,
                    0, 0, bitmap.width, bitmap.height, matrix, true)

            val cropped = Bitmap.createBitmap(rotated,
                    region.left, region.top, region.width(), region.height())

            // save to file
            val out = BufferedOutputStream(FileOutputStream(file))
            cropped.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
            out.close()

            // update gallery
            MediaScannerConnection.scanFile(ctx, arrayOf(file.path), null) { _, _ -> }

            // notify to user
            launch(UI) {
                Toast.makeText(ctx, "Capture: image saved", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            println("ERROR: ${e.localizedMessage}")
            e.printStackTrace()

            launch(UI) {
                Toast.makeText(ctx, "Error while saving!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder?, format: Int, w: Int, h: Int) {
        startPreview()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder?) = closeCamera()

    override fun surfaceCreated(holder: SurfaceHolder?) = openCamera()

    companion object {
        const val antibanding_auto = Camera.Parameters.ANTIBANDING_AUTO
        const val focus_mode_video = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
        const val focus_mode_auto = Camera.Parameters.FOCUS_MODE_AUTO
        const val white_balance_auto = Camera.Parameters.WHITE_BALANCE_AUTO
        const val portrait  = 90
        const val landleft  = 0
        const val landright = 180
    }
}