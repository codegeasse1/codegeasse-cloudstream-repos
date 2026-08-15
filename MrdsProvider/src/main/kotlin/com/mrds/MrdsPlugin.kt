package com.mrds

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class MrdsPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MrdsProvider())
    }
}
