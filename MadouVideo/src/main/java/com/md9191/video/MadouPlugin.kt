package com.md9191.video

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MadouPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MadouProvider())
    }
}
