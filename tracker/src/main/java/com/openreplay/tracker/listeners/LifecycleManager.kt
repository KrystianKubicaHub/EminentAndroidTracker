package com.openreplay.tracker.listeners

import android.app.Activity
import android.app.Application
import android.os.Bundle

class LifecycleManager(
    private val onEvent: (Event, Activity) -> Unit
) : Application.ActivityLifecycleCallbacks {

    enum class Event { ActivityStarted, ActivityResumed, ActivityPaused, ActivityStopped }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
    }

    override fun onActivityStarted(activity: Activity) {
        onEvent(Event.ActivityStarted, activity)
    }

    override fun onActivityResumed(activity: Activity) {
        onEvent(Event.ActivityResumed, activity)
    }

    override fun onActivityPaused(activity: Activity) {
        onEvent(Event.ActivityPaused, activity)
    }

    override fun onActivityStopped(activity: Activity) {
        onEvent(Event.ActivityStopped, activity)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
    }

    override fun onActivityDestroyed(activity: Activity) {
    }
}
