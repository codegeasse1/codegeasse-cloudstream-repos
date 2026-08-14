package com.anime4i

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class Anime4iPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Anime4iProvider())
    }
}
