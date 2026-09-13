package app.gamenative.mods

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModDeploymentCoordinatorTest {
    @Test
    fun sameGameMutationsAreSerialized() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()

        List(8) {
            async {
                ModDeploymentCoordinator.withGameLock("game") {
                    maximum.updateAndGet { previous -> maxOf(previous, active.incrementAndGet()) }
                    delay(5)
                    active.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(1, maximum.get())
    }

    @Test
    fun oneProfileTransactionCanCallNestedGameMutations() = runBlocking {
        var nestedCompleted = false

        ModDeploymentCoordinator.withGameLock("game") {
            ModDeploymentCoordinator.withGameLock("game") {
                nestedCompleted = true
            }
        }

        assertTrue(nestedCompleted)
    }
}
