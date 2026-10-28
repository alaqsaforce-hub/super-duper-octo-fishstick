package com.voicedock.ai

import androidx.test.core.app.ApplicationProvider
import com.voicedock.ai.accounts.Account
import com.voicedock.ai.accounts.AccountKind
import com.voicedock.ai.accounts.AccountOrder
import com.voicedock.ai.accounts.Accounts
import com.voicedock.ai.accounts.AuthException
import com.voicedock.ai.accounts.ChatGptAuth
import com.voicedock.ai.accounts.ChatGptCreds
import com.voicedock.ai.accounts.ChatGptSession
import com.voicedock.ai.accounts.Jwt
import com.voicedock.ai.accounts.LoopbackReceiver
import com.voicedock.ai.accounts.OpenRouterLogin
import com.voicedock.ai.accounts.Pkce
import com.voicedock.ai.agent.AgentAction
import com.voicedock.ai.agent.AgentBrain
import com.voicedock.ai.agent.AgentDecision
import com.voicedock.ai.agent.AgentDevice
import com.voicedock.ai.agent.AgentLimits
import com.voicedock.ai.agent.AgentPhase
import com.voicedock.ai.agent.AgentProtocol
import com.voicedock.ai.agent.AgentRunner
import com.voicedock.ai.agent.AgentSafety
import com.voicedock.ai.agent.AppMatch
import com.voicedock.ai.agent.AppTarget
import com.voicedock.ai.agent.Bounds
import com.voicedock.ai.agent.BrainInput
import com.voicedock.ai.agent.BrainReply
import com.voicedock.ai.agent.Dir
import com.voicedock.ai.agent.ElemKind
import com.voicedock.ai.agent.PromptBrain
import com.voicedock.ai.agent.ProtocolException
import com.voicedock.ai.agent.RawNode
import com.voicedock.ai.agent.ScreenModel
import com.voicedock.ai.agent.ScreenSnapshot
import com.voicedock.ai.agent.StepStatus
import com.voicedock.ai.agent.UiElement
import com.voicedock.ai.ai.AiCandidate
import com.voicedock.ai.ai.AiException
import com.voicedock.ai.ai.AiProvider
import com.voicedock.ai.ai.AiRouter
import com.voicedock.ai.ai.ChatClient
import com.voicedock.ai.ai.ChatGptClient
import com.voicedock.ai.ai.ChatMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

// ---------------------------------------------------------------- agent: screen model + protocol + safety

class AgentScreenTest {
    private fun sampleTree() = RawNode(cls = "FrameLayout", bounds = Bounds(0, 0, 1080, 2400), children = listOf(
        RawNode(cls = "RecyclerView", scrollable = true, bounds = Bounds(0, 200, 1080, 2000), children = listOf(
            RawNode(clickable = true, bounds = Bounds(0, 200, 1080, 400), children = listOf(RawNode(text = "Rahim"), RawNode(text = "please send the file"))),
            RawNode(clickable = true, bounds = Bounds(0, 400, 1080, 600), children = listOf(RawNode(text = "Office group"))),
        )),
        RawNode(cls = "EditText", editable = true, focused = true, text = "", desc = "Message", bounds = Bounds(0, 2100, 900, 2300)),
        RawNode(cls = "EditText", editable = true, password = true, desc = "PIN", bounds = Bounds(0, 2300, 900, 2400)),
        RawNode(cls = "ImageButton", clickable = true, desc = "Send", bounds = Bounds(900, 2100, 1080, 2300)),
    ))

    @Test fun buildsNumberedElements() {
        val s = ScreenModel.build(sampleTree(), "com.whatsapp", "WhatsApp", 1080, 2400)
        val send = s.elements.first { it.label == "Send" }
        assertEquals(ElemKind.BUTTON, send.kind)
        assertTrue(s.elements.any { it.editable && it.focused })
        assertTrue(s.elements.any { it.password })
        assertTrue(s.elements.any { it.label.contains("Rahim") })
        assertNotNull(s.largestScrollable())
        val text = s.render()
        assertTrue(text.contains("WhatsApp") && text.contains("Send"))
        // ids are unique
        assertEquals(s.elements.size, s.elements.map { it.id }.toSet().size)
    }

