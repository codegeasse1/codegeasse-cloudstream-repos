package com.chigua

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class C51CGPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(C51CGProvider())
    }
}
