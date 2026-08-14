package com.chikianimation

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ChikiAnimationPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ChikiAnimationProvider())
    }
}
