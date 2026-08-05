package com.leakporner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LeakPornerPlugin : Plugin() {
    override fun load(context: Context) {
        // Registers the provider so CloudStream knows it exists
        registerMainAPI(LeakPornerProvider())
    }
}
