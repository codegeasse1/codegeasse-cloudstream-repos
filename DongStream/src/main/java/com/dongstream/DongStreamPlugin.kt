package com.dongstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DongStreamPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the provider here
        registerMainAPI(DongStreamProvider())
    }
}
