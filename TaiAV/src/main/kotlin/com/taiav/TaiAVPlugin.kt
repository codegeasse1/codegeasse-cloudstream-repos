package com.taiav

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TaiAVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TaiAVProvider())
    }
}
