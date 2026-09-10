package com.vaanimitra.pipeline

/**
 * Prevents overlapping wake-word capture sessions (debounce).
 */
class VoiceSessionController {

    @Volatile
    private var active = false

    fun tryAcquire(): Boolean {
        synchronized(this) {
            if (active) return false
            active = true
            return true
        }
    }

    fun release() {
        active = false
    }
}
