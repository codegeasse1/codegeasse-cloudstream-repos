package com.pornea91

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class Porna91Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Porna91Provider())
    }
}
