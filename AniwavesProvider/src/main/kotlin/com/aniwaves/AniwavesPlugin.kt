package com.aniwaves

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AniwavesPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AniwavesProvider())
    }
}
