package de.joyncloudstream

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JoynPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(JoynProvider())
    }
}
