@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.tirocinio.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import com.dev.anzalone.luca.tirocinio.Native
import com.dev.anzalone.luca.tirocinio.R
import com.dev.anzalone.luca.tirocinio.camera.CameraUtils
import com.dev.anzalone.luca.tirocinio.utils.Model
import com.dev.anzalone.luca.tirocinio.utils.mapTo
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/**
 * CameraActivity
 * Created by Luca on 08/04/2018.
 */

class CameraActivity : Activity(), Camera.PreviewCallback, Camera.FaceDetectionListener {
    private var frame: ByteArray?  = null
    private var currentFace: Rect? = null
    private lateinit var modelDir: File
    private var currentModelId = -1
    private val lock = ReentrantLock()
    private val detectorActor = newDetectorActor()
    private var promise: Deferred<LongArray>? = null
    private var imageTaken = false

    init {
        System.loadLibrary("native-lib")

    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        setContentView(R.layout.activity_camera)
        println("onCreate")

        askPermission()

        // private model directory
        modelDir = getDir("models", Context.MODE_PRIVATE)

        // set face detection listener
        cameraPreview.faceListener = this
        cameraPreview.previewCallback = this

        // show the menu, where a model can be selected
        popupMenu.setOnClickListener(::showPopupMenu)

        // capture face
        captureButton.setOnClickListener {
            if (currentFace == null)
                Toast.makeText(this@CameraActivity, "Capture: No face detected!",
                        Toast.LENGTH_SHORT).show()

            cameraPreview.capture(frame, currentFace ?: return@setOnClickListener)
        }

        // set cameraPreview for cameraOverlay
        cameraOverlay.preview = cameraPreview

        // handle display rotation
        if (bundle != null) {
            currentModelId = bundle.getInt(model_id, -1)
        }
    }

    override fun onResume() {
        super.onResume()
        println("onResume")

        cameraPreview.startPreview()
    }

    override fun onPause() {
        super.onPause()
        println("onPause")

        cameraPreview.stopPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        println("onDestroy")

        val temp = File(modelDir, "temp")
        if (temp.exists())
            temp.delete()
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        bundle.putInt(model_id, currentModelId)
    }

