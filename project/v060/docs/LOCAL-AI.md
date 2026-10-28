# Local / self-hosted AI endpoint (v0.6)

The audit found two contradictions that made a local-first model impossible in v0.5.0:

1. `AiPolicy.blockReason()` started with `localOnly -> "local_only"`, so **"Local-only mode"
   also blocked the user's own Ollama / LM Studio / llama.cpp server** — the exact opposite of
   local-first.
2. `res/xml/network_security_config.xml` forbade all cleartext, while `AppSettings.customAiUrl`
   suggested `http://192.168.0.10:11434/v1`. Android 9+ therefore refused the connection with
   `CLEARTEXT communication not permitted`.

## What v0.6 changes

- `AiPolicy.isLocalEndpoint(url)` recognises loopback (`localhost`, `127.0.0.1`, `::1`),
  emulator aliases (`10.0.2.2`, `10.150.59.209`) and RFC1918 private ranges
  (`10.x`, `192.168.x`, `172.16–31.x`).
- `localOnly` now blocks **cloud** providers only. A CUSTOM provider pointing at a local endpoint
  is usable in Local-only mode.
- A `http://` URL that is **not** a local endpoint returns `insecure_url` and is rejected even
  when cloud consent is on. The app never silently downgrades to cleartext.
- A public `https://custom` endpoint still needs the same `cloudAllowed` consent as Gemini/Groq.
- Endpoint parsing rejects user-info tricks and fake private-IP hostnames; custom clients do not follow
  redirects. The same local/cloud gate applies to provider model-list tests as well as inference.
  A local server can itself forward data elsewhere; VoiceDock cannot control that server.
- `network_security_config.xml` keeps `cleartextTrafficPermitted="false"` as the base and adds one
  narrow `<domain-config>` for exact loopback/LAN hosts.

## Important Android limitation (cannot be fixed in XML)

Android's Network Security Configuration **cannot express CIDR ranges**. The `<domain-config>`
allow-list therefore contains exact hosts only:

```
localhost · 127.0.0.1 · 10.0.2.2 · 10.150.59.209 · 192.168.0.10
```

If your server has a different LAN IP you have two safe options:

1. **Use `https://`** (recommended). For a self-signed certificate, add a `<trust-anchors>` block
   with `<certificates src="user" />` inside that domain-config and install the CA on the phone.
2. Add your exact IP as another `<domain includeSubdomains="false">…</domain>` line and rebuild.

Do **not** set `cleartextTrafficPermitted="true"` in `<base-config>`: that would allow the app's
API keys and document text to travel unencrypted to any host.

## Verification

```bash
./gradlew :app:testDebugUnitTest --tests 'com.voicedock.ai.AiPolicyLocalTest'
```

Regression tests are in `app/src/test/java/com/voicedock/ai/AiPolicyLocalTest.kt`.