    @Test fun signatureChangesWithContent() {
        val a = ScreenModel.build(sampleTree(), "com.whatsapp", "WhatsApp", 1080, 2400)
        val b = ScreenModel.build(sampleTree().copy(children = sampleTree().children.dropLast(1)), "com.whatsapp", "WhatsApp", 1080, 2400)
        assertTrue(a.signature != b.signature)
        assertEquals(a.signature, ScreenModel.build(sampleTree(), "com.whatsapp", "WhatsApp", 1080, 2400).signature)
    }

    @Test fun parsesModelReplies() {
        val d = AgentProtocol.parse("Sure!\n```json\n{\"thought\":\"open chat\",\"action\":\"tap\",\"id\":3}\n```")
        assertEquals(AgentAction.Tap(3, null, null), d.action)
        assertEquals("open chat", d.thought)
        assertEquals(AgentAction.Scroll(Dir.DOWN, null), AgentProtocol.parse("{\"action\":{\"name\":\"scroll\",\"direction\":\"down\"}}").action)
        assertEquals(AgentAction.OpenApp("WhatsApp"), AgentProtocol.parse("{\"action\":\"open_app\",\"app\":\"WhatsApp\"}").action)
        assertEquals(AgentAction.Finish("done {ok}"), AgentProtocol.parse("{\"action\":\"finish\",\"answer\":\"done {ok}\"}").action)
        assertEquals(AgentAction.Type(5, "hi", true), AgentProtocol.parse("{\"action\":\"type\",\"id\":\"[5]\",\"text\":\"hi\",\"submit\":true}").action)
        for (bad in listOf("no json here", "{\"action\":\"tap\"}", "{\"action\":\"fly\"}", "{\"thought\":\"x\"}")) {
            try { AgentProtocol.parse(bad); fail("should fail: $bad") } catch (_: ProtocolException) {}
        }
    }

    @Test fun safetyGate() {
        val s = ScreenModel.build(sampleTree(), "com.whatsapp", "WhatsApp", 1080, 2400)
        val send = s.elements.first { it.label == "Send" }
        val row = s.elements.first { it.label.contains("Rahim") }
        val pin = s.elements.first { it.password }
        fun v(a: AgentAction, risky: Boolean = false, snap: ScreenSnapshot? = s, launch: String? = null) = AgentSafety.check(AgentDecision("", a, risky), snap, emptySet(), launch)
        assertTrue(v(AgentAction.Tap(send.id)) is AgentSafety.Verdict.Confirm)
        assertTrue(v(AgentAction.Tap(row.id)) is AgentSafety.Verdict.Allow) // long row text mentioning "send" is not a button
        assertTrue(v(AgentAction.Type(pin.id, "hello")) is AgentSafety.Verdict.Block)
        assertTrue(v(AgentAction.Type(null, "123456")) is AgentSafety.Verdict.Block)
        assertTrue(v(AgentAction.Type(null, "hello", submit = true)) is AgentSafety.Verdict.Confirm)
        assertTrue(v(AgentAction.Type(null, "hello")) is AgentSafety.Verdict.Allow)
        assertTrue(v(AgentAction.Scroll(Dir.DOWN)) is AgentSafety.Verdict.Allow)
        assertTrue(v(AgentAction.OpenApp("bKash"), launch = "com.bKash.customerapp") is AgentSafety.Verdict.Block)
        assertTrue(v(AgentAction.OpenApp("Gallery"), launch = "com.miui.gallery") is AgentSafety.Verdict.Allow)
        assertTrue(v(AgentAction.OpenApp("বিকাশ"), launch = null) is AgentSafety.Verdict.Block)
        assertTrue(v(AgentAction.Tap(row.id), snap = s.copy(pkg = "com.example.mybank")) is AgentSafety.Verdict.Block)
        assertTrue(v(AgentAction.Tap(row.id), snap = s.copy(pkg = "com.example.nexus", appLabel = "DBBL Bank")) is AgentSafety.Verdict.Block)
        assertTrue(v(AgentAction.Tap(row.id), snap = s.copy(pkg = "com.example.x", appLabel = "বিকাশ")) is AgentSafety.Verdict.Block)
        assertFalse(AgentSafety.isFinanceLabel("Gallery"))
        assertTrue(v(AgentAction.Tap(row.id), risky = true) is AgentSafety.Verdict.Confirm)
        assertTrue(AgentSafety.looksSecret("4111 1111 1111 1111"))
        assertFalse(AgentSafety.looksSecret("+8801711000000"))
        assertFalse(AgentSafety.looksSecret("01711000000"))
        assertEquals("মুছুন", AgentSafety.riskWord("মুছুন")?.let { "মুছুন" })
    }

