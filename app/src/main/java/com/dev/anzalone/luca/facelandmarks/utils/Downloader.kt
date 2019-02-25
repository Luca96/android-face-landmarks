@file:Suppress("DEPRECATION")

package com.dev.anzalone.luca.facelandmarks.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ProgressDialog
import android.os.AsyncTask
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Class used to download files
 */
class Downloader(activity: Activity, title: String = "Downloading file..",
                 val destination: File,
                 val onError: (File) -> Unit = {},
                 val onSuccess: (File) -> Unit = {})
{
    private val progressDialog: ProgressDialog = ProgressDialog(activity)

    init {
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.setCancelable(false)
        progressDialog.setTitle(title)
    }

    fun start(url: String) {
        Task().execute(url)
    }

    /** AsyncTask */
    @SuppressLint("StaticFieldLeak")
    inner class Task : AsyncTask<String, Int, File>() {

        override fun onPreExecute() {
            super.onPreExecute()
            progressDialog.show()
        }

        override fun doInBackground(vararg urls: String): File {
            try {
                val url = URL(urls[0])
                val connection = url.openConnection()
                val fileLength = connection.contentLength
                val reader = BufferedInputStream(url.openStream(), buffer_size)
                val writer = FileOutputStream(destination)

                val data  = ByteArray(buffer_size)
                var count = reader.read(data)
                var total = count

                Log.d(tag, "download started for: ${destination.name}")

                while (count != -1) {
                    total += count
                    publishProgress(total * 100 / fileLength)
                    writer.write(data, 0, count)
                    count = reader.read(data)
                }

                writer.flush()
                writer.close()
                reader.close()

                Log.d(tag, "File downloaded at ${destination.path}")

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
        const val buffer_size = 4096
        const val tag = "Downloader"
    }
}