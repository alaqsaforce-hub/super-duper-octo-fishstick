package com.voicedock.ai.connectors

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.voicedock.ai.knowledge.TextExtract
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Properties
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.search.BodyTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.SubjectTerm
import kotlin.coroutines.resume

/** Text from a MIME message: text/plain preferred, else HTML stripped; attachments ignored. JVM-testable. */
object MailText {
    fun bodyOf(p: Part): String {
        val plain = StringBuilder(); val html = StringBuilder()
        collect(p, plain, html, 0)
        return plain.toString().trim().ifBlank { TextExtract.html(html.toString()) }.take(200_000)
    }

    private fun collect(p: Part, plain: StringBuilder, html: StringBuilder, depth: Int) {
        if (depth > 8) return
        if (Part.ATTACHMENT.equals(p.disposition, true) || !p.fileName.isNullOrBlank()) return
        when {
            p.isMimeType("text/plain") -> plain.append(p.content as? String ?: "").append('\n')
            p.isMimeType("text/html") -> html.append(p.content as? String ?: "").append('\n')
            p.isMimeType("multipart/*") -> { val mp = p.content as Multipart; for (i in 0 until mp.count) collect(mp.getBodyPart(i), plain, html, depth + 1) }
            p.isMimeType("message/rfc822") -> (p.content as? Part)?.let { collect(it, plain, html, depth + 1) }
        }
    }

    fun toItem(m: Message, id: String): MailItem {
        val from = m.from?.firstOrNull()?.let { (it as? InternetAddress)?.toUnicodeString() ?: it.toString() }.orEmpty()
        val body = runCatching { bodyOf(m) }.getOrDefault("")
        return MailItem(id, m.subject.orEmpty(), from, m.sentDate?.toString().orEmpty(), body.take(160).replace('\n', ' '), body)
    }
}

/**
 * Gmail over IMAP with a Google "App password" (needs 2-Step Verification). The folder is opened READ_ONLY,
 * so nothing is marked read, moved or deleted.
 */
class ImapMail(private val email: String, private val appPassword: String, private val host: String = "imap.gmail.com") {
    fun recent(query: String, max: Int = 20): List<MailItem> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps"); put("mail.imaps.host", host); put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true"); put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        try {
            store.connect(host, email, appPassword.replace(" ", ""))
            val inbox = store.getFolder("INBOX"); inbox.open(Folder.READ_ONLY)
            try {
                val msgs: Array<Message> = if (query.isBlank()) {
                    val n = inbox.messageCount
                    if (n == 0) emptyArray() else inbox.getMessages((n - max + 1).coerceAtLeast(1), n)
                } else inbox.search(OrTerm(arrayOf(SubjectTerm(query), FromStringTerm(query), BodyTerm(query))))
                return msgs.reversed().take(max).mapIndexed { i, m -> MailText.toItem(m, "imap:${m.messageNumber}:$i") }
            } finally { runCatching { inbox.close(false) } }
        } catch (e: javax.mail.AuthenticationFailedException) {
            throw ConnectorException(ConnectorException.Kind.AUTH, 0, "IMAP login failed")
        } catch (e: javax.mail.MessagingException) {
            throw ConnectorException(ConnectorException.Kind.NETWORK, 0, e.javaClass.simpleName + ": " + (e.message ?: "").take(120))
        } finally { runCatching { store.close() } }
    }
}

/**
 * Google OAuth via Google Identity Services AuthorizationClient (no password ever reaches VoiceDock).
 * Works only after the developer registers an Android OAuth client (package + SHA-1) — see docs/OAUTH-SETUP.md.
 * The short-lived access token is kept in memory only.
 */
object GoogleAuth {
    sealed interface Outcome {
        data class Token(val token: String, val email: String?) : Outcome
        data class NeedsConsent(val sender: IntentSender) : Outcome
        data class Failed(val notSetUp: Boolean, val message: String) : Outcome
    }

    @Volatile var token: String? = null; private set

    private fun request(scopes: List<String>) = AuthorizationRequest.builder().setRequestedScopes(scopes.map { Scope(it) }).build()

    suspend fun authorize(activity: Activity, scopes: List<String>): Outcome = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(activity).authorize(request(scopes))
            .addOnSuccessListener { r: AuthorizationResult ->
                if (r.hasResolution()) r.pendingIntent?.intentSender?.let { cont.resume(Outcome.NeedsConsent(it)) }
                    ?: cont.resume(Outcome.Failed(false, "no consent screen"))
                else r.accessToken?.let { token = it; cont.resume(Outcome.Token(it, r.toGoogleSignInAccount()?.email)) }
                    ?: cont.resume(Outcome.Failed(false, "no token"))
            }
            .addOnFailureListener { e -> cont.resume(failure(e)) }
    }

    fun fromConsentResult(activity: Activity, data: Intent?): Outcome = try {
        val r = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)
        r.accessToken?.let { token = it; Outcome.Token(it, r.toGoogleSignInAccount()?.email) } ?: Outcome.Failed(false, "no token")
    } catch (e: Exception) { failure(e) }

    private fun failure(e: Exception): Outcome {
        val code = (e as? ApiException)?.statusCode
        // 10 = DEVELOPER_ERROR: no OAuth client registered for this package + signing certificate.
        return Outcome.Failed(notSetUp = code == 10 || code == 17, message = "${code ?: ""} ${e.message ?: e.javaClass.simpleName}".trim().take(200))
    }

    fun clear() { token = null }
}
