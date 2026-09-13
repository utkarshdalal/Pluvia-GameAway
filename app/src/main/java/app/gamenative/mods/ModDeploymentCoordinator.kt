package app.gamenative.mods

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object ModDeploymentCoordinator {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private class HeldGameLocks(val keys: Set<String>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HeldGameLocks>
    }

    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withGameLock(appId: String, block: suspend () -> T): T {
        val key = appId.ifBlank { "unknown-game" }
        val held = currentCoroutineContext()[HeldGameLocks]
        if (key in held?.keys.orEmpty()) return block()
        val entry = synchronized(entries) {
            entries.getOrPut(key, ::Entry).also { it.users++ }
        }
        return try {
            entry.mutex.withLock {
                withContext(HeldGameLocks(held?.keys.orEmpty() + key)) { block() }
            }
        } finally {
            synchronized(entries) {
                entry.users--
                if (entry.users == 0) entries.remove(key, entry)
            }
        }
    }
}
