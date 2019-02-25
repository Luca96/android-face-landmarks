@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.facelandmarks.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.os.AsyncTask
import android.os.Looper
import android.util.Log
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Class used to extract bzip2 files (models)
 */
class Extractor(activity: Activity, val destination: File,
                 val onError: (File) -> Unit = {},
                 val onSuccess: (File) -> Unit = {})
{
    private val progressDialog: ProgressDialog = ProgressDialog(activity)

    init {
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setCancelable(false)
        progressDialog.setTitle("Extracting model...")
    }

    fun start(tempFile: File) {
        Task().execute(tempFile)
    }

    /** AsyncTask */
    @SuppressLint("StaticFieldLeak")
    inner class Task : AsyncTask<File, Int, File>() {

        override fun onPreExecute() {
            super.onPreExecute()
            progressDialog.show()
        }

        override fun doInBackground(vararg files: File): File {
            val temp = files[0]
            try {
                val stream = temp.inputStream()
                val fileLength = (temp.length() * 1.558).toInt()  // approximate extracted size
                val reader = BZip2CompressorInputStream(BufferedInputStream(stream, buffer_size))
                val writer = FileOutputStream(destination)

                val data  = ByteArray(buffer_size)
                var count = reader.read(data)
                var total = count

                Log.d(tag, "extraction started for: ${destination.name}")

                while (count != -1) {
                    total += count
                    publishProgress(total * 100 / fileLength)
                    writer.write(data, 0, count)
                    count = reader.read(data)
                }

                writer.flush()
                writer.close()
                reader.close()

                Log.d(tag, "File extacted at ${destination.path}")

                Looper.prepare()
                onSuccess(destination)

            } catch (e: Exception) {
                Log.w(tag, "Error occurred! --> $e")
                e.printStackTrace()

                Looper.prepare()
                onError(destination)
            }

            return destination
        }

        override fun onProgressUpdate(values: Array<Int>) {
            super.onProgressUpdate(*values)
            progressDialog.progress = values[0]
        }

        override fun onPostExecute(result: File?) {
            progressDialog.dismiss()
        }
    }

    companion object {
        const val buffer_size = 4096 * 2
        const val tag = "Extractor"
    }
}