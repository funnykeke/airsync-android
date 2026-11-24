package com.sameerasw.airsync.receiver

import android.app.Activity
import android.os.Bundle

/** Gives authorized ADB a chance to wake the process before retrying the receiver. */
class UsbWakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
