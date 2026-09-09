package com.orbisai.model

data class Agent(val id:String,val name:String,val role:String,val emoji:String,val voiceId:String,val prompt:String)
object Agents {
    val manager=Agent("manager","آریا","مدیر و هماهنگ‌کننده","👨‍💼","manager","برنامه‌ریزی، تصمیم‌گیری و هماهنگی")
    val researcher=Agent("researcher","نورا","پژوهشگر و تحلیل‌گر","👩‍🔬","researcher","تحقیق، مقایسه و تحلیل منابع")
    val creative=Agent("creative","لیا","خلاق و طراح","👩‍🎨","creative","ایده‌پردازی، طراحی و تولید")
    val all=listOf(manager,researcher,creative)
}
