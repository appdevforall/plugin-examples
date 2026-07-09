package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.ProtocolMessage

interface OutboundSink {
    fun send(message: ProtocolMessage)
}
