package dev.konraditurbe.osmosis.rsdk

import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process bus for the GPS-sync foreground service's lifecycle. The R-SDK GPS flow and the WiFi
 * media-offload flow both drive one BLE GATT link to the same camera, and running them at once is
 * exactly what produced the disconnection / "camera says remote connected but the app can't find it"
 * reports in the field. So the UI has to lock media browsing while a GPS link is bound to a camera —
 * and to do that it needs to observe the service's state. [GpsService] is the sole writer; the UI
 * observes via [addListener].
 */
object GpsSyncState {
    enum class Phase {
        STOPPED,   // no GPS-sync service running
        STARTING,  // service up, R-SDK handshaking (not yet bound to the camera)
        ACTIVE,    // R-SDK bound to the camera and streaming GPS — media browsing must be blocked
    }

    fun interface Listener { fun onGpsSyncState(phase: Phase, cameraName: String?) }

    @Volatile var phase: Phase = Phase.STOPPED; private set
    @Volatile var cameraName: String? = null; private set

    /** True once the R-SDK link is bound to the camera — the point past which offload must not start. */
    val locked: Boolean get() = phase != Phase.STOPPED

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** Called by [GpsService] only. Fires listeners synchronously; the UI marshals to the main thread. */
    fun set(newPhase: Phase, name: String? = cameraName) {
        phase = newPhase
        cameraName = if (newPhase == Phase.STOPPED) null else name
        listeners.forEach { it.onGpsSyncState(phase, cameraName) }
    }

    /** Register [l] and immediately deliver the current state so a returning UI restores its lock. */
    fun addListener(l: Listener) { listeners.add(l); l.onGpsSyncState(phase, cameraName) }
    fun removeListener(l: Listener) { listeners.remove(l) }
}
