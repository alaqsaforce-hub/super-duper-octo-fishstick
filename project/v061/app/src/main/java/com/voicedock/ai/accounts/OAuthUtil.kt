package com.voicedock.ai.accounts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.Closeable
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** RFC 7636 PKCE + random OAuth values. */
object Pkce {
    private val rnd = SecureRandom()
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun random(bytes: Int = 32): String = b64url(ByteArray(bytes).also { rnd.nextBytes(it) })
    fun verifier(): String = random(64) // 86 chars (allowed 43–128)
    fun challenge(verifier: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/**
 * One-shot HTTP listener on 127.0.0.1 that receives the browser redirect at the end of an OAuth login
 * (the "loopback" redirect recommended for native apps, RFC 8252). Only [path] is answered; it closes itself.
 */
class LoopbackReceiver(private val path: String, preferredPort: Int = 0) : Closeable {
    private val server = ServerSocket().apply {
        reuseAddress = true
        val addr = InetAddress.getByName("127.0.0.1")
        try { bind(InetSocketAddress(addr, preferredPort)) } catch (e: Exception) { if (preferredPort != 0) bind(InetSocketAddress(addr, 0)) else throw e }
        soTimeout = 500
    }
    val port: Int get() = server.localPort
    val redirectUri: String get() = "http://127.0.0.1:$port$path"

    /** Waits for the callback; returns its query parameters. [page] renders the HTML shown in the browser. */
    suspend fun await(timeoutMs: Long, page: (Map<String, String>) -> String): Map<String, String> = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            while (isActive) {
                val sock = try { server.accept() } catch (e: SocketTimeoutException) { continue }
                sock.use { s ->
                    s.soTimeout = 5000
                    val reader = s.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = reader.readLine().orEmpty()
                    while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break } // drain headers
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val p = target.substringBefore('?')
                    val out = s.getOutputStream()
                    if (requestLine.startsWith("GET ") && p == path) {
                        val q = parseQuery(target.substringAfter('?', ""))
                        val body = page(q).toByteArray(Charsets.UTF_8)
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(body); out.flush()
                        return@withTimeout q
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                    }
                }
            }
            emptyMap()
        }
    }

    override fun close() { runCatching { server.close() } }

    companion object {
        fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}

class AuthException(val code: String, message: String, val terminal: Boolean = false) : Exception(message)

/** Minimal JWT handling: decode + RS256 signature check against a JWKS + standard OIDC claim checks. */
object Jwt {
    private val json = Json { ignoreUnknownKeys = true }
    private fun dec(s: String) = Base64.getUrlDecoder().decode(s.padEnd((s.length + 3) / 4 * 4, '='))

    fun header(token: String): JsonObject = json.parseToJsonElement(String(dec(token.split('.')[0]), Charsets.UTF_8)).jsonObject
    fun payload(token: String): JsonObject = json.parseToJsonElement(String(dec(token.split('.')[1]), Charsets.UTF_8)).jsonObject

    fun verifyRs256(token: String, jwks: JsonObject): Boolean {
        val parts = token.split('.')
        if (parts.size != 3) return false
        val h = header(token)
        if (h["alg"]?.jsonPrimitive?.contentOrNull != "RS256") return false
        val kid = h["kid"]?.jsonPrimitive?.contentOrNull
        val keys = (jwks["keys"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            .filter { it["kty"]?.jsonPrimitive?.contentOrNull == "RSA" && (kid == null || it["kid"]?.jsonPrimitive?.contentOrNull == kid) }
        for (k in keys) {
            val n = k["n"]?.jsonPrimitive?.contentOrNull ?: continue
            val e = k["e"]?.jsonPrimitive?.contentOrNull ?: continue
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, dec(n)), BigInteger(1, dec(e))))
            val ok = Signature.getInstance("SHA256withRSA").run {
                initVerify(pub); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(dec(parts[2]))
            }
            if (ok) return true
        }
        return false
    }

    /** @return null if the claims are valid, else the reason. */
    fun claimProblem(p: JsonObject, issuer: String, audience: String, nonce: String?, nowSec: Long, skewSec: Long = 120): String? {
        val iss = p["iss"]?.jsonPrimitive?.contentOrNull
        if (iss != issuer) return "issuer"
        val aud = when (val a = p["aud"]) { is JsonArray -> a.mapNotNull { it.jsonPrimitive.contentOrNull }; is JsonPrimitive -> listOfNotNull(a.contentOrNull); else -> emptyList() }
        if (audience !in aud) return "audience"
        val exp = p["exp"]?.jsonPrimitive?.longOrNull ?: return "exp"
        if (exp + skewSec < nowSec) return "expired"
        if (nonce != null && p["nonce"]?.jsonPrimitive?.contentOrNull != nonce) return "nonce"
        if (p["sub"]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) return "sub"
        return null
    }
}
