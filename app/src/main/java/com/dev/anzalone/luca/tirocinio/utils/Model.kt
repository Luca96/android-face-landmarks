package com.dev.anzalone.luca.tirocinio.utils

import android.app.Activity
import android.app.AlertDialog
import com.dev.anzalone.luca.tirocinio.Native
import kotlinx.coroutines.experimental.async
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Class that represent a model for dlib
 */
class Model(val url: String, val name: String, val hash: String) {

    /** check if the model exists */
    fun exists(dir: File) = File(dir, name).exists()

    /** download and extract the model to the given directory */
    private fun storeTo(activity: Activity, saveDir: File) {
        val temp = File(saveDir, "temp")
        val dest = File(saveDir, name)

        Downloader(activity, temp,
                onError = { if (it.exists()) it.delete() },
                onSuccess = {
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

    fun askToUser(activity: Activity, saveDir: File, title: String, message: String) {
        AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", { _, _ ->
                    this.storeTo(activity, saveDir)
                })
                .setNegativeButton("No", { _, _ -> })
                .show()
    }

    /** try to load the model, returns true on success */
    private fun loadFrom(dir: File): Boolean {
        return try {
            Native.loadModel(File(dir, name).path)
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
        val file = File(dir, name)
//        val len = file.length
//        val bytes = file.byteInputStream().readBytes(len)

        val hash = MD5.digest(file)
        println("hash ${this.hash} == $hash: ${this.hash == hash}")

        return this.hash != hash
    }

    /** delete the model file */
    fun delete(dir: File) {
        val file = File(dir, name)

        if (file.exists())
            file.delete()
    }

    companion object MD5 {

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
    }
}