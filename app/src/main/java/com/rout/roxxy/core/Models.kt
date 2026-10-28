package com.rout.roxxy.core

data class RoxxyAction(
    val type: String,
    val params: Map<String, Any?> = emptyMap()
)

data class BrainResponse(
    val speech: String,
    val emotion: String,
    val expectFollowUp: Boolean,
    val actions: List<RoxxyAction>
)

data class ChatTurn(val role: String, val content: String)
