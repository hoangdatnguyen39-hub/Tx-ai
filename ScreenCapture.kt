package com.txai.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager

object ScreenCapture {

    const val REQUEST_CODE = 1001

    fun requestPermission(activity: Activity) {
        val manager = activity.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        activity.startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_CODE
        )
    }
}