    /** ---------------------------------------------------------------------------------------- */
    /** FACE AND LANDMARKS DETECTION */
    /** ---------------------------------------------------------------------------------------- */
    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        frame = bytes
    }

    /** actor */
    private fun newDetectorActor() = actor<Detection> {
        for ((face, landmarks) in channel) {
            launch(UI) {
                cameraOverlay.setFaceAndLandmarks(face, landmarks)
                cameraOverlay.invalidate()
            }

            currentFace = face

            // trigger auto-capture
            if (!imageTaken && face != null && landmarks != null && landmarks.isNotEmpty()) {
                models[currentModelId]?.let {
                    cameraPreview.capture(data = frame, region = face)
                    imageTaken = true
                }
            }
        }
    }

    override fun onFaceDetection(faces: Array<out Camera.Face>, camera: Camera) {
        launch(UI) {

            if (frame == null || faces.isEmpty()) {
                detectorActor.send(Pair(null, null))
                return@launch
            }

            val bestFace = faces.filter { it.score > 30 }.maxBy { it.score }

            if (bestFace != null) {
                val face = bestFace.rect
                val w = cameraPreview.previewWidth
                val h = cameraPreview.previewHeight
                val rotation = cameraPreview.displayRotation
                val rect = Rect(face).mapTo(w, h, rotation)

                //----  detect landmarks  ----//
                // ..if no detections are running
                if (promise != null && !promise!!.isCompleted) {
                    detectorActor.send(Pair(face, null))
                    return@launch
                }

                // ..and only if the model is loaded and not locked
                if (lock.isLocked || currentModelId < 0) {
                    detectorActor.send(Pair(face, null))
                    return@launch
                }

                // ..finally detect landmarks!
                promise = async {
                    Native.analiseFrame(frame, rotation, w, h, rect)
                }

                detectorActor.send(Pair(face, promise?.await()))
            }
        }
    }

    /** ---------------------------------------------------------------------------------------- */
    /** POPUP-MENU */
    /** ---------------------------------------------------------------------------------------- */
    private fun menuItemClick(item: MenuItem): Boolean {
        val itemId = item.itemId
        val model  = models[itemId] ?: return true

        if (model.exists(modelDir)) {
            // try loading...
            if (currentModelId != itemId) {

                if (lock.isLocked) // already loading another model
                    return true

                if (model.isCorrupted(modelDir)) {
                    model.delete(modelDir)
                    return model.askToUser(this@CameraActivity, modelDir,
                            "Model corrupted",
                            "Do you want to download the model again?") == Unit
                }

                launch(UI) {
                    debugText.text = "Loading: ${model.name}..."

                    // clear drawings
                    detectorActor.send(Pair(null, null))

                    lock.withLock {
                        val loaded = model.loadAsync(modelDir).await()

                        currentModelId = when (loaded) {
                            true -> { imageTaken = false; itemId }
                            else -> {
                                model.askToUser(this@CameraActivity, modelDir,
                                        "Something goes wrong :(",
                                        "Do you want to download the model again?")
                                -1
                            }
                        }
                    }

                    debugText.text = "Loaded: ${model.name}"
                    delay(2000L)
                    debugText.text = ""
                }
            }

        } else {
            // try downloading...
            model.askToUser(this, modelDir, "Model not Found", "Do you want to download the model?")
        }

        return true
    }

    private fun showPopupMenu(v: View) {
        val popup = PopupMenu(this, v)
        popup.inflate(R.menu.model_menu)
        popup.setOnMenuItemClickListener(::menuItemClick)
        popup.show()
    }

    /** ---------------------------------------------------------------------------------------- */
    /** PERMISSIONS */
    /** ---------------------------------------------------------------------------------------- */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            request_camera -> {
                if (grantResults[0] != perm_granted)
                    Toast.makeText(this, "Until you grant the permission, camera cannot be used!",
                            Toast.LENGTH_LONG)
                            .show()
            }

            request_storage -> {
                if (grantResults[0] != perm_granted)
                    Toast.makeText(this, "Until you grant the permission, photos can't be taken!",
                            Toast.LENGTH_LONG)
                            .show()
                else
                    CameraUtils.initMediaDir()
            }
        }
    }

    /** asks for camera and internet permission */
    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // permission for camera
            if (checkSelfPermission(perm_camera) != perm_granted)
                requestPermissions(arrayOf(perm_camera), request_camera)

            // permission for writing external storage (saving photos)
            if (checkSelfPermission(perm_storage) != perm_granted)
                requestPermissions(arrayOf(perm_storage), request_storage)
        }
    }

    /** ---------------------------------------------------------------------------------------- */

    companion object {
        val models = mapOf(
                R.id.eye_eyebrows to Model(
                        url  = "https://github.com/Luca96/dlib-minified-models/raw/master/face%20landmarks/eye_eyebrows_model.dat.bz2",
                        name = "eye_eyebrows_model.dat",
                        hash = "db8a1047de822e9c558b2f2b8ebfc815")
                ,
                R.id.face_contour to Model(
                        url  = "https://github.com/Luca96/dlib-minified-models/raw/master/face%20landmarks/face_contour_model.dat.bz2",
                        name = "face_contour_model.dat",
                        hash = "9bfd77ff74cd20b0bf435d50fcb13ece")
                ,
                R.id.nose_mouth to Model(
                        url = "https://github.com/Luca96/dlib-minified-models/raw/master/face%20landmarks/nose_mouth_model.dat.bz2",
                        name = "nose_mouth_model.dat",
                        hash = "92fc14be045c2447814c1130f26a12b4")
                ,
                R.id.fast_68_land to Model(
                        url  = "https://github.com/Luca96/dlib-minified-models/raw/master/face%20landmarks/face_landmarks_68.dat.bz2",
                        name = "all_68_model.dat",
                        hash = "dba4c259742d76b06d8847cae2067e6c")
                ,
                R.id.dlib_68_land to Model(
                        url  = "https://github.com/davisking/dlib-models/raw/master/shape_predictor_68_face_landmarks.dat.bz2",
                        name = "shape_predictor_68_face_landmarks.dat",
                        hash = "73fde5e05226548677a050913eed4e04"
                )
        )

        const val perm_granted = PackageManager.PERMISSION_GRANTED
        const val perm_camera  = Manifest.permission.CAMERA
        const val perm_storage = Manifest.permission.WRITE_EXTERNAL_STORAGE
        const val request_camera  = 100
        const val request_storage = 200
        const val model_id = "CameraActivity.model_id"
    }
}

typealias Detection = Pair<Rect?, LongArray?>