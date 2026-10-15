package com.flixworld.adult

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Flix World — adult extension.
 *
 * Both sources ship in this ONE extension, mirroring the Flix World app where
 * the adult profile carries OK.XXX as the main source and FikFap as the Shorts
 * feed.
 */
@CloudstreamPlugin
class FlixWorldAdultPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkxxxProvider())
        registerMainAPI(FikfapShortsProvider())
    }
}
