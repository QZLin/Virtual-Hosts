package com.github.xfalcon.vhosts.util

import android.content.Context
import android.util.Log

object LogUtils {
    var context: Context? = null

    private fun sendLogData(tag: String?, msg: String?) {
    }

    fun v(tag: String?, msg: String) {
        sendLogData(tag, msg)
        Log.v(tag, msg)
    }

    fun v(tag: String?, msg: String?, tr: Throwable) {
        sendLogData(tag, msg + "   ;" + tr.message)
        Log.v(tag, msg, tr)
    }

    fun d(tag: String?, msg: String) {
        sendLogData(tag, msg)
        Log.d(tag, msg)
    }

    fun d(tag: String?, msg: String?, tr: Throwable) {
        sendLogData(tag, msg + "   ;" + tr.message)
        Log.d(tag, msg, tr)
    }

    fun i(tag: String?, msg: String) {
        sendLogData(tag, msg)
        Log.i(tag, msg)
    }

    fun i(tag: String?, msg: String?, tr: Throwable) {
        sendLogData(tag, msg + "   ;" + tr.message)
        Log.i(tag, msg, tr)
    }

    fun w(tag: String?, msg: String) {
        sendLogData(tag, msg)
        Log.w(tag, msg)
    }

    fun w(tag: String?, msg: String?, tr: Throwable) {
        sendLogData(tag, msg + "   ;" + tr.message)
        Log.w(tag, msg, tr)
    }

    fun e(tag: String?, msg: String) {
        sendLogData(tag, msg)
        Log.e(tag, msg)
    }

    fun e(tag: String?, msg: String?, tr: Throwable) {
        sendLogData(tag, msg + "   ;" + tr.message)
        Log.e(tag, msg, tr)
    }
}
