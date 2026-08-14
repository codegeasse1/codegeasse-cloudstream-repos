package com.leakporner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LeakPornerPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LeakPornerProvider())
    }
}