    @Test fun appMatching() {
        val apps = listOf(AppTarget("com.whatsapp.w4b", "WhatsApp Business"), AppTarget("com.whatsapp", "WhatsApp"),
            AppTarget("com.miui.gallery", "Gallery"), AppTarget("com.google.android.youtube", "YouTube"))
        assertEquals("com.whatsapp", AppMatch.best("whatsapp", apps)?.pkg)
        assertEquals("com.whatsapp.w4b", AppMatch.best("WhatsApp Business", apps)?.pkg)
        assertEquals("com.miui.gallery", AppMatch.best("গ্যালারি", apps, mapOf("গ্যালারি" to "gallery"))?.pkg)
        assertEquals("com.google.android.youtube", AppMatch.best("com.google.android.youtube", apps)?.pkg)
        assertNull(AppMatch.best("telegram", apps))
    }
}

// ---------------------------------------------------------------- agent runner with a fake phone

private class FakeDevice(var screen: ScreenSnapshot) : AgentDevice {
    val log = mutableListOf<String>()
    var shots = 0
    var onTap: (UiElement) -> Unit = {}
    override suspend fun snapshot() = screen
    override suspend fun screenshot(): ByteArray? { shots++; return byteArrayOf(1, 2, 3) }
    override suspend fun tap(e: UiElement): Boolean { log += "tap:${e.label}"; onTap(e); return true }
    override suspend fun tapAt(x: Int, y: Int): Boolean { log += "tapAt:$x,$y"; return true }
    override suspend fun longPress(e: UiElement) = true.also { log += "long:${e.label}" }
    override suspend fun longPressAt(x: Int, y: Int) = true
    override suspend fun setText(e: UiElement, text: String, submit: Boolean) = true.also { log += "type:$text:$submit" }
    override suspend fun scroll(target: UiElement?, dir: Dir, screen: ScreenSnapshot) = true.also { log += "scroll:$dir" }
    override suspend fun back() = true.also { log += "back" }
    override suspend fun home() = true.also { log += "home" }
    override fun resolveApp(name: String) = if (name.equals("bkash", true)) AppTarget("com.bKash.customerapp", "bKash") else AppTarget("com.miui.gallery", "Gallery")
    override suspend fun launch(t: AppTarget) = true.also { log += "launch:${t.pkg}" }
}

private class ScriptBrain(vararg replies: String) : AgentBrain {
    private val q = ArrayDeque(replies.toList())
    val inputs = mutableListOf<BrainInput>()
    override suspend fun decide(input: BrainInput): BrainReply {
        inputs += input
        val r = q.removeFirstOrNull() ?: "{\"action\":\"finish\",\"answer\":\"end\"}"
        if (r == "THROW") throw AiException(AiException.Kind.RATE_LIMIT, 429, "limit")
        return BrainReply(AgentProtocol.parse(r), "Fake · m")
    }
}

class AgentRunnerTest {
    private val els = listOf(
        UiElement(1, ElemKind.BUTTON, "Office group", Bounds(0, 200, 1080, 400), clickable = true),
        UiElement(2, ElemKind.BUTTON, "Send", Bounds(900, 2100, 1080, 2300), clickable = true),
        UiElement(3, ElemKind.FIELD, "Message", Bounds(0, 2100, 900, 2300), editable = true, focused = true),
        UiElement(4, ElemKind.LIST, "", Bounds(0, 200, 1080, 2000), scrollable = true),
    )
    private fun screen() = ScreenSnapshot("com.whatsapp", "WhatsApp", 1080, 2400, els)
    private val fast = AgentLimits(settleMs = 1)

    @Test fun readsNotesAndFinishes() = runTest {
        val dev = FakeDevice(screen())
        val brain = ScriptBrain(
            "{\"thought\":\"read\",\"action\":\"note\",\"text\":\"Office: meeting at 5\"}",
            "{\"action\":\"scroll\",\"direction\":\"down\"}",
            "{\"action\":\"tap\",\"id\":1}",
            "{\"action\":\"finish\",\"answer\":\"Summary: meeting at 5\"}")
        val r = AgentRunner(dev, brain, fast).run("summarise")
        assertEquals(AgentPhase.DONE, r.phase)
        assertEquals("Summary: meeting at 5", r.answer)
        assertTrue(r.notes.contains("meeting at 5"))
        assertEquals(listOf("scroll:DOWN", "tap:Office group"), dev.log)
        assertTrue(brain.inputs[1].notes.contains("meeting at 5"))
    }

