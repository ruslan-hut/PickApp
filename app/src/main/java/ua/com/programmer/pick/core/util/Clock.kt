package ua.com.programmer.pick.core.util

/**
 * Wall-clock time source. Injectable so timing-sensitive logic — notably the
 * WebSocket half-open watchdog in [ua.com.programmer.pick.data.remote.websocket.WebSocketManager]
 * — can be driven by virtual time in unit tests instead of real
 * `System.currentTimeMillis()`.
 */
fun interface Clock {
    /** Current wall-clock time in milliseconds since the epoch. */
    fun now(): Long

    companion object {
        /** Production clock backed by the system wall clock. */
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
