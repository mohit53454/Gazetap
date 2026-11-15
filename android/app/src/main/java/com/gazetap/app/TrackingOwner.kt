package com.gazetap.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A [LifecycleOwner] whose state GazeTap chooses, instead of one that follows an Activity on screen.
 *
 * CameraX only opens the camera while its lifecycle owner is at least STARTED. Binding the analysis
 * use case to MainActivity therefore switched the camera off as soon as another app came to the
 * front: no frames, no cursor, no flick, no swipe - in exactly the "any app" case the whole app
 * exists for. Binding to this owner keeps the camera open while [GazeSessionService] is running.
 */
class TrackingOwner : LifecycleOwner {

    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    fun resume() {
        if (registry.currentState != Lifecycle.State.RESUMED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun pause() {
        if (registry.currentState == Lifecycle.State.RESUMED) {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
