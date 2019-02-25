@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.facelandmarks.camera

import android.graphics.*
import android.hardware.Camera
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs


/**
 * CameraUtils
 * Created by Luca on 10/04/2018.
 */
object CameraUtils {
    private val mediaDir = File(Environment
            .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "FaceAnalyzer")

    /** try to open a camera with the desired facing */
    @Synchronized
    fun open(facing: Int = front) : Camera? {
        try {
            return Camera.open(facing)

        } catch (e: Exception) {
            println("CameraUtils.openCamera() - ERROR \n $e")
            e.printStackTrace()
        }

        return null
    }

    /** find the optimal supported preview size for camera */
    fun optimalPreviewSize(params: Camera.Parameters, width: Int, height: Int): Pair<Int, Int> {
        val sizes = params.supportedPreviewSizes
        val tolerance = 0.15f

        val targetRatio = when (width > height) {
            true -> width.toFloat() / height
            else -> height.toFloat() / width
        }

        println("PREVIEW-SIZE: $width, $height r: $targetRatio")

        val closestRatio = sizes.sortedBy {
            val ratio = it.width.toFloat() / it.height
            println("SIZE: ${it.width}, ${it.height} r: $ratio")
            abs(targetRatio - ratio)
        }

        val minRatio = closestRatio[0].width.toFloat() / closestRatio[0].height

        closestRatio.filter {
            val ratio = it.width.toFloat() / it.height
            abs(ratio - minRatio) <= tolerance
        }

        val size = closestRatio.filter {
            val ratio = it.width.toFloat() / it.height
            abs(ratio - minRatio) <= tolerance
        }.maxBy { it.width * it.height } ?: closestRatio[0]

        println("BEST-SIZE: ${size.width}, ${size.height} r: ${size.width.toFloat() / size.height}")

        return Pair(size.width, size.height)
    }

    /** returns a file for saving an image */
    fun outputMediaFile(): File? {
        if (!mediaDir.exists())
            return null

        val timestamp = SimpleDateFormat("yyyyMMdd__HHmmss", Locale.getDefault()).format(Date())

        return File(mediaDir.path, "FACE_$timestamp.jpg")
    }

    /** create if not exist the media directory for storing photos */
    fun initMediaDir(): Boolean {
        if (!mediaDir.exists())
            return mediaDir.mkdir()

        return true
    }

    /** convert a yuv raw frame captured from camera to a bitmap */
    fun bitmapFromYuv(data: ByteArray, camera: Camera) : Bitmap {
        val params = camera.parameters
        val size = params.previewSize

        val image = YuvImage(data, params.previewFormat, size.width, size.height, null)
        val out = ByteArrayOutputStream()

        image.compressToJpeg(Rect(0, 0, size.width, size.height), 100, out)
        val imageBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    const val front = Camera.CameraInfo.CAMERA_FACING_FRONT
    const val back  = Camera.CameraInfo.CAMERA_FACING_BACK
}