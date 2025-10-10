package com.auratail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AuratailProvider: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Auratail())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(Odnoklassniki())
        registerExtractorAPI(OkRuSSL())
        registerExtractorAPI(OkRuHTTP())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(svilla())
        registerExtractorAPI(svanila())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3())

    }
}
