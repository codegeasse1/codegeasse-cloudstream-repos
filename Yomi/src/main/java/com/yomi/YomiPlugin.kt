package com.yomi

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class YomiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(YomiProvider())
    }
}
