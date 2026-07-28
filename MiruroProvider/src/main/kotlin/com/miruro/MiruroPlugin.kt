package com.miruro

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class MiruroPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MiruroProvider())
    }
}
