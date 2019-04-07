package com.dev.anzalone.luca.facelandmarks.utils

import android.app.Activity
import com.dev.anzalone.luca.facelandmarks.Native
import kotlinx.coroutines.async
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Class that represent a model for dlib,
 * [url]: link to download the model,
 * [name]: model name showed in the UI menu
 * [hash]: hash used to check the integrity of the model
 * [id]: model id
 * [version]: actual model version
 * [file]: file name of the stored model (in the device memory)
 */
class Model(val url: String, val name: String, val hash: String,
            val id: Int, val version: Float, val file: String) {

    /** check if the model exists */
    fun exists(dir: File) = File(dir, file).exists()

    /** download and extract the model to the given directory */
    private fun storeTo(activity: Activity, saveDir: File) {
        val temp = File(saveDir, "temp")
        val dest = File(saveDir, file)

        Downloader(activity,
                destination = temp,
                onError = { if (it.exists()) it.delete() },
                onSuccess = { it ->
                    activity.runOnUiThread {
                        Extractor(activity, dest,
                                onSuccess = { if (temp.exists()) temp.delete() },
                                onError = {
                                    if (dest.exists()) dest.delete()
                                    if (temp.exists()) temp.delete()
                                }
                        ).start(it)
                    }
                }
        ).start(url)
    }

    /** ask the user to download the missing model */
    fun askToUser(activity: Activity, saveDir: File, title: String, message: String) {
        UserDialog(activity, title, message,
                onPositive = { storeTo(activity, saveDir) })
                .show()
    }

    /** try to load the model, returns true on success */
    private fun loadFrom(dir: File): Boolean {
        return try {
            Native.loadModel(File(dir, file).path)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** load the model asynchronously */
    fun loadAsync(dir: File) = async {
        loadFrom(dir)
    }

    /** check if the downloaded and then extracted model is corrupted */
    fun isCorrupted(dir: File): Boolean {
        val file = File(dir, file)
        val hash = digest(file)
        println("hash ${this.hash} == $hash: ${this.hash == hash}")

        return this.hash != hash
    }

    /** delete the model file */
    fun delete(dir: File) {
        val file = File(dir, name)

        if (file.exists())
            file.delete()
    }

    companion object {

        /**
         * compute the hash of the model file
         * source: [https://stackoverflow.com/a/14922433/423105]
         */
        fun digest(file: File): String {
            if (!file.exists())
                return ""

            return try {
                val md5 = MessageDigest.getInstance("MD5")
                val buffer = ByteArray(8192)
                val stream = file.inputStream()
                var read = stream.read(buffer)

                while (read > 0) {
                    md5.update(buffer, 0, read)
                    read = stream.read(buffer)
                }

                val bigInt = BigInteger(1, md5.digest())
                val output = bigInt.toString(16)

                String.format("%32s", output).replace(' ', '0')

            } catch (e: Exception) {
                println("Error: somothing goes wrong -> ${e.message}")
                e.printStackTrace()

                ""
            }
        }

        /** create a model from a json object */
        fun fromJsonObject(obj: JSONObject) : Model {
            return Model(
                    obj.getString("url"),
                    obj.getString("name"),
                    obj.getString("hash"),
                    obj.getString("id").toInt(),
                    obj.getString("version").toFloat(),
                    obj.getString("file")
            )
        }
    }
}