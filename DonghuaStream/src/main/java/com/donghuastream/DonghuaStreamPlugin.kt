package com.donghuastream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class DonghuaStreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DonghuaStreamProvider())
    }
}
