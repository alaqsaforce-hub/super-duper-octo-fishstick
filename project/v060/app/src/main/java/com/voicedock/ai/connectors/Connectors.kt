package com.voicedock.ai.connectors

import com.voicedock.ai.ai.GeminiClient
import com.voicedock.ai.knowledge.TextExtract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64

/** Connector failure with an honest reason. All connectors are READ-ONLY: no write/send/delete calls exist in this file. */
class ConnectorException(val kind: Kind, val httpCode: Int, message: String) : Exception(message) {
    enum class Kind { AUTH, NOT_FOUND, RATE_LIMIT, NETWORK, SERVER, OTHER }
}

private val lenient = Json { ignoreUnknownKeys = true }

private fun OkHttpClient.fetch(req: Request): Pair<String, ByteArray> {
    try {
        newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (r.isSuccessful) return (r.header("Content-Type") ?: "") to bytes
            val text = String(bytes, Charsets.UTF_8)
            val msg = runCatching {
                val e = lenient.parseToJsonElement(text).jsonObject
                (e["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "HTTP ${r.code}"
            val kind = when (r.code) {
                401 -> ConnectorException.Kind.AUTH
                403 -> if (msg.contains("rate limit", true)) ConnectorException.Kind.RATE_LIMIT else ConnectorException.Kind.AUTH
                404 -> ConnectorException.Kind.NOT_FOUND
                429 -> ConnectorException.Kind.RATE_LIMIT
                in 500..599 -> ConnectorException.Kind.SERVER
                else -> ConnectorException.Kind.OTHER
            }
            throw ConnectorException(kind, r.code, msg.take(300))
        }
    } catch (e: IOException) {
        throw ConnectorException(ConnectorException.Kind.NETWORK, 0, e.javaClass.simpleName)
    }
}

// ───────────────────────────── GitHub (personal access token, read-only) ─────────────────────────────

data class GhRepo(val fullName: String, val defaultBranch: String, val private: Boolean, val description: String)
data class GhFile(val path: String, val size: Long)

class GitHubClient(
    private val token: String,
    private val base: String = "https://api.github.com",
    private val http: OkHttpClient = GeminiClient.defaultHttp,
) {
    private fun req(url: String, raw: Boolean = false) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Accept", if (raw) "application/vnd.github.raw+json" else "application/vnd.github+json")
        .get().build()

    private fun json(url: String) = lenient.parseToJsonElement(String(http.fetch(req(url)).second, Charsets.UTF_8))

    fun login(): String = json("$base/user").jsonObject["login"]?.jsonPrimitive?.contentOrNull.orEmpty()

    fun repos(): List<GhRepo> = (json("$base/user/repos?per_page=100&sort=updated") as JsonArray).map { r ->
        val o = r.jsonObject
        GhRepo(
            o["full_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            o["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
            o["private"]?.jsonPrimitive?.contentOrNull == "true",
            o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /** Text files of a branch (recursive tree), largest-first excluded beyond [maxSize]. */
    fun textFiles(repo: GhRepo, maxSize: Long = 300_000): List<GhFile> {
        val tree = json("$base/repos/${repo.fullName}/git/trees/${repo.defaultBranch}?recursive=1").jsonObject["tree"]?.jsonArray.orEmpty()
        return tree.mapNotNull { t ->
            val o = t.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull != "blob") return@mapNotNull null
            val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val size = o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
            if (size > maxSize || !isText(path)) null else GhFile(path, size)
        }.sortedWith(compareBy({ !it.path.endsWith(".md", true) }, { it.path.count { c -> c == '/' } }, { it.path }))
    }

    fun file(repo: GhRepo, path: String): String {
        val enc = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return TextExtract.decode(http.fetch(req("$base/repos/${repo.fullName}/contents/$enc?ref=${repo.defaultBranch}", raw = true)).second)
    }

    companion object {
        private val TEXT_EXT = Regex("\\.(md|markdown|txt|rst|adoc|kt|kts|java|py|js|ts|tsx|jsx|go|rs|c|h|cpp|cs|php|rb|swift|dart|gs|sh|yml|yaml|toml|json|xml|html|css|sql|gradle|properties|ini|cfg|csv)$", RegexOption.IGNORE_CASE)
        fun isText(path: String) = TEXT_EXT.containsMatchIn(path) || path.substringAfterLast('/').let { it == "README" || it == "LICENSE" || it == "Dockerfile" }
    }
}

// ───────────────────────── Google Gmail + Drive REST (OAuth access token, read-only scopes) ─────────────────────────

data class MailItem(val id: String, val subject: String, val from: String, val date: String, val snippet: String, val body: String)
data class DriveFile(val id: String, val name: String, val mimeType: String, val modified: String, val size: Long)

class GoogleRest(
    private val token: String,
    private val gmailBase: String = "https://gmail.googleapis.com/gmail/v1",
    private val driveBase: String = "https://www.googleapis.com/drive/v3",
    private val http: OkHttpClient = GeminiClient.defaultHttp,
) {
    private fun req(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
    private fun json(url: String) = lenient.parseToJsonElement(String(http.fetch(req(url)).second, Charsets.UTF_8)).jsonObject

    /** Gmail search with the same syntax as the Gmail search box (e.g. "from:bank invoice newer_than:30d"). */
    fun gmailSearch(query: String, max: Int = 15): List<MailItem> {
        val url = "$gmailBase/users/me/messages".toHttpUrl().newBuilder()
            .addQueryParameter("maxResults", max.coerceIn(1, 50).toString())
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }.build().toString()
        val ids = json(url)["messages"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
        return ids.map { gmailMessage(it) }
    }

    fun gmailMessage(id: String): MailItem {
        val m = json("$gmailBase/users/me/messages/$id?format=full")
        val payload = m["payload"]?.jsonObject
        val headers = payload?.get("headers")?.jsonArray?.associate {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase() to it.jsonObject["value"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.orEmpty()
        val plain = StringBuilder(); val html = StringBuilder()
        payload?.let { collectParts(it, plain, html) }
        val body = plain.toString().ifBlank { TextExtract.html(html.toString()) }.trim()
        return MailItem(id, headers["subject"].orEmpty(), headers["from"].orEmpty(), headers["date"].orEmpty(),
            m["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty(), body)
    }

    private fun collectParts(part: JsonObject, plain: StringBuilder, html: StringBuilder) {
        val mime = part["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val data = part["body"]?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
        if (data != null && part["filename"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty()) {
            val text = runCatching { String(Base64.getUrlDecoder().decode(data.trimEnd('=')), Charsets.UTF_8) }.getOrDefault("")
            if (mime == "text/plain") plain.append(text).append('\n') else if (mime == "text/html") html.append(text).append('\n')
        }
        part["parts"]?.jsonArray?.forEach { collectParts(it.jsonObject, plain, html) }
    }

    /** Drive search by name/full text; trashed files excluded. */
    fun driveSearch(text: String, max: Int = 25): List<DriveFile> {
        val esc = text.replace("\\", "\\\\").replace("'", "\\'")
        val q = if (text.isBlank()) "trashed = false" else "trashed = false and (name contains '$esc' or fullText contains '$esc')"
        val url = "$driveBase/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("pageSize", max.coerceIn(1, 100).toString())
            .addQueryParameter("fields", "files(id,name,mimeType,modifiedTime,size)")
            // Drive rejects orderBy together with fullText search; and an empty orderBy must not be sent at all.
            .apply { if (text.isBlank()) addQueryParameter("orderBy", "modifiedTime desc") }
            .build().toString()
        return json(url)["files"]?.jsonArray?.map { f ->
            val o = f.jsonObject
            DriveFile(o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["modifiedTime"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0)
        }.orEmpty().filter { it.mimeType != "application/vnd.google-apps.folder" }
    }

    /** Google Docs/Sheets/Slides are exported as text/CSV; other files are downloaded (≤15 MB) for local extraction. */
    fun driveContent(f: DriveFile): Pair<String, ByteArray> {
        val export = when (f.mimeType) {
            "application/vnd.google-apps.document" -> "text/plain"
            "application/vnd.google-apps.spreadsheet" -> "text/csv"
            "application/vnd.google-apps.presentation" -> "text/plain"
            else -> null
        }
        if (export == null && f.size > 15L * 1024 * 1024) throw ConnectorException(ConnectorException.Kind.OTHER, 0, "too_big")
        val url = if (export != null) "$driveBase/files/${f.id}/export?mimeType=" + java.net.URLEncoder.encode(export, "UTF-8")
        else "$driveBase/files/${f.id}?alt=media"
        val (_, bytes) = http.fetch(req(url))
        return (export ?: f.mimeType) to bytes
    }

    companion object {
        const val SCOPE_GMAIL_READONLY = "https://www.googleapis.com/auth/gmail.readonly"
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    }
}
