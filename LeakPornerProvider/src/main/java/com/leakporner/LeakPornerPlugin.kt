package com.leakporner

import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudStreamPlugin
class LeakPornerPlugin : Plugin {
    override fun loadPlugin() {
        registerMainAPI(LeakPornerProvider())
    }
}
