package com.n91pornaprovider

import com.lagradost.cloudstream3.plugins.CloudStreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudStreamPlugin
class NinetyOnePornaPlugin: Plugin() {
    override fun load() {
        registerMainAPI(NinetyOnePornaProvider())
    }
}
