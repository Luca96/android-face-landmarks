package com.dev.anzalone.luca.facelandmarks.utils

import android.app.AlertDialog
import android.content.Context

/**
 * Simple builder for creating AlertDialogs
 */
class UserDialog(context: Context, title: String, msg: String = "",
                 positiveLabel: String = "Yes",
                 negativeLabel: String = "No",
                 onPositive: () -> Unit = {},
                 onNegative: () -> Unit = {}) {

    private val builder = AlertDialog.Builder(context)

    init {
        builder.setTitle(title)
                .setMessage(msg)
                .setPositiveButton(positiveLabel) { _, _ -> onPositive() }
                .setNegativeButton(negativeLabel) { _, _ -> onNegative() }
    }

    fun show() {
        builder.show()
    }
}