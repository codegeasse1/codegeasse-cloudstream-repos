package com.chiki2d

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ChikiAni2dPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ChikiAni2dProvider())
    }
}
