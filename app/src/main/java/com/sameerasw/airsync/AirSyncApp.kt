package com.sameerasw.airsync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.sameerasw.airsync.data.local.DataStoreManager
import com.sameerasw.airsync.crash.CrashNotificationHelper

class AirSyncApp : Application() {
    private var activityCount = 0

    companion object {
        private var instance: AirSyncApp? = null
        fun getContext(): Application? = instance
        fun isAppForeground(): Boolean = instance?.isForeground() ?: false
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashNotificationHelper.createChannel(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                activityCount++
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun isForeground(): Boolean = activityCount > 0
}
