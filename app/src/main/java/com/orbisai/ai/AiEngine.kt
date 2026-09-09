package com.orbisai.ai

import com.orbisai.model.Agent

enum class Mode { AUTO, OFFLINE, ONLINE }
data class AiRequest(val text:String,val agent:Agent,val mode:Mode)
data class AiResponse(val text:String,val latencyMs:Long,val source:String)

interface AiEngine { suspend fun generate(request:AiRequest):AiResponse }

/** Deterministic local fallback: useful before a real local/cloud model is configured. */
class DemoLocalEngine:AiEngine {
 override suspend fun generate(request:AiRequest):AiResponse {
  val t=System.nanoTime(); val q=request.text.trim(); val lower=q.lowercase()
  val text=when {
   lower.contains("سلام")||lower.contains("درود") -> "سلام! من ${request.agent.name} هستم. آماده‌ام همین‌جا شروع کنیم. چه کاری را می‌خواهی انجام بدهم؟"
   lower.contains("کمک")||lower.contains("چه کار") -> when(request.agent.id){
    "manager"->"می‌توانم کارت را به برنامه و مرحله‌های اجرایی تبدیل کنم، اولویت بدهم و کارها را بین ایجنت‌ها تقسیم کنم."
    "researcher"->"می‌توانم موضوع را به پرسش‌های پژوهشی تبدیل کنم، منابع را مقایسه کنم و نتیجه را خلاصه و منظم تحویل بدهم."
    else->"می‌توانم برای ایده، طراحی، نام‌گذاری، محتوا و ساخت یک خروجی خلاقانه با تو جلو بروم."
   }
   lower.contains("ایده") -> "ایده‌ات را بگو؛ آن را به چند مسیر متفاوت تبدیل می‌کنم و برای هر مسیر مزیت، ریسک و قدم بعدی می‌دهم."
   lower.contains("برنامه")||lower.contains("پروژه") -> "پروژه را به هدف، خروجی، کارهای اصلی، اولویت‌ها و قدم بعدی تقسیم می‌کنیم. اگر بخواهی، از همین پیام شروع می‌کنم."
   lower.contains("تحقیق")||lower.contains("بررسی")||lower.contains("تحلیل") -> "موضوع را از چند زاویه بررسی می‌کنم، نکات کلیدی را جدا می‌کنم و نتیجه را به زبان ساده و قابل اجرا جمع‌بندی می‌کنم."
   else -> when(request.agent.id){
    "manager"->"متوجه شدم. ${request.agent.name} این درخواست را به یک اقدام مشخص تبدیل می‌کند و قدم بعدی را پیشنهاد می‌دهد."
    "researcher"->"متوجه شدم. ${request.agent.name} موضوع را ساختاربندی می‌کند تا بتوانیم دقیق‌تر بررسی و مقایسه کنیم."
    "creative"->"متوجه شدم. ${request.agent.name} درخواست را به یک ایده و مسیر اجرایی خلاقانه تبدیل می‌کند."
    else->"آماده‌ام."
   }
  }
  return AiResponse(text,(System.nanoTime()-t)/1_000_000,"local-fallback")
 }
}
