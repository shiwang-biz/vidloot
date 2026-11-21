package com.bizfunnel.vidloot.license

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the license client against an in-memory server that follows the
 * License Manager protocol (same as the SDKs' live tests, without a network).
 */
class LicenseClientTest {
    // Fixed test key: seed bytes 0..31 (the same key the Python stand-in server uses).
    private val seed = ByteArray(32) { it.toByte() }
    private val priv = Ed25519PrivateKeyParameters(seed, 0)
    private val pub = "A6EHv/POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg="

    private fun b64u(b: ByteArray): String {
        val s = java.util.Base64.getUrlEncoder().withoutPadding()
        return s.encodeToString(b)
    }

    private fun sign(msg: String): String {
        val s = Ed25519Signer(); s.init(true, priv)
        val m = msg.toByteArray(); s.update(m, 0, m.size)
        return b64u(s.generateSignature())
    }

    private fun token(payload: JSONObject): String {
        val head = "v1.k1." + b64u(payload.toString().toByteArray())
        return "$head.${sign(head)}"
    }

    private var now = 1_800_000_000L
    private var offline = false
    private var tamperNonce = false
    private val seats = mutableSetOf<String>()
    private var calls = 0

    private fun server(url: String, body: String, @Suppress("UNUSED_PARAMETER") t: Int): Pair<Int, String> {
        calls++
        if (offline) throw java.net.ConnectException("offline")
        val req = JSONObject(body)
        val ep = url.substringAfterLast('/')
        val key = req.getString("license_key")
        val mid = req.getString("machine_id")
        val product = req.getString("product")
        val out = JSONObject()
        var local: String? = null
        val status = when {
            key == "VLT-EXPIRED" -> "expired"
            ep == "deactivate" -> { seats.remove(mid); "deactivated" }
            ep == "validate" && mid !in seats -> "not_activated"
            else -> {
                seats += mid
                local = token(
                    JSONObject().put("lid", 7).put("pid", product).put("plan", "Pro").put("term", "yearly")
                        .put("features", org.json.JSONArray(listOf("playlists", "subtitles")))
                        .put("limits", JSONObject().put("parallel_downloads", 3))
                        .put("status", "active").put("exp", now + 365 * 86400).put("updates_until", "2027-11-21")
                        .put("licensed_to", "Test Customer").put("iat", now)
                        .put("next_check", now + 3 * 86400).put("grace_until", now + 10 * 86400)
                        .put("bind", JSONObject().put("type", "machine").put("value", LicenseClient.sha256Hex("$product|$mid"))),
                )
                "active"
            }
        }
        val resp = JSONObject().put("status", status).put("nonce", if (tamperNonce) "x" else req.getString("nonce")).put("ts", now)
        if (local != null) { resp.put("local_key_sha256", LicenseClient.sha256Hex(local)); out.put("local_key", local) }
        out.put("message", if (status == "expired") "This license expired." else "OK").put("response", token(resp))
        return 200 to out.toString()
    }

    private val store = object : LicenseClient.Storage {
        var v: String? = null
        override fun get() = v
        override fun set(value: String) { v = value }
        override fun remove() { v = null }
    }

    private fun client(device: String = "device-123") = LicenseClient(
        apiUrl = "https://licenses.test/api/v1", product = "vidloot-desktop", publicKeys = mapOf("k1" to pub),
        storage = store, deviceId = { device }, transport = ::server, clock = { now },
    )

    @Test fun signaturesMatchThePythonSigner() {
        // Ed25519 is deterministic: same key + message must give the same signature everywhere.
        assertEquals("mgoe9_lyVRtJjSEWa1kyB1Tkpol7yVVM7yqijkXqASPjNuVaA9Jz0fTwNlWksG6o2RszdOpA0gXLxBNHjsDECA", sign("v1.k1.abc"))
    }

    @Test fun activateCheckOfflineGraceDeactivate() {
        val c = client()
        assertEquals("no_key", c.check().status)

        val a = c.activate(" vlt-pro-1 ")
        assertTrue(a.valid); assertEquals("active", a.status)
        assertTrue(a.hasFeature("playlists")); assertEquals(3L, a.limit("parallel_downloads"))
        assertEquals("VLT-PRO-1", c.storedKey())
        val e = Entitlements.from(a)
        assertEquals(Entitlements("Pro", 0, true, true, 3), e)

        // Before next_check: trusted offline, no network call.
        val before = calls
        offline = true
        assertTrue(c.check().valid)
        assertEquals(before, calls)

        // After next_check with the server down: grace period.
        now += 4 * 86400
        val g = c.check()
        assertTrue(g.valid); assertEquals("grace", g.status)

        // Past grace: locked.
        now += 7 * 86400
        assertFalse(c.check().valid)

        // Back online: re-validates.
        offline = false
        assertTrue(c.check().valid)

        val d = c.deactivate()
        assertEquals("deactivated", d.status)
        assertNull(c.storedKey())
    }

    @Test fun licenseIsBoundToTheDevice() {
        client("device-123").activate("VLT-PRO-1")
        // The same stored license copied to another phone is refused.
        val r = client("another-phone").check()
        assertFalse(r.valid)
        assertEquals("invalid", r.status)
        assertEquals("This license was activated on another device.", r.message)
    }

    @Test fun rejectsForgedOrReplayedAnswers() {
        tamperNonce = true
        val r = client().activate("VLT-PRO-1")
        assertFalse(r.valid); assertEquals("error", r.status)
        assertNull(store.v)
    }

    @Test fun rejectsEditedLocalKey() {
        val c = client()
        c.activate("VLT-PRO-1")
        val saved = JSONObject(store.v!!)
        val parts = saved.getString("local_key").split('.').toMutableList()
        val payload = JSONObject(String(LicenseClient.b64decode(parts[2]))).put("plan", "Enterprise")
        parts[2] = b64u(payload.toString().toByteArray())
        store.v = saved.put("local_key", parts.joinToString(".")).toString()
        offline = true
        val r = c.check()
        assertFalse(r.valid) // tampered key isn't trusted, and the server can't be reached
    }

    @Test fun expiredKeyIsRefused() {
        val r = client().activate("VLT-EXPIRED")
        assertFalse(r.valid); assertEquals("expired", r.status); assertEquals("This license expired.", r.message)
    }
}
