package com.wisp.app.relay

data class PublishResult(val relayUrl: String, val eventId: String, val accepted: Boolean, val message: String)
