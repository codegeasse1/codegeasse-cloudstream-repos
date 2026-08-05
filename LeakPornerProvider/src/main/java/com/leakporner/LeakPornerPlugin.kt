package com.leakporner

import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ProviderType

@CloudStreamPlugin
class LeakPornerPlugin : Plugin {
    override fun loadPlugin() {
        // Register the main provider
        registerMainAPI(LeakPornerProvider())
        // If you later add extractors, register them here
    }
}
