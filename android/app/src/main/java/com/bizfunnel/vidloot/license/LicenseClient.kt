package com.bizfunnel.vidloot.license

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Kotlin port of the Bizfunnel License Manager client (@bizfunnel/license-rn and the
 * Rust SDK). Same protocol and the same checks, step for step:
 *
 *  - The license is bound to this device: machine_id = sha256("<product>|<device id>").
 *  - Every server answer must carry a `response` token signed with your Ed25519 key,
 *    echoing our random nonce, dated within a day, and (if a local key came back)
 *    the SHA-256 of that local key. Anything else is rejected.
 *  - The signed local key is trusted offline until `next_check`; after that, if the
 *    server can't be reached, the app keeps working until `grace_until`.
 *  - A signed "suspended / expired / revoked / invalid" locks immediately.
 *
 * All calls block: run them off the main thread.
 */
class LicenseClient(
    apiUrl: String,
    val product: String,
    publicKeys: Map<String, String>,
    private val storage: Storage,
    private val deviceId: () -> String,
    private val appVersion: String? = null,
    private val deviceLabel: String = "Android device",
    private val platform: String = "android",
    private val timeoutMs: Int = 15_000,
    /** POST json → (http status, body). Replaceable for tests. */
    private val transport: (url: String, json: String, timeoutMs: Int) -> Pair<Int, String> = ::httpPost,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    interface Storage {
        fun get(): String?
        fun set(value: String)
        fun remove()
    }

    data class Claims(
        val lid: Long, val pid: String, val plan: String, val term: String,
        val features: List<String>, val limits: Map<String, Long>, val status: String,
        val exp: Long?, val updatesUntil: String?, val licensedTo: String,
        val iat: Long, val nextCheck: Long, val graceUntil: Long, val bindType: String, val bindValue: String,
    )

    data class Result(val valid: Boolean, val status: String, val message: String, val claims: Claims? = null) {
        fun hasFeature(flag: String) = claims?.features?.contains(flag) == true
        fun limit(name: String): Long? = claims?.limits?.get(name)
        val inGrace get() = status == "grace"
    }

    class UntrustedException : Exception("The license server's answer could not be verified")

    private val apiUrl = apiUrl.trimEnd('/')
    private val keys: Map<String, Ed25519PublicKeyParameters>

    init {
        require(this.apiUrl.isNotEmpty() && product.isNotEmpty()) { "apiUrl and product are required" }
        keys = publicKeys.mapValues { (kid, b64) ->
            val raw = b64decode(b64.trim())
            require(raw.size == 32) { "public key $kid must be 32 bytes" }
            Ed25519PublicKeyParameters(raw, 0)
        }
        require(keys.isNotEmpty()) { "at least one public key is required" }
    }

    /** Hash of (product | device id): what is sent to the server. */
    fun machineId(): String {
        val raw = deviceId().trim()
        require(raw.length >= 4) { "device id is empty" }
        return sha256Hex("$product|$raw")
    }

    fun storedKey(): String? = load()?.first?.takeIf { it.isNotEmpty() }

    /** First run / "change license": bind the key to this device. Never throws. */
    fun activate(licenseKey: String): Result {
        val key = licenseKey.trim().uppercase()
        return try {
            val r = call("activate", key)
            finish(key, r.status, r.reply)
        } catch (e: Exception) {
            Result(false, "error", errorMessage(e))
        }
    }

    /** Check the license. No network until the re-check is due (or [forceOnline]). Never throws. */
    fun check(forceOnline: Boolean = false): Result {
        val stored = load() ?: return Result(false, "no_key", "Enter your license key to activate.")
        val (key, localToken) = stored
        if (key.isEmpty()) return Result(false, "no_key", "Enter your license key to activate.")
        val local = localToken?.let { verifyClaims(it) }
        val t = clock()
        if (local != null && !forceOnline && t < local.nextCheck) return fromClaims(local, false)
        return try {
            var r = call(if (local != null) "validate" else "activate", key)
            if (r.status == "not_activated") r = call("activate", key)
            finish(key, r.status, r.reply)
        } catch (e: Exception) {
            if (local != null && t < local.graceUntil) fromClaims(local, true)
            else Result(false, "error", "Could not verify the license: ${errorMessage(e)}")
        }
    }

    /** Free this device's seat and forget the key. Never throws. */
    fun deactivate(): Result {
        val key = storedKey() ?: return Result(false, "no_key", "No license on this device.")
        return try {
            val r = call("deactivate", key)
            storage.remove()
            Result(false, r.status, r.reply.optString("message", ""))
        } catch (e: Exception) {
            Result(false, "error", errorMessage(e))
        }
    }

    // ------------------------------------------------------------------ internals

    private class Reply(val status: String, val reply: JSONObject, val signed: JSONObject)

    private fun finish(key: String, status: String, reply: JSONObject): Result {
        val token = reply.optString("local_key", "").takeIf { it.isNotEmpty() && !reply.isNull("local_key") }
        if (token != null) {
            val claims = verifyClaims(token) ?: throw UntrustedException()
            save(key, token)
            return fromClaims(claims, false)
        }
        if (status in setOf("suspended", "expired", "revoked", "invalid")) save(key, null)
        return Result(false, status, reply.optString("message", ""))
    }

    private fun fromClaims(c: Claims, grace: Boolean): Result {
        if (c.pid != product) return Result(false, "invalid", "This license is for a different product.")
        if (c.bindType == "machine") {
            val expected = sha256Hex("$product|${machineId()}")
            if (expected != c.bindValue) return Result(false, "invalid", "This license was activated on another device.")
        }
        val exp = c.exp
        if (exp != null && exp > 0 && clock() > exp) return Result(false, "expired", "This license has expired. Please renew.", c)
        if (c.status != "active" && c.status != "trial") return Result(false, c.status, "This license is not active.", c)
        if (grace) return Result(true, "grace", "Working offline: the license server could not be reached.", c)
        return Result(true, c.status, "License is valid.", c)
    }

    /** POST and trust only the signed `response` (status + our nonce + extras). */
    private fun call(endpoint: String, key: String, extra: Map<String, String> = emptyMap()): Reply {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        val body = JSONObject()
            .put("license_key", key)
            .put("product", product)
            .put("machine_id", machineId())
            .put("device_label", deviceLabel)
            .put("platform", platform)
            .put("app_version", appVersion ?: JSONObject.NULL)
            .put("nonce", nonce)
        extra.forEach { (k, v) -> body.put(k, v) }
        val (code, text) = transport("$apiUrl/$endpoint", body.toString(), timeoutMs)
        if (code >= 500) throw IllegalStateException("Server answered $code")
        val reply = JSONObject(text)
        val signed = reply.optString("response", "").takeIf { it.isNotEmpty() }?.let { verifyToken(it) }
            ?: throw UntrustedException()
        if (signed.optString("nonce") != nonce) throw UntrustedException()
        if (kotlin.math.abs(clock() - signed.optLong("ts", 0)) > 86_400) throw UntrustedException()
        if (reply.has("local_key") && !reply.isNull("local_key")) {
            if (sha256Hex(reply.getString("local_key")) != signed.optString("local_key_sha256")) throw UntrustedException()
        }
        return Reply(signed.optString("status", "error"), reply, signed)
    }

    private fun verifyClaims(token: String): Claims? {
        val p = verifyToken(token) ?: return null
        return try {
            val bind = p.getJSONObject("bind")
            val limits = mutableMapOf<String, Long>()
            p.optJSONObject("limits")?.let { l -> l.keys().forEach { k -> limits[k] = l.optLong(k) } }
            val features = p.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            Claims(
                lid = p.optLong("lid"), pid = p.getString("pid"), plan = p.optString("plan"), term = p.optString("term"),
                features = features, limits = limits, status = p.getString("status"),
                exp = if (p.isNull("exp") || !p.has("exp")) null else p.optLong("exp"),
                updatesUntil = if (p.isNull("updates_until") || !p.has("updates_until")) null else p.optString("updates_until"),
                licensedTo = p.optString("licensed_to"), iat = p.optLong("iat"),
                nextCheck = p.getLong("next_check"), graceUntil = p.getLong("grace_until"),
                bindType = bind.getString("type"), bindValue = bind.getString("value"),
            )
        } catch (_: Exception) {
            null
        }
    }

    /** v1.<kid>.<b64url payload>.<b64url Ed25519 signature over "v1.<kid>.<payload>"> */
    private fun verifyToken(token: String): JSONObject? {
        val parts = token.split('.')
        if (parts.size != 4 || parts[0] != "v1") return null
        val key = keys[parts[1]] ?: return null
        return try {
            val sig = b64url(parts[3])
            if (sig.size != 64) return null
            val head = "${parts[0]}.${parts[1]}.${parts[2]}".toByteArray(Charsets.UTF_8)
            val v = Ed25519Signer()
            v.init(false, key)
            v.update(head, 0, head.size)
            if (!v.verifySignature(sig)) return null
            JSONObject(String(b64url(parts[2]), Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun load(): Pair<String, String?>? = try {
        storage.get()?.let { JSONObject(it) }?.let { o ->
            o.optString("license_key", "") to o.optString("local_key", "").takeIf { it.isNotEmpty() && !o.isNull("local_key") }
        }
    } catch (_: Exception) {
        null
    }

    private fun save(key: String, local: String?) {
        storage.set(JSONObject().put("license_key", key).put("local_key", local ?: JSONObject.NULL).toString())
    }

    companion object {
        fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).toHex()
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun b64url(s: String): ByteArray = b64decode(s)

        /** Base64 or base64url, padding optional. (java.util.Base64 needs Android 8; Vidloot supports 7.) */
        fun b64decode(s: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val clean = s.replace('-', '+').replace('_', '/').trimEnd('=')
            val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
            var buf = 0
            var bits = 0
            for (ch in clean) {
                val v = alphabet.indexOf(ch)
                require(v >= 0) { "bad base64" }
                buf = (buf shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buf shr bits) and 0xff)
                }
            }
            return out.toByteArray()
        }

        private fun errorMessage(e: Exception): String = when (e) {
            is java.net.SocketTimeoutException -> "Request timed out"
            is java.net.UnknownHostException, is java.net.ConnectException -> "Couldn't reach the license server. Check your internet connection."
            else -> e.message ?: e.javaClass.simpleName
        }

        fun httpPost(url: String, json: String, timeoutMs: Int): Pair<Int, String> {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Accept", "application/json")
                c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                return code to text
            } finally {
                c.disconnect()
            }
        }
    }
}