    @Test fun riskyTapWaitsForPermission() = runTest {
        val dev = FakeDevice(screen())
        val runner = AgentRunner(dev, ScriptBrain("{\"action\":\"tap\",\"id\":2}", "{\"action\":\"tap\",\"id\":2}", "{\"action\":\"finish\",\"answer\":\"ok\"}"), fast)
        val job = async { runner.run("send hi") }
        runner.ui.first { it.pending != null }
        assertEquals(AgentPhase.WAITING_USER, runner.ui.value.phase)
        assertTrue(dev.log.isEmpty()) // nothing pressed before the answer
        runner.answerConfirm(false)
        runner.ui.first { it.pending != null && it.steps.any { s -> s.status == StepStatus.DENIED } }
        runner.answerConfirm(true)
        val r = job.await()
        assertEquals(AgentPhase.DONE, r.phase)
        assertEquals(listOf("tap:Send"), dev.log)
    }

    @Test fun blocksFinanceAppAndStops() = runTest {
        val dev = FakeDevice(screen())
        val r = AgentRunner(dev, ScriptBrain("{\"action\":\"open_app\",\"app\":\"bkash\"}", "{\"action\":\"finish\",\"answer\":\"no\"}"), fast).run("pay")
        assertEquals(AgentPhase.DONE, r.phase)
        assertTrue(dev.log.none { it.startsWith("launch") })
    }

    @Test fun sensitiveScreenNeverReachesBrainOrScreenshot() = runTest {
        val protectedScreens = listOf(
            ScreenSnapshot("com.bKash.customerapp", "bKash", 1080, 2400, els),
            ScreenSnapshot("com.example", "Example", 1080, 2400,
                listOf(UiElement(1, ElemKind.FIELD, "", Bounds(0, 0, 100, 100), editable = true, password = true))),
            ScreenSnapshot("com.example", "Example", 1080, 2400,
                listOf(UiElement(1, ElemKind.TEXT, "483910", Bounds(0, 0, 100, 100)))),
        )
        protectedScreens.forEach { screen ->
            val dev = FakeDevice(screen)
            val brain = ScriptBrain("{\"action\":\"finish\",\"answer\":\"should not run\"}")
            assertEquals(AgentPhase.FAILED, AgentRunner(dev, brain, fast).run("inspect").phase)
            assertTrue(brain.inputs.isEmpty())
            assertEquals(0, dev.shots)
            assertTrue(dev.log.isEmpty())
        }
    }

    @Test fun badRepliesAndProviderErrorsEndTheTask() = runTest {
        val r1 = AgentRunner(FakeDevice(screen()), object : AgentBrain {
            override suspend fun decide(input: BrainInput): BrainReply = BrainReply(AgentProtocol.parse("garbage"), "x")
        }, fast).run("x")
        assertEquals(AgentPhase.FAILED, r1.phase)
        val r2 = AgentRunner(FakeDevice(screen()), ScriptBrain("THROW", "THROW"), fast).run("x")
        assertEquals(AgentPhase.FAILED, r2.phase)
    }

    @Test fun stopEndsWhileWaiting() = runTest {
        val runner = AgentRunner(FakeDevice(screen()), ScriptBrain("{\"action\":\"ask_user\",\"question\":\"Which chat?\"}"), fast)
        val job = async { runner.run("x") }
        runner.ui.first { it.question != null }
        runner.stop()
        assertEquals(AgentPhase.STOPPED, job.await().phase)
    }

    @Test fun lookTakesScreenshotNextTurn() = runTest {
        val dev = FakeDevice(screen())
        val brain = ScriptBrain("{\"action\":\"look\"}", "{\"action\":\"finish\",\"answer\":\"a photo\"}")
        AgentRunner(dev, brain, fast).run("describe")
        assertNull(brain.inputs[0].screenshot)
        assertNotNull(brain.inputs[1].screenshot)
    }

    @Test fun promptBrainSendsScreenshotAndParses() = runTest {
        var gotImages = -1
        val brain = PromptBrain { _, user, images -> gotImages = images.size; assertTrue(user.contains("Office group")); "{\"action\":\"back\"}" to "P · m" }
        val r = brain.decide(BrainInput("g", 1, 30, "", emptyList(), emptyList(), screen(), byteArrayOf(9)))
        assertEquals(AgentAction.Back, r.decision.action)
        assertEquals(1, gotImages)
    }
}

