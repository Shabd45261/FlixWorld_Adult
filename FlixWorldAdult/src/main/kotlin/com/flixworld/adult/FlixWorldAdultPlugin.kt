package com.flixworld.adult

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Flix World — adult extension.
 *
 * Both sources ship in this ONE extension, mirroring the Flix World app where
 * the adult profile carries OK.XXX as the main source and FikFap as the Shorts
 * feed.
 */
@CloudstreamPlugin
class FlixWorldAdultPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(OkxxxProvider())
        registerMainAPI(FikfapShortsProvider())
    }
}
