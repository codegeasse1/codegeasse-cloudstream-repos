package com.pimpbunny

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class PimpBunnyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(PimpBunnyProvider())
    }
}
