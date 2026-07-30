package com.animex

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeXPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeXProvider())
    }
}
