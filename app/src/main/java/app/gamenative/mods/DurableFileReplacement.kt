package app.gamenative.mods

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun replaceWithPreparedFile(source: File, target: File) {
    runCatching {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.getOrElse { atomicFailure ->
        runCatching {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { replacementFailure ->
            replacementFailure.addSuppressed(atomicFailure)
            throw replacementFailure
        }
    }
}
