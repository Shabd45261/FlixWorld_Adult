package com.flixworld.adult

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * Flix World — adult extension.
 *
 * Registers ONE provider ("FlixWorld Adult") that carries both sources:
 * OK.XXX (videos) and FikFap (Shorts).
 */
@CloudstreamPlugin
class FlixWorldAdultPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(FlixWorldAdult())
    }
}
