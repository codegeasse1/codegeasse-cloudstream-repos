package com.anikage

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AniKagePlugin: Plugin() {
    override fun load(context: Context) {
        // Registers the main provider class
        registerMainAPI(AniKageProvider())
    }
}
