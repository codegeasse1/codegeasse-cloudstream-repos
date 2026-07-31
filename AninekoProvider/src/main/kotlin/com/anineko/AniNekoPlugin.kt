package com.anineko

import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.registerMainAPI

@CloudStreamPlugin
class AniNekoPlugin: Plugin() {
    override fun load() {
        registerMainAPI(AniNekoProvider())
    }
}
