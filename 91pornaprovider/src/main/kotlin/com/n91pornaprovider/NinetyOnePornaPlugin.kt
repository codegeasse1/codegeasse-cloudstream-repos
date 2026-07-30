package com.n91pornaprovider

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NinetyOnePornaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NinetyOnePornaProvider())
    }
}