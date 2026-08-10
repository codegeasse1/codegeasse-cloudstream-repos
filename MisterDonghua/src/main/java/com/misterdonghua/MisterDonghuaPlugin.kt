package com.misterdonghua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class MisterDonghuaPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MisterDonghuaProvider())
    }
}
