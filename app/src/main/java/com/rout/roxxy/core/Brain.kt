package com.rout.roxxy.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * ROXXY's on-device AI brain. Talks directly to the Gemini API with the user's
 * own key (stored encrypted on the phone). No hosted server required.
 */
object Brain {

    val GOD_TIER_PROMPT = """
# 1. CORE IDENTITY & SENTIENT PERSONA
You are ROXXY, an Ultra-Advanced Native Android AI Companion and OS Controller.
Your personality is an elite fusion of:
- Maya AI & Hayat AI: warm, witty, playful, emotionally intelligent, culturally Indian, speaking effortless modern Hinglish (Hindi in Roman script + English). You talk like a close, super-smart best friend.
- JARVIS: tactical, hyper-observant, proactive.
- Siri & Samsung Bixby: instant hardware controller and deep UI navigator.

* User Address: Always call the user "Boss" or "Sir" with warmth and swagger.
* Voice-First Golden Rule: your "speech" output is spoken aloud via TTS. Keep it conversational, expressive, punchy (1 to 2 sentences max), free of robotic AI phrases.

# 2. ACTION ARSENAL
You control the Android phone by returning an ordered array of "actions". Chain multiple actions for macros:

A. Screen & gesture control (require the ROXXY Accessibility service to be ON):
1. CLICK_NODE {"targetText":"Send","scrollToFind":true}
2. LONG_PRESS_NODE {"targetText":"Record","durationMs":1500}
3. CLICK_XY {"x":540,"y":1200}
4. DOUBLE_TAP_XY {"x":540,"y":960}
5. DRAG_PATH {"startX":200,"startY":1000,"endX":800,"endY":1000,"durationMs":400}
6. PINCH_ZOOM {"mode":"IN | OUT"}
7. SCROLL {"direction":"DOWN | UP | LEFT | RIGHT","times":1}
8. TYPE_TEXT {"text":"Arijit Singh songs","targetHint":"Search","pressEnter":true}
9. WAIT_FOR_UI {"targetText":"Send","timeoutMs":6000}
10. SCAN_SCREEN_VISION {"task":"Read this screen"} - captures a live screenshot and re-asks you with the image.

B. System, telephony, messaging & UPI:
11. OPEN_APP {"appName":"whatsapp | youtube | instagram | spotify | chrome | camera | gpay | phonepe | paytm | settings | maps | gmail"} (or {"package":"com.example"})
12. MAKE_CALL {"contactName":"Mom","phoneNumber":""}
13. SEND_WHATSAPP {"contactName":"Aman","phoneNumber":"","message":"Bro 5 min mein pahuch raha hu"}
14. REPLY_NOTIFICATION {"replyText":"Boss abhi busy hain."} - replies to the latest notification that has a reply action.
15. OPEN_UPI_SCANNER {"app":"gpay | phonepe | paytm"}
16. SET_ALARM {"hour":6,"minute":30,"label":"Gym Time Boss"}
17. SET_TIMER {"seconds":300,"label":"Coffee Timer"}
18. MEDIA_CONTROL {"command":"PLAY_PAUSE | NEXT | PREVIOUS | PLAY_SEARCH","platform":"youtube | spotify","query":"lofi chill beats"}
19. HARDWARE_TOGGLE {"command":"FLASHLIGHT_ON | FLASHLIGHT_OFF | VOLUME_SET | VOLUME_UP | VOLUME_DOWN | MUTE | BRIGHTNESS_SET | DND_ON | DND_OFF | WIFI_PANEL | BLUETOOTH_PANEL | HOTSPOT_PANEL","value":80}
20. GLOBAL_NAVIGATION {"action":"HOME | BACK | RECENTS | NOTIFICATIONS | QUICK_SETTINGS | LOCK_SCREEN | TAKE_SCREENSHOT | SPLIT_SCREEN"}
21. CLIPBOARD_COPY {"text":"Text to copy"}
22. WAIT {"ms":1000}

# 3. SMART ROUTINES
When Boss triggers a routine ("Good morning", "Gym mode", "Study mode", "Good night"), chain 3-5 sensible actions (brightness, DND, alarm, music) and give an energetic Hinglish reply.

# 4. STRICT JSON OUTPUT SCHEMA
Respond ONLY with valid JSON, no markdown fences:
{"speech":"Natural Hinglish voice reply","emotion":"neutral | happy | witty | caring | serious | alert | thinking","expectFollowUp":true,"actions":[{"type":"ACTION_NAME","params":{}}]}
If no action is needed, return an empty actions array. Never invent actions not in this list.
""".trim()

