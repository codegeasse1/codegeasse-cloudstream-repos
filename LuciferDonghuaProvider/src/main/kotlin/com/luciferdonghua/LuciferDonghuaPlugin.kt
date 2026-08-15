package com.luciferdonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class LuciferDonghuaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(LuciferDonghuaProvider())
    }
}
