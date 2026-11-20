package com.bizfunnel.vidloot.license

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Licensing adapter. Everything the app needs from licensing goes through here:
 * the status and the plan's limits. The Bizfunnel License Manager client is
 * connected here; until then the app runs on the Free plan.
 *
 * Limits are enforced in DownloadManager.enqueue, not only in the UI.
 */
data class Entitlements(
    val plan: String,
    /** Highest video height allowed; 0 = no limit. */
    val maxHeight: Int,
    val playlists: Boolean,
    val subtitles: Boolean,
    val parallelDownloads: Int,
) {
    fun allowsHeight(h: Int) = maxHeight == 0 || h <= maxHeight

    companion object {
        /** Same Free plan as the desktop app (license.rs). */
        val FREE = Entitlements(plan = "Free", maxHeight = 720, playlists = false, subtitles = false, parallelDownloads = 1)
    }
}

data class LicenseInfo(
    /** free | active | grace | invalid */
    val status: String = "free",
    val keyHint: String? = null,
    val expiresAt: String? = null,
    val updatesUntil: String? = null,
    val message: String? = null,
    val entitlements: Entitlements = Entitlements.FREE,
) {
    val isPro get() = status == "active" || status == "grace"
}

object License {
    private val _info = MutableStateFlow(LicenseInfo())
    val info: StateFlow<LicenseInfo> = _info

    val entitlements: Entitlements get() = _info.value.entitlements

    suspend fun activate(key: String): Result<LicenseInfo> =
        Result.failure(IllegalStateException("Licensing isn't connected in this build yet."))

    suspend fun deactivate(): Result<LicenseInfo> {
        _info.value = LicenseInfo()
        return Result.success(_info.value)
    }

    suspend fun refresh(): LicenseInfo = _info.value
}