// ---------------------------------------------------------------- accounts / OAuth

class OAuthTest {
    @Test fun pkceRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertTrue(v.length in 43..128 && v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    private val b64u = Base64.getUrlEncoder().withoutPadding()
    private fun unsigned(b: BigInteger): ByteArray = b.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun keyAndJwks(): Pair<java.security.KeyPair, kotlinx.serialization.json.JsonObject> {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pub = kp.public as RSAPublicKey
        val jwks = buildJsonObject { put("keys", buildJsonArray { add(buildJsonObject {
            put("kty", "RSA"); put("kid", "k1"); put("n", b64u.encodeToString(unsigned(pub.modulus))); put("e", b64u.encodeToString(unsigned(pub.publicExponent)))
        }) }) }
        return kp to jwks
    }

    private fun sign(kp: java.security.KeyPair, payload: String): String {
        val h = b64u.encodeToString("{\"alg\":\"RS256\",\"kid\":\"k1\"}".toByteArray())
        val p = b64u.encodeToString(payload.toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(kp.private); update("$h.$p".toByteArray()); sign() }
        return "$h.$p." + b64u.encodeToString(sig)
    }

    @Test fun jwtSignatureAndClaims() {
        val (kp, jwks) = keyAndJwks()
        val now = 1_800_000_000L
        val tok = sign(kp, "{\"iss\":\"https://auth.openai.com\",\"aud\":\"oaiapp_1\",\"exp\":${now + 3600},\"nonce\":\"n1\",\"sub\":\"user-1\",\"email\":\"a@b.c\"}")
        assertTrue(Jwt.verifyRs256(tok, jwks))
        val parts = tok.split('.')
        val forged = parts[0] + "." + b64u.encodeToString("{\"iss\":\"https://auth.openai.com\",\"aud\":\"oaiapp_1\",\"sub\":\"evil\"}".toByteArray()) + "." + parts[2]
        assertFalse(Jwt.verifyRs256(forged, jwks))
        val p = Jwt.payload(tok)
        assertNull(Jwt.claimProblem(p, "https://auth.openai.com", "oaiapp_1", "n1", now))
        assertEquals("audience", Jwt.claimProblem(p, "https://auth.openai.com", "oaiapp_2", "n1", now))
        assertEquals("nonce", Jwt.claimProblem(p, "https://auth.openai.com", "oaiapp_1", "other", now))
        assertEquals("expired", Jwt.claimProblem(p, "https://auth.openai.com", "oaiapp_1", "n1", now + 7200))
        assertEquals("issuer", Jwt.claimProblem(p, "https://evil", "oaiapp_1", "n1", now))
    }

    @Test fun loopbackReceivesCallback() = runBlocking {
        LoopbackReceiver("/auth/callback").use { rx ->
            assertTrue(rx.redirectUri.startsWith("http://127.0.0.1:") && rx.redirectUri.endsWith("/auth/callback"))
            val res = async { rx.await(10_000) { q -> "<p>code=${q["code"]}</p>" } }
            val http = OkHttpClient()
            val miss = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http.newCall(Request.Builder().url("http://127.0.0.1:${rx.port}/favicon.ico").build()).execute().use { it.code }
            }
            assertEquals(404, miss)
            val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                http.newCall(Request.Builder().url(rx.redirectUri + "?code=abc%2B1&state=s%20t").build()).execute().use { it.body!!.string() }
            }
            val q = res.await()
            assertEquals("abc+1", q["code"]); assertEquals("s t", q["state"])
            assertTrue(body.contains("code=abc+1"))
        }
    }

    private fun query(url: String) = LoopbackReceiver.parseQuery(url.substringAfter('?'))

    @Test fun chatGptAuthorizeUrlAndCallbackChecks() {
        val auth = ChatGptAuth()
        val first = auth.newPending("http://127.0.0.1:1455/auth/callback", null)
        val q1 = query(auth.authorizeUrl(first, "urn:uuid:h1"))
        assertEquals("dynamic_agent_client", q1["client_id"])
        assertEquals(ChatGptAuth.AGENT_NAME, q1["agent_name_hint"])
        assertEquals("urn:uuid:h1", q1["ext_agent_host_id"])
        assertEquals("S256", q1["code_challenge_method"])
        assertEquals(Pkce.challenge(first.verifier), q1["code_challenge"])
        assertEquals("https://api.openai.com/v1", q1["resource"])
        assertTrue(q1["scope"]!!.split(' ').containsAll(listOf("openid", "offline_access", "chatgpt.tokens.use.direct")))
        assertEquals("http://127.0.0.1:1455/auth/callback", q1["redirect_uri"])
        assertNull(q1["prompt"])

        val again = auth.newPending("http://127.0.0.1:1455/auth/callback", "oaiapp_X")
        val q2 = query(auth.authorizeUrl(again, "urn:uuid:h1", idTokenHint = "idt", loginHint = "a@b.c", forceConsent = true))
        assertEquals("oaiapp_X", q2["client_id"]); assertNull(q2["agent_name_hint"]); assertEquals("idt", q2["id_token_hint"]); assertEquals("consent", q2["prompt"])

        assertEquals("c" to "oaiapp_1", auth.checkCallback(first, mapOf("state" to first.state, "code" to "c", "client_id" to "oaiapp_1")))
        fun code(p: ChatGptAuth.Pending, m: Map<String, String>) = try { auth.checkCallback(p, m); "ok" } catch (e: AuthException) { assertTrue(e.terminal); e.code }
        assertEquals("state", code(first, mapOf("state" to "x", "code" to "c", "client_id" to "oaiapp_1")))
        assertEquals("access_denied", code(first, mapOf("state" to first.state, "error" to "access_denied")))
        assertEquals("registration_incomplete", code(first, mapOf("state" to first.state, "code" to "c")))
        assertEquals("client_mismatch", code(again, mapOf("state" to again.state, "code" to "c", "client_id" to "oaiapp_Y")))
        assertEquals("ok", code(again, mapOf("state" to again.state, "code" to "c")))
    }

    @Test fun chatGptTokenExchangeRefreshAndSession() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"access_token\":\"at1\",\"refresh_token\":\"rt1\",\"id_token\":\"idt\",\"expires_in\":3600,\"scope\":\"openid chatgpt.tokens.use.direct\"}"))
        server.enqueue(MockResponse().setBody("{\"access_token\":\"at2\",\"refresh_token\":\"rt2\",\"expires_in\":3600}"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\",\"error_description\":\"reused\"}"))
        server.start()
        val base = server.url("").toString().trimEnd('/')
        var now = 1_000_000L
        val auth = ChatGptAuth(authBase = base, now = { now })
        val t = auth.exchange("oaiapp_1", "code1", "ver", "http://127.0.0.1:1455/auth/callback")
        assertEquals("at1", t.accessToken); assertEquals(1_000_000L + 3_600_000L, t.expiresAt)
        assertTrue(ChatGptAuth.PLAN_SCOPE in t.scopes)
        val req1 = server.takeRequest().body.readUtf8()
        assertTrue(req1.contains("grant_type=authorization_code") && req1.contains("code_verifier=ver") && req1.contains("client_id=oaiapp_1") && req1.contains("resource="))

        var creds: ChatGptCreds? = ChatGptCreds("oaiapp_1", "h", "sub", accessToken = "at1", refreshToken = "rt1", expiresAt = now + 60_000)
        var signedOut = false
        val session = ChatGptSession({ creds }, { creds = it }, { signedOut = true }, auth, now = { now })
        assertEquals("at2", session.accessToken()) // expires within 2 min → refreshed
        assertEquals("rt2", creds!!.refreshToken)
        assertTrue(server.takeRequest().body.readUtf8().contains("grant_type=refresh_token"))
        assertEquals("at2", session.accessToken()) // cached
        now += 3_600_000L
        try { session.accessToken(); fail() } catch (e: AuthException) { assertTrue(e.terminal); assertEquals("invalid_grant", e.code) }
        assertTrue(signedOut)
        server.shutdown()
    }

    @Test fun verifiesIdTokenViaDiscovery() {
        val (kp, jwks) = keyAndJwks()
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.path) {
                "/.well-known/openid-configuration" -> MockResponse().setBody("{\"jwks_uri\":\"${server.url("/jwks")}\",\"revocation_endpoint\":\"${server.url("/revoke")}\"}")
                "/jwks" -> MockResponse().setBody(jwks.toString())
                "/revoke" -> MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val now = 1_800_000_000L
        val auth = ChatGptAuth(authBase = server.url("").toString().trimEnd('/'), now = { now * 1000 })
        val tok = sign(kp, "{\"iss\":\"https://auth.openai.com\",\"aud\":[\"oaiapp_1\"],\"exp\":${now + 600},\"nonce\":\"n\",\"sub\":\"u1\",\"email\":\"me@x.y\"}")
        assertEquals("u1" to "me@x.y", auth.verifyIdToken(tok, "oaiapp_1", "n"))
        try { auth.verifyIdToken(tok, "oaiapp_2", "n"); fail() } catch (e: AuthException) { assertEquals("bad_claims", e.code) }
        assertTrue(auth.revoke("oaiapp_1", "rt"))
        server.shutdown()
    }

    @Test fun openRouterLogin() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"key\":\"sk-or-v1-abc\"}"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        server.start()
        val login = OpenRouterLogin(site = server.url("").toString().trimEnd('/'))
        val url = login.authUrl("http://127.0.0.1:3000/openrouter/callback", "verifier-123")
        val q = query(url)
        assertEquals("http://127.0.0.1:3000/openrouter/callback", q["callback_url"])
        assertEquals("S256", q["code_challenge_method"]); assertEquals(Pkce.challenge("verifier-123"), q["code_challenge"])
        assertEquals("sk-or-v1-abc", login.exchange("code9", "verifier-123"))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("code9", body["code"]!!.jsonPrimitive.content); assertEquals("verifier-123", body["code_verifier"]!!.jsonPrimitive.content)
        try { login.exchange("bad", "v"); fail() } catch (e: AuthException) { assertTrue(e.terminal) }
        server.shutdown()
    }

    @Test fun accountOrderFreeFirst() {
        val l = listOf(
            Account("a", "gemini", "paid old", paid = true, createdAt = 1),
            Account("b", "gemini", "free new", createdAt = 5),
            Account("c", "gemini", "free old", createdAt = 2),
            Account("d", "gemini", "off", enabled = false),
            Account("e", "groq", "other"),
            Account("f", "chatgpt", "no plan", kind = AccountKind.CHATGPT, planUsage = false),
            Account("g", "chatgpt", "signed out", kind = AccountKind.CHATGPT, loggedIn = false),
            Account("h", "chatgpt", "ok", kind = AccountKind.CHATGPT),
        )
        assertEquals(listOf("c", "b", "a"), AccountOrder.usable(l, "gemini", allowPaid = true).map { it.id })
        assertEquals(listOf("c", "b"), AccountOrder.usable(l, "gemini", allowPaid = false).map { it.id })
        assertEquals(listOf("h"), AccountOrder.usable(l, "chatgpt", allowPaid = false).map { it.id })
    }
}

