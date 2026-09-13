package app.gamenative.ui.data

import app.gamenative.data.GameSource

enum class DownloadItemStatus {
    DOWNLOADING,
    QUEUED,
    PAUSED,
    RESUMABLE,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class DownloadItemState(
    val appId: String,
    val gameSource: GameSource,
    val gameName: String,
    val iconUrl: String,
    val progress: Float?,
    val bytesDownloaded: Long?,
    val bytesTotal: Long?,
    val etaMs: Long?,
    val statusMessage: String?,
    val isActive: Boolean?,
    val isPartial: Boolean,
    val status: DownloadItemStatus,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    val uniqueId: String
        get() = "${gameSource.name}_$appId"

    val canPause: Boolean
        get() = status == DownloadItemStatus.DOWNLOADING

    val canResume: Boolean
        get() = isPartial && (
            status == DownloadItemStatus.PAUSED ||
                status == DownloadItemStatus.RESUMABLE ||
                status == DownloadItemStatus.FAILED
            )

    val canCancel: Boolean
        // Queued rows get a delete button (dequeue/cancel) but no resume button —
        // the queue resumes them automatically when the active download finishes.
        get() = status == DownloadItemStatus.DOWNLOADING ||
            status == DownloadItemStatus.QUEUED ||
            isPartial

    val isFinished: Boolean
        get() = !isPartial && (
            status == DownloadItemStatus.COMPLETED ||
                status == DownloadItemStatus.CANCELLED ||
                status == DownloadItemStatus.FAILED
            )
}

data class CancelConfirmation(
    val appId: String,
    val gameSource: GameSource,
    val gameName: String,
)

data class DownloadsState(
    val downloads: Map<String, DownloadItemState> = emptyMap<String, DownloadItemState>(),
    val cancelConfirmation: CancelConfirmation? = null,
)
