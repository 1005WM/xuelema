package com.homeworkassistant.data.mcp

import org.jsoup.nodes.Element
import java.time.*
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle

/** Keep month/day-only metadata visible without assigning a potentially wrong historical year. */
internal data class HomeworkDates(val publishedAt: Instant?, val dueAt: Instant?, val dueText: String?) {
    companion object {
        private val token = "(?:\\d{4}[-/年])?\\d{1,2}[-/月]\\d{1,2}日?\\s*[T ]?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?"
        private val range = Regex("(?:作答|起止|开放|有效)时间\\s*[:：]?\\s*($token)\\s*(?:至|到|~|～|—|–|-)\\s*($token)")
        private val deadline = Regex("(?:截止(?:时间|日期)?|结束时间|提交时间截止)\\s*[:：]?\\s*($token|不限|无限制|未设置|无截止时间)")
        private val formatter = DateTimeFormatterBuilder().appendPattern("uuuu-M-d H:mm")
            .optionalStart().appendPattern(":ss").optionalEnd().toFormatter().withResolverStyle(ResolverStyle.STRICT)
        private val zone = ZoneId.of("Asia/Shanghai")

        // Official task metadata can use full-width colons in the clock itself, not just labels.
        private fun normalize(value: String) = value.replace('：', ':').replace('\u00a0', ' ')
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            // Inline metadata labels and clock components may be separate HTML nodes.
            .replace(Regex("(?<=\\d)\\s*([:/月年-])\\s*(?=\\d)"), "$1")
            .replace(Regex("(作答|起止|开放|有效|截止|结束)\\s+(时间|日期)"), "$1$2")

        fun read(source: Element): HomeworkDates {
            val metadata = source.clone()
            // A date mentioned inside a question or a student's answer is not task metadata.
            metadata.select(".TiMu,.singleQuesId,.question-item,[data-question-id],[data-questionid],script,style,textarea").remove()
            val text = normalize(metadata.text())
            val times = range.find(text)
            val attributes = metadata.getAllElements().asSequence().flatMap { node ->
                sequenceOf("data-endtime", "data-end-time", "data-deadline").map { node.attr(it) }.filter(String::isNotBlank)
            }.toList() + metadata.select("input").filter {
                it.attr("name").lowercase() in setOf("endtime", "workendtime", "deadline", "enddate") ||
                    it.id().lowercase() in setOf("endtime", "workendtime", "deadline", "enddate")
            }.map { it.attr("value") }
            val raw = deadline.find(text)?.groupValues?.get(1) ?: times?.groupValues?.get(2)
            val due = sequenceOf(raw).plus(attributes.asSequence()).filterNotNull().map(::parse).firstOrNull { it.second != null }
            return HomeworkDates(times?.groupValues?.get(1)?.let(::parse)?.first, due?.first, due?.second)
        }

        private fun parse(raw: String): Pair<Instant?, String?> {
            if (raw in setOf("不限", "无限制", "未设置", "无截止时间")) return null to "未设置截止时间"
            val value = normalize(raw).trim().replace('年', '-').replace('月', '-').replace("日", " ")
                .replace('/', '-').replace('T', ' ').replace(Regex("\\s+"), " ")
            if (Regex("\\d{10}|\\d{13}").matches(value)) {
                val instant = runCatching { Instant.ofEpochMilli(value.toLong() * if (value.length == 10) 1000 else 1) }.getOrNull()
                    ?.takeIf { it.atZone(zone).year in 2000..2199 }
                return instant to instant?.atZone(zone)?.toLocalDateTime()?.toString()
            }
            if (Regex("\\d{4}-.*").matches(value)) {
                val instant = runCatching { LocalDateTime.parse(value, formatter).atZone(zone).toInstant() }.getOrNull()
                return instant to value.takeIf { instant != null }
            }
            val short = Regex("(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matchEntire(value)
                ?: return null to null
            val valid = runCatching {
                MonthDay.of(short.groupValues[1].toInt(), short.groupValues[2].toInt())
                LocalTime.of(short.groupValues[3].toInt(), short.groupValues[4].toInt(), short.groupValues[5].ifBlank { "0" }.toInt())
            }.isSuccess
            return null to value.takeIf { valid }?.let { "$it（年份待确认）" }
        }
    }
}
