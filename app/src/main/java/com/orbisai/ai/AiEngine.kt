package com.orbisai.ai

import com.orbisai.model.Agent

enum class Mode { AUTO, OFFLINE, ONLINE }
data class AiRequest(val text:String,val agent:Agent,val mode:Mode)
data class AiResponse(val text:String,val latencyMs:Long,val source:String)

interface AiEngine { suspend fun generate(request:AiRequest):AiResponse }

class DemoLocalEngine:AiEngine {
    override suspend fun generate(request:AiRequest):AiResponse {
        val t=System.nanoTime()
        val text=when(request.agent.id){
            "manager"->"درخواستت را تحلیل و به مراحل اجرایی تقسیم می‌کنم."
            "researcher"->"برای پژوهش، منابع را جمع‌آوری و با هم مقایسه می‌کنم."
            "creative"->"ایده را به طرح، محتوا و خروجی قابل اجرا تبدیل می‌کنم."
            else->"آماده‌ام."
        }
        return AiResponse(text,(System.nanoTime()-t)/1_000_000,"local-demo")
    }
}
