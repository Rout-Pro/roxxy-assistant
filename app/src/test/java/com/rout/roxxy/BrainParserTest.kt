package com.rout.roxxy

import com.rout.roxxy.core.Brain
import com.rout.roxxy.core.LocalRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainParserTest {

    @Test
    fun parsesCleanJsonWithActions() {
        val raw = """{"speech":"Done Boss!","emotion":"happy","expectFollowUp":true,"actions":[{"type":"OPEN_APP","params":{"appName":"whatsapp"}},{"type":"WAIT","params":{"ms":500}}]}"""
        val r = Brain.parseResponse(raw)
        assertEquals("Done Boss!", r.speech)
        assertEquals("happy", r.emotion)
        assertTrue(r.expectFollowUp)
        assertEquals(2, r.actions.size)
        assertEquals("OPEN_APP", r.actions[0].type)
        assertEquals("whatsapp", r.actions[0].params["appName"])
        assertEquals(500, (r.actions[1].params["ms"] as Number).toInt())
    }

    @Test
    fun parsesMarkdownFencedJson() {
        val raw = "```json\n{\"speech\":\"Haan Boss\",\"emotion\":\"witty\",\"actions\":[]}\n```"
        val r = Brain.parseResponse(raw)
        assertEquals("Haan Boss", r.speech)
        assertEquals("witty", r.emotion)
        assertTrue(r.actions.isEmpty())
    }

    @Test
    fun fallsBackToPlainTextWhenNoJson() {
        val r = Brain.parseResponse("Boss ye ek normal jawab hai")
        assertEquals("Boss ye ek normal jawab hai", r.speech)
        assertTrue(r.actions.isEmpty())
    }

    @Test
    fun toleratesMissingFields() {
        val r = Brain.parseResponse("""{"actions":[{"type":"SCROLL","params":{"direction":"DOWN"}}]}""")
        assertEquals("Done Boss!", r.speech)
        assertEquals("neutral", r.emotion)
        assertEquals(1, r.actions.size)
        assertEquals("DOWN", r.actions[0].params["direction"])
    }

    @Test
    fun extractsTextFromApiShape() {
        val api = """{"candidates":[{"content":{"parts":[{"text":"hello "},{"text":"boss"}],"role":"model"}}]}"""
        assertEquals("hello boss", Brain.extractTextFromApiResponse(api))
        assertNull(Brain.extractTextFromApiResponse("""{"candidates":[]}"""))
    }

    @Test
    fun buildsRequestWithSystemPromptAndTelemetry() {
        val body = Brain.buildRequestBody(
            userText = "time kya hai",
            screenNodes = listOf(mapOf("text" to "Send", "x" to "100", "y" to "200")),
            screenshotBase64 = null,
            battery = 55, isCharging = false,
            foregroundPackage = "com.whatsapp",
            clipboard = "clip",
            notifications = listOf(mapOf("app" to "com.whatsapp", "sender" to "Aman", "message" to "hi"))
        ).toString()
        assertTrue(body.contains("ROXXY"))
        assertTrue(body.contains("time kya hai"))
        assertTrue(body.contains("com.whatsapp"))
        assertTrue(body.contains("application/json"))
    }

    @Test
    fun localRouterHandlesKeylessCommands() {
        assertNotNull(LocalRouter.route("time kya hai Boss", 80))
        assertNotNull(LocalRouter.route("aaj ki date batao", 80))
        assertNotNull(LocalRouter.route("battery kitni hai", 80))
        val torch = LocalRouter.route("torch on karo", 80)
        assertNotNull(torch)
        assertEquals("HARDWARE_TOGGLE", torch!!.actions[0].type)
        val open = LocalRouter.route("whatsapp kholo", 80)
        assertNotNull(open)
        assertEquals("OPEN_APP", open!!.actions[0].type)
        assertEquals("com.whatsapp", open.actions[0].params["package"])
        assertNull(LocalRouter.route("mujhe ek lamba joke sunao", 80))
    }

    @Test
    fun localRouterBatterySaysPercent() {
        val r = LocalRouter.route("battery check karo", 42)
        assertTrue(r!!.speech.contains("42"))
    }
}
