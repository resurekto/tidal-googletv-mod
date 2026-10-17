package com.aspiro.wamp.tidalconnect.discovery

import android.content.Context
import m32

/**
 * Replaces TcBroadcastProviderFactory: TIDAL Connect is always available (stock: `!isTv`).
 * The constructor must keep its signature; the DI factory calls it.
 */
class TcBroadcastProviderFactory(
    @Suppress("unused") private val context: Context,
    private val broadcastProvider: TcBroadcastProvider,
) : m32 {

    private val listeners = ArrayList<m32.a>()

    override fun isAvailable(): Boolean = true

    override fun get(): TcBroadcastProvider = broadcastProvider

    override fun addListener(listener: m32.a) {
        listeners.add(listener)
    }

    fun removeListener(listener: m32.a) {
        listeners.remove(listener)
    }

    override fun refreshAvailability() {
        for (listener in listeners) listener.a(this)
    }
}
