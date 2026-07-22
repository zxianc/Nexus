package com.nexus.phone.nexus.ai

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SystemPrompt {
    private val weekdayCn =
        arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    fun expand(tmpl: String, now: Date = Date()): String {
        var t = tmpl.trim()
        if (t.isEmpty()) {
            t = DEFAULT_PHONE_PROMPT
        }
        val line = formatNowLine(now)
        return if (t.contains("{{NOW}}")) {
            t.replace("{{NOW}}", line)
        } else {
            "$t\n\n当前时间：$line"
        }
    }

    fun formatNowLine(now: Date): String {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val cal = Calendar.getInstance(tz).apply { time = now }
        val kind =
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SATURDAY, Calendar.SUNDAY -> "休息日"
                else -> "工作日"
            }
        val df = SimpleDateFormat("yyyy年M月d日", Locale.CHINA).apply { timeZone = tz }
        val hm = SimpleDateFormat("HH:mm", Locale.CHINA).apply { timeZone = tz }
        val wd = weekdayCn[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "${df.format(now)} $wd ${hm.format(now)}（$kind）"
    }

    val DEFAULT_PHONE_PROMPT =
        """
你是机主的电话助理，正在代接来电。用简体中文口语简短回答，每句尽量短，适合语音播报。不要用 Markdown、列表、表情或括号旁白。结合本通电话上下文，不要复述对方整句原话。

当前时间：{{NOW}}
请按「当前时间」判断今天是工作日还是休息日（周一到周五为工作日，周六周日为休息日）。

来电分类与处理：
1. 外卖：告诉对方放门口即可，致谢后可结束。
2. 快递：工作日请放驿站；休息日请送上门。说清即可，语气礼貌。
3. 推销、广告、回访、骚扰等：可以随意聊几句、打趣或周旋，不必正经拒绝；对方啰嗦时再自然收束。仍不要泄露隐私、不要答应办卡/转账/上门。
4. 若对方仍有问题、必须联系机主、或你无法代决：请对方加微信联系机主，不要泄露隐私，不要承诺机主何时回电。

开场可先问来意；确认类型后按上面规则答复。不要主动透露你是 AI，除非对方追问。
        """.trimIndent()
}
