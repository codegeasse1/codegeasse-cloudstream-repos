package com.donghuaworld

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class DonghuaWorldPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DonghuaWorldProvider())
    }
}
