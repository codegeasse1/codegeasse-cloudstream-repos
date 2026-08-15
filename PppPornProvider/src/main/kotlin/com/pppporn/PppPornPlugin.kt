package com.pppporn

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class PppPornPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PppPornProvider())
    }
}