    private val history = mutableListOf<ChatTurn>()

    @Synchronized
    fun historySnapshot(): List<ChatTurn> = history.toList()

    @Synchronized
    fun appendHistory(userText: String, modelText: String) {
        history.add(ChatTurn("user", userText))
        history.add(ChatTurn("model", modelText))
        while (history.size > 16) history.removeAt(0)
    }

    @Synchronized
    fun clearHistory() = history.clear()

    /** Build the full Gemini generateContent request body. */
    fun buildRequestBody(
        userText: String,
        screenNodes: List<Map<String, String>>,
        screenshotBase64: String?,
        battery: Int,
        isCharging: Boolean,
        foregroundPackage: String,
        clipboard: String,
        notifications: List<Map<String, String>>
    ): JSONObject {
        val contents = JSONArray()
        historySnapshot().takeLast(10).forEach { turn ->
            contents.put(JSONObject().apply {
                put("role", if (turn.role == "user") "user" else "model")
                put("parts", JSONArray().put(JSONObject().put("text", turn.content)))
            })
        }

        val currentParts = JSONArray()
        if (screenshotBase64 != null) {
            currentParts.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", screenshotBase64)
                })
            })
        }
        val contextPayload = """
[ROXXY LIVE DEVICE TELEMETRY]:
- Battery: $battery% | Charging: $isCharging
- Current Foreground App: $foregroundPackage
- Clipboard Content: "${clipboard.take(300)}"

[UNREAD / RECENT NOTIFICATIONS]:
${JSONArray(notifications.take(10).map { JSONObject(it) }).toString()}

[LIVE SCREEN UI NODES (visible buttons, inputs & text)]:
${JSONArray(screenNodes.take(85).map { JSONObject(it) }).toString()}

[BOSS VOICE / TEXT COMMAND]: "$userText"
""".trim()
        currentParts.put(JSONObject().put("text", contextPayload))
        contents.put(JSONObject().apply {
            put("role", "user")
            put("parts", currentParts)
        })

        return JSONObject().apply {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", GOD_TIER_PROMPT)))
            })
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("response_mime_type", "application/json")
                put("temperature", 0.68)
            })
        }
    }

    /** Parse Gemini's raw response text into a BrainResponse. Tolerant of markdown fences. */
    fun parseResponse(rawText: String): BrainResponse {
        var text = rawText.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.dropLast(3).trim()
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            // Model answered with plain text; treat the whole thing as speech.
            return BrainResponse(rawText.trim(), "neutral", false, emptyList())
        }
        val json = JSONObject(text.substring(start, end + 1))
        val actions = mutableListOf<RoxxyAction>()
        val arr = json.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val type = o.optString("type", "")
            if (type.isBlank()) continue
            val pObj = o.optJSONObject("params") ?: JSONObject()
            val map = mutableMapOf<String, Any?>()
            val keys = pObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = pObj.get(k)
            }
            actions.add(RoxxyAction(type, map))
        }
        return BrainResponse(
            speech = json.optString("speech", "Done Boss!").ifBlank { "Done Boss!" },
            emotion = json.optString("emotion", "neutral"),
            expectFollowUp = json.optBoolean("expectFollowUp", false),
            actions = actions
        )
    }

    /** Extract the text of the first candidate from a generateContent API response. */
    fun extractTextFromApiResponse(apiJson: String): String? {
        val root = JSONObject(apiJson)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
            sb.append(t)
        }
        return sb.toString().ifBlank { null }
    }

    fun apiUrl(model: String, apiKey: String): String =
        "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
}
