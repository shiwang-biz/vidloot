package com.bizfunnel.vidloot.license

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.bizfunnel.vidloot.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Licensing adapter. Everything the app needs from licensing goes through here:
 * the status and the plan's limits. Limits are enforced in DownloadManager.enqueue,
 * not only in the UI.
 *
 * Server settings are baked in at build time (BuildConfig, from the GitHub
 * workflow's repository variables). Without them the app runs on the Free plan.
 *
 * Plan → Vidloot features (same as desktop):
 *   feature "playlists", feature "subtitles",
 *   limit "max_height" (0 or missing = no limit), limit "parallel_downloads" (missing = 3)
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

        fun from(r: LicenseClient.Result): Entitlements {
            val c = r.claims ?: return FREE
            fun limit(n: String) = r.limit(n)?.takeIf { it >= 0 }?.toInt()
            return Entitlements(
                plan = c.plan.ifBlank { "Pro" },
                maxHeight = limit("max_height") ?: 0,
                playlists = r.hasFeature("playlists"),
                subtitles = r.hasFeature("subtitles"),
                parallelDownloads = (limit("parallel_downloads") ?: 3).coerceIn(1, 8),
            )
        }
    }
}

data class LicenseInfo(
    /** free | active | grace | invalid */
    val status: String = "free",
    val connected: Boolean = false,
    val keyHint: String? = null,
    val licensedTo: String? = null,
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

    private var client: LicenseClient? = null

    @SuppressLint("HardwareIds")
    fun init(context: Context) {
        val api = BuildConfig.LICENSE_API
        val pub = BuildConfig.LICENSE_PUBKEY
        if (api.isBlank() || pub.isBlank()) return
        val sp = context.getSharedPreferences("vidloot-license", Context.MODE_PRIVATE)
        val product = BuildConfig.LICENSE_PRODUCT
        client = try {
            LicenseClient(
                apiUrl = api,
                product = product,
                publicKeys = mapOf(BuildConfig.LICENSE_KID.ifBlank { "k1" } to pub),
                storage = object : LicenseClient.Storage {
                    override fun get() = sp.getString("license", null)
                    override fun set(value: String) { sp.edit().putString("license", value).apply() }
                    override fun remove() { sp.edit().remove("license").apply() }
                },
                // ANDROID_ID: stable per phone + signing key, survives app reinstalls.
                deviceId = {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                        ?: error("This phone has no device id.")
                },
                appVersion = BuildConfig.VERSION_NAME,
                deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60),
                platform = "android",
            )
        } catch (_: Exception) {
            null
        }
        _info.value = LicenseInfo(connected = client != null)
    }

    private fun keyHint(key: String): String {
        val parts = key.split('-')
        return when {
            parts.size > 2 -> "${parts.first()}-…-${parts.last()}"
            key.length > 4 -> "…${key.takeLast(4)}"
            else -> key
        }
    }

    private fun date(secs: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(secs * 1000))

    private fun apply(r: LicenseClient.Result): LicenseInfo {
        val c = client ?: return _info.value
        val key = c.storedKey()
        val status = when {
            r.valid -> if (r.inGrace) "grace" else "active"
            r.status == "no_key" || key == null -> "free"
            else -> "invalid"
        }
        val claims = r.claims
        val info = LicenseInfo(
            status = status,
            connected = true,
            keyHint = key?.let { keyHint(it) },
            licensedTo = claims?.licensedTo?.ifBlank { null },
            expiresAt = claims?.exp?.takeIf { it > 0 }?.let { date(it) },
            updatesUntil = claims?.updatesUntil,
            message = if ((r.valid && !r.inGrace) || status == "free") null else r.message,
            entitlements = if (r.valid) Entitlements.from(r) else Entitlements.FREE,
        )
        _info.value = info
        return info
    }

    /** Cheap: the client only calls the server when its re-check is due (or [force]). */
    suspend fun refresh(force: Boolean = false): LicenseInfo = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext _info.value
        apply(c.check(force))
    }

    suspend fun activate(key: String): Result<LicenseInfo> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext Result.failure(
            IllegalStateException("Licensing isn't set up in this build (no license server configured)."),
        )
        if (key.trim().length < 6) return@withContext Result.failure(IllegalArgumentException("Please enter the full license key."))
        val r = c.activate(key)
        if (!r.valid) {
            refresh(false)
            Result.failure(IllegalStateException(r.message.ifBlank { friendly(r.status) }))
        } else {
            Result.success(apply(r))
        }
    }

    suspend fun deactivate(): Result<LicenseInfo> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext Result.failure(IllegalStateException("Licensing isn't set up in this build."))
        val r = c.deactivate()
        if (r.status == "error") return@withContext Result.failure(IllegalStateException("Couldn't deactivate: ${r.message}"))
        _info.value = LicenseInfo(connected = true)
        Result.success(_info.value)
    }

    private fun friendly(status: String) = when (status) {
        "limit_reached" -> "This key is already active on the maximum number of devices. Deactivate it on another device first."
        "expired" -> "This license has expired. Please renew."
        "suspended" -> "This license is suspended. Please contact support."
        "revoked" -> "This license was revoked."
        "invalid" -> "That license key isn't valid for Vidloot."
        "rate_limited" -> "Too many attempts. Please wait a few minutes and try again."
        else -> "This license key couldn't be activated."
    }
}
