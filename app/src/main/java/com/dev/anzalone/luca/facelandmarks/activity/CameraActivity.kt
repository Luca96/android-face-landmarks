@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.facelandmarks.activity

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Camera
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import com.dev.anzalone.luca.facelandmarks.Native
import com.dev.anzalone.luca.facelandmarks.R
import com.dev.anzalone.luca.facelandmarks.camera.CameraUtils
import com.dev.anzalone.luca.facelandmarks.utils.Downloader
import com.dev.anzalone.luca.facelandmarks.utils.Model
import com.dev.anzalone.luca.facelandmarks.utils.UserDialog
import com.dev.anzalone.luca.facelandmarks.utils.mapTo
import kotlinx.android.synthetic.main.activity_camera.*
import kotlinx.coroutines.*
import kotlinx.coroutines.android.UI
import kotlinx.coroutines.channels.actor
import org.json.JSONArray
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/**
 * CameraActivity: puts all together by previewing the captured frames,
 * running the android face detector, localizing the landmarks and building the user interface.
 * Created by Luca on 08/04/2018.
 */

class CameraActivity : Activity(), Camera.PreviewCallback, Camera.FaceDetectionListener {
    private var frame: ByteArray?  = null
    private var currentFace: Rect? = null
    private lateinit var modelDir: File
    private lateinit var modelsJson: File
    private var currentModelId = -1
    private val lock = ReentrantLock()
    private val detectorActor = newDetectorActor()
    private var promise: Deferred<LongArray>? = null
    private var imageTaken = false
    private var modelsFetched = false

    init {
        System.loadLibrary("native-lib")
    }

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        setContentView(R.layout.activity_camera)
        println("onCreate")

        askPermission()

        // private model directory, and models.json file
        modelDir = getDir("models", MODE_PRIVATE)
        modelsJson = File(getDir("models", MODE_PRIVATE), "models.json")

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

        // handle display rotation (keep track of the current loaded model)
        if (bundle != null) {
            currentModelId = bundle.getInt(model_id, -1)
        }

        launch(UI) {
            delay(1500L)

            // if models.json does't exist retrieve and alert user
            if (!modelsJson.exists()) {
                fetchModels(::loadModelsFromJson)

            } else {
                // ..warn user about eventual new models and updates
                loadModelsFromJson(modelsJson.readText())  // load models from (stored) models.json

                val updates = ArrayList<Model>()
                var newModels = 0

                UserDialog(this@CameraActivity,
                        title = "Check for new models?",
                        msg = "this will check if there are new models or updates",
                        onPositive = {
                            fetchModels { json ->
                                val jarray = JSONArray(json)
                                val size = jarray.length()

                                for (i in 0 until size) {
                                    val obj  = jarray.getJSONObject(i)
                                    val mod1 = Model.fromJsonObject(obj)
                                    val mod2 = models.getOrNull(mod1.id)

                                    // keep track of new models and models updates
                                    if (mod2 == null) {
                                        newModels++

                                    } else if (mod1.version > mod2.version) {
                                        updates.add(mod1)
                                    }
                                }

                                // warn user
                                if (updates.size > 0) {
                                    UserDialog(this@CameraActivity,
                                            title = "Fetch summary:",
                                            msg = "There are $newModels new models and $updates updates.\nDo you want to download the updated models?",
                                            positiveLabel = "Update",
                                            negativeLabel = "Close",
                                            onPositive = {
                                                updates.forEach { it ->
                                                    Downloader(this@CameraActivity,
                                                            title = "Update model ${it.name}",
                                                            destination = File(modelDir, it.file),
                                                            onError = {}   //TODO: show error message
                                                    ).start(it.url)
                                                }
                                            }
                                    ).show()
                                }
                            }
                        }
                ).show()
            }
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

    /** store the captured frame for later analisys */
    override fun onPreviewFrame(bytes: ByteArray, camera: Camera) {
        frame = bytes
    }

    /** define an Actor that sends the detected landarks to a channel, for later consuming */
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

    /** localize landmark every time a face is detected */
    override fun onFaceDetection(faces: Array<out Camera.Face>, camera: Camera) {
        launch(CommonPool) {

            if (frame == null || faces.isEmpty()) {
                detectorActor.send(Pair(null, null))
                return@launch
            }

            // get the prominent (bigger) face with a confidence greater than 30
            val bestFace = faces.filter { it.score > 30 }.maxBy { it.score }

            if (bestFace != null) {
                val face = bestFace.rect
                val w = cameraPreview.previewWidth
                val h = cameraPreview.previewHeight
                val rotation = cameraPreview.displayRotation
                val rect = Rect(face).mapTo(w, h, rotation)

                //----  detect landmarks  ----//
                // ..if no detections are running
//                if (promise != null && !promise!!.isCompleted) {
                if (promise?.isCompleted == false) {
                    detectorActor.send(Pair(face, null))
                    return@launch
                }

                // ..and only if the model is loaded and not locked
                if (lock.isLocked || currentModelId < 0) {
                    detectorActor.send(Pair(face, null))
                    return@launch
                }

                // ..so, finally detect landmarks!
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

    /** load a model from dlib */
    private fun loadModel(id: Int): Boolean {
        val model  = models[id] ?: return true

        if (model.exists(modelDir)) {
            // try loading...
            if (currentModelId != id) {

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
                            true -> { imageTaken = false; id }
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
        val menu  = popup.menu

        models.forEach { model ->
            val item = menu.add(model.name)

            item.setOnMenuItemClickListener {
                loadModel(model.id)
                true
            }
        }

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

    /** asks for camera and storage permission */
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

    /** check whether the device is connected to the internet */
    private fun isConnected2Internet() : Boolean {
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.activeNetworkInfo.isConnected
    }

    /** retrieve the models.json file from GitHub */
    private fun fetchModels(callback: (String) -> Unit = {}) {
        val dialog = UserDialog(this@CameraActivity,
                title = "Error during fetching models",
                msg = "is the device connected to the internet?",
                onPositive = { fetchModels(callback) },
                positiveLabel = "Retry?",
                negativeLabel = "Close"
        )

        Downloader(this@CameraActivity,
                title = "Fetching available models..",
                destination = modelsJson,
                onError = { dialog.show() },
                onSuccess = { callback(it.readText()) }
        ).start(json_url)
    }

    /** load the models from the content of a json file */
    private fun loadModelsFromJson(contents: String) {
        val jarray = JSONArray(contents)

        models.clear()

        // load model info stored inside models.json
        for (i in 0 until jarray.length()) {
            val obj = jarray.getJSONObject(i)
            models.add(Model.fromJsonObject(obj))
        }

        modelsFetched = true
    }

    companion object {
        val models = ArrayList<Model>()

        const val perm_granted = PackageManager.PERMISSION_GRANTED
        const val perm_camera  = Manifest.permission.CAMERA
        const val perm_storage = Manifest.permission.WRITE_EXTERNAL_STORAGE
        const val request_camera  = 100
        const val request_storage = 200
        const val model_id = "CameraActivity.model_id"
        const val json_url = "https://github.com/Luca96/dlib-minified-models/raw/master/face_landmarks/models.json"
    }
}

typealias Detection = Pair<Rect?, LongArray?>
typealias ModelPair = Pair<String, Model>