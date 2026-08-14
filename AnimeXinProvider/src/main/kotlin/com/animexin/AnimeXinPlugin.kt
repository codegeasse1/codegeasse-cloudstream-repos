package com.animexin

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeXinPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeXinProvider())
    }
}
