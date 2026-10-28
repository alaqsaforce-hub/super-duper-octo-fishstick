package com.voicedock.ai.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

enum class Dir { UP, DOWN, LEFT, RIGHT }

/** One action the model may request. Coordinates (x, y) are normalised 0–999 like Gemini computer-use. */
sealed class AgentAction {
    data class Tap(val id: Int?, val x: Int? = null, val y: Int? = null) : AgentAction()
    data class LongPress(val id: Int?, val x: Int? = null, val y: Int? = null) : AgentAction()
    data class Type(val id: Int?, val text: String, val submit: Boolean = false) : AgentAction()
    data class Scroll(val direction: Dir, val id: Int? = null) : AgentAction()
    data class OpenApp(val name: String) : AgentAction()
    data object Back : AgentAction()
    data object Home : AgentAction()
    data class Wait(val seconds: Int) : AgentAction()
    data object Look : AgentAction()
    data class Note(val text: String) : AgentAction()
    data class AskUser(val question: String) : AgentAction()
    data class Finish(val answer: String) : AgentAction()

    /** Short log line, e.g. `tap [12] "Send"`. */
    fun describe(s: ScreenSnapshot?): String = when (this) {
        is Tap -> if (id != null) "tap [$id]${lbl(s, id)}" else "tap @$x,$y"
        is LongPress -> if (id != null) "long_press [$id]${lbl(s, id)}" else "long_press @$x,$y"
        is Type -> "type [${id ?: "focused"}] \"${text.take(60)}\"" + if (submit) " + enter" else ""
        is Scroll -> "scroll ${direction.name.lowercase()}" + (id?.let { " [$it]" } ?: "")
        is OpenApp -> "open_app $name"
        Back -> "back"
        Home -> "home"
        is Wait -> "wait ${seconds}s"
        Look -> "look (screenshot)"
        is Note -> "note: ${text.take(80)}"
        is AskUser -> "ask_user: ${question.take(80)}"
        is Finish -> "finish"
    }

    private fun lbl(s: ScreenSnapshot?, id: Int) = s?.byId(id)?.label?.takeIf { it.isNotBlank() }?.let { " \"${it.take(40)}\"" } ?: ""
}

data class AgentDecision(val thought: String, val action: AgentAction, val risky: Boolean = false)

class ProtocolException(message: String) : Exception(message)

/** Parses the model's JSON reply (tolerates ```json fences and text around the object). */
object AgentProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun extractJson(raw: String): String {
        val s = raw.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
        val start = s.indexOf('{')
        if (start < 0) throw ProtocolException("no JSON object")
        // first balanced object (strings may contain braces)
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        throw ProtocolException("unbalanced JSON")
    }

    fun parse(raw: String): AgentDecision {
        val o = try { json.parseToJsonElement(extractJson(raw)).jsonObject } catch (e: ProtocolException) { throw e } catch (e: Exception) {
            throw ProtocolException("invalid JSON: ${e.message?.take(80)}")
        }
        // some models nest the arguments: {"action": {"name": "tap", "id": 3}}
        val nested = o["action"] as? JsonObject
        val args: JsonObject = nested ?: o
        val name = (nested?.str("name") ?: nested?.str("type") ?: o.str("action"))?.lowercase()?.trim()
            ?: throw ProtocolException("missing action")
        val action: AgentAction = when (name) {
            "tap", "click" -> AgentAction.Tap(args.int("id"), args.int("x"), args.int("y")).also { requireTarget(it.id, it.x, it.y) }
            "long_press", "longpress", "long_click" -> AgentAction.LongPress(args.int("id"), args.int("x"), args.int("y")).also { requireTarget(it.id, it.x, it.y) }
            "type", "set_text", "write" -> AgentAction.Type(args.int("id"), args.str("text") ?: throw ProtocolException("type needs text"), args.bool("submit") ?: args.bool("press_enter") ?: false)
            "scroll", "swipe" -> AgentAction.Scroll(dir(args.str("direction") ?: "down"), args.int("id"))
            "open_app", "launch", "open" -> AgentAction.OpenApp(args.str("app") ?: args.str("app_name") ?: args.str("name")?.takeIf { nested == null } ?: throw ProtocolException("open_app needs app"))
            "back", "go_back" -> AgentAction.Back
            "home" -> AgentAction.Home
            "wait" -> AgentAction.Wait((args.int("seconds") ?: 1).coerceIn(1, 5))
            "look", "take_screenshot", "screenshot" -> AgentAction.Look
            "note", "remember" -> AgentAction.Note(args.str("text") ?: args.str("note") ?: throw ProtocolException("note needs text"))
            "ask_user", "ask" -> AgentAction.AskUser(args.str("question") ?: args.str("text") ?: throw ProtocolException("ask_user needs question"))
            "finish", "done", "answer" -> AgentAction.Finish(args.str("answer") ?: args.str("text") ?: o.str("answer") ?: "")
            else -> throw ProtocolException("unknown action '$name'")
        }
        return AgentDecision(o.str("thought").orEmpty().take(300), action, o.bool("risky") ?: args.bool("risky") ?: false)
    }

    private fun requireTarget(id: Int?, x: Int?, y: Int?) {
        if (id == null && (x == null || y == null)) throw ProtocolException("needs id or x,y")
    }

    private fun dir(s: String) = when (s.lowercase().trim()) {
        "up" -> Dir.UP; "down" -> Dir.DOWN; "left" -> Dir.LEFT; "right" -> Dir.RIGHT
        else -> throw ProtocolException("bad direction '$s'")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { p -> p.contentOrNull?.trim()?.removePrefix("[")?.removeSuffix("]")?.toIntOrNull() ?: p.doubleOrNull?.toInt() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.lowercase()?.let { v -> v == "true" || v == "yes" } }
}