// ---------------------------------------------------------------- ChatGPT client + vision routing

class ChatGptClientTest {
    @Test fun streamsResponsesApi() {
        val server = MockWebServer()
        val sse = listOf(
            "event: response.created", "data: {\"type\":\"response.created\"}", "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"হ্যালো \"}", "",
            "data: {\"type\":\"response.output_text.delta\",\"delta\":\"world\"}", "",
            "data: {\"type\":\"response.completed\",\"response\":{}}", "").joinToString("\n")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))
        server.enqueue(MockResponse().setBody("{\"models\":[{\"slug\":\"gpt-a\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"},{\"slug\":\"gpt-b\"}]}"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"limit\"}}"))
        server.start()
        val c = ChatGptClient({ "tok" }, server.url("/v1").toString())
        assertTrue(c.supportsImages)
        assertEquals("হ্যালো world", c.completeWithImages("gpt-a", "be brief", listOf(ChatMessage.user("hi")), listOf(byteArrayOf(1, 2))))
        val req = server.takeRequest()
        assertEquals("/v1/responses", req.path); assertEquals("Bearer tok", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("be brief", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"].toString()); assertEquals("true", body["stream"].toString())
        assertFalse(body.containsKey("temperature") || body.containsKey("max_output_tokens"))
        val s = body.toString()
        assertTrue(s.contains("input_text") && s.contains("input_image") && s.contains("data:image/jpeg;base64,AQI="))
        assertEquals(listOf("gpt-a", "gpt-b"), c.listModels())
        try { c.complete("gpt-a", "", listOf(ChatMessage.user("x"))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.RATE_LIMIT, e.kind) }
        server.shutdown()
    }

    @Test fun streamFailures() {
        val c = ChatGptClient({ "t" }, "http://127.0.0.1:9/v1")
        fun run(vararg lines: String): AiException.Kind? { val it = lines.iterator(); return try { c.readStream { if (it.hasNext()) it.next() else null }; null } catch (e: AiException) { e.kind } }
        assertEquals(AiException.Kind.NETWORK, run("data: {\"type\":\"response.output_text.delta\",\"delta\":\"half\"}"))
        assertEquals(AiException.Kind.AUTH, run("data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_user_not_eligible\"}}}"))
        assertEquals(AiException.Kind.EMPTY, run("data: {\"type\":\"response.incomplete\",\"response\":{}}"))
        assertEquals(AiException.Kind.EMPTY, run("data: {\"type\":\"response.completed\",\"response\":{}}"))
        assertNull(run("data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"final\"}]}]}}"))
        // token problems become AUTH errors (sign in again), never a silent billing switch
        val signedOut = ChatGptClient({ throw AuthException("invalid_grant", "x", terminal = true) }, "http://127.0.0.1:9/v1")
        try { signedOut.complete("m", "", listOf(ChatMessage.user("x"))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.AUTH, e.kind) }
        assertEquals(AiException.Kind.SERVER, ChatGptClient.kindFor("subscription_sharing_usage_unavailable", 503))
        assertEquals(AiException.Kind.BAD_REQUEST, ChatGptClient.kindFor("subscription_sharing_unsupported_capability", 400))
    }

    private class Fake(val name: String, override val supportsImages: Boolean, val fail: Boolean = false) : ChatClient {
        val calls = mutableListOf<Int>()
        override fun listModels() = listOf("m")
        override fun complete(model: String, system: String, messages: List<ChatMessage>): String { calls += 0; if (fail) throw AiException(AiException.Kind.RATE_LIMIT, 429, "x"); return name }
        override fun completeWithImages(model: String, system: String, messages: List<ChatMessage>, images: List<ByteArray>): String {
            calls += images.size; if (fail) throw AiException(AiException.Kind.RATE_LIMIT, 429, "x"); return name
        }
    }

    @Test fun routerSkipsTextOnlyProvidersForImagesAndKeepsAccountLabel() {
        val text = Fake("text", false); val vis1 = Fake("vis1", true, fail = true); val vis2 = Fake("vis2", true)
        val router = AiRouter { listOf(AiCandidate(AiProvider.GROQ, "m", text), AiCandidate(AiProvider.GEMINI, "m", vis1, "free"), AiCandidate(AiProvider.CHATGPT, "m", vis2, "me@x")) }
        val a = router.completeVision("s", listOf(ChatMessage.user("q")), listOf(byteArrayOf(1)))
        assertEquals("vis2", a.text); assertEquals("me@x", a.account)
        assertTrue(text.calls.isEmpty())
        assertEquals("text", router.complete("s", listOf(ChatMessage.user("q"))).text)
        val none = AiRouter { listOf(AiCandidate(AiProvider.GROQ, "m", text)) }
        try { none.completeVision("s", listOf(ChatMessage.user("q")), listOf(byteArrayOf(1))); fail() } catch (e: AiException) { assertEquals(AiException.Kind.NOT_CONFIGURED, e.kind) }
    }
}

// ---------------------------------------------------------------- account store (Robolectric; no Keystore secrets)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccountsStoreTest {
    @Test fun addUpdateRemoveAndHostId() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = Accounts.newId()
        val v0 = Accounts.version.value
        Accounts.add(ctx, Account(id, "chatgpt", "me", AccountKind.CHATGPT, clientId = "oaiapp_1", subject = "u1"), null)
        assertTrue(Accounts.version.value > v0)
        assertTrue(Accounts.get(ctx, id)!!.createdAt > 0)
        assertTrue(Accounts.hasUsable(ctx, "chatgpt"))
        Accounts.update(ctx, id) { it.copy(loggedIn = false) }
        assertFalse(Accounts.hasUsable(ctx, "chatgpt"))
        assertEquals("oaiapp_1", Accounts.get(ctx, id)!!.clientId) // sign-out keeps the registration
        val h = Accounts.hostId(ctx)
        assertTrue(h.startsWith("urn:uuid:")); assertEquals(h, Accounts.hostId(ctx))
        Accounts.remove(ctx, id)
        assertNull(Accounts.get(ctx, id))
    }
}
