package com.homeworkassistant.data.mcp

import com.homeworkassistant.data.web.TrustedOriginPolicy
import com.homeworkassistant.domain.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter

data class McpCourse(val courseId: String, val classId: String, val cpi: String, val name: String,
    val lifecycle: CourseLifecycle = CourseLifecycle.UNKNOWN, val rawLifecycle: String = "") {
    val domain get() = Course("$courseId:$classId", name)
}
data class McpWorkPage(val items: List<Homework>, val next: List<String>, val complete: Boolean)
data class McpCoursePage(val courses: List<McpCourse>, val next: List<String>, val recognized: Boolean, val unresolved: Int = 0)

/** Port of xuexitong_mcp.client's course/work/exam readers (MIT); no Python process or remote MCP. */
class McpChaoxingParser {
    private val policy = TrustedOriginPolicy()
    private val zone = ZoneId.of("Asia/Shanghai")

    fun courses(page: OfficialHtml): McpCoursePage {
        val doc = Jsoup.parse(page.html, page.url)
        val cards = linkedSetOf<Element>()
        cards.addAll(doc.select("div.course"))
        doc.select("a[href*=stucoursemiddle]").forEach { link ->
            cards.add(link.closest("div.course") ?: link.closest("li") ?: link.parent() ?: link)
        }
        var unresolved = 0
        val parsed = cards.mapNotNull { card ->
            val url = card.select("a[href*=stucoursemiddle]").firstOrNull()?.attr("href")
                ?.let { officialLink(it, page.url) }?.toHttpUrlOrNull()
            fun id(selectors: String, query: String): String? = card.selectFirst(selectors)?.attr("value")
                ?.takeIf { it.matches(Regex("\\d+")) } ?: url?.queryParameterNames
                    ?.firstOrNull { it.equals(query, true) }?.let { url.queryParameter(it) }?.takeIf { it.matches(Regex("\\d+")) }
            val cid = id("input.courseId, input[name=courseId]", "courseid")
            val clazz = id("input.clazzId, input.classId, input[name=clazzId]", "clazzid")
            val cpi = id("input.cpi, input.curPersonId, input[name=cpi]", "cpi")
            if (cid == null || clazz == null) { unresolved++; return@mapNotNull null }
            val nameNode = card.selectFirst(".course-name")
            val name = nameNode?.attr("title")?.ifBlank { nameNode.text() }?.trim()?.ifBlank { "未命名课程" } ?: "未命名课程"
            val labels = card.select(".not-open-tip, .course-status, .course-state").filter { node ->
                (sequenceOf(node) + node.parents().asSequence()).none { it.hasAttr("hidden") || Regex("display\\s*:\\s*none|visibility\\s*:\\s*hidden", RegexOption.IGNORE_CASE).containsMatchIn(it.attr("style")) }
            }.map { it.text().replace(Regex("\\s+"), "") }.filter(String::isNotBlank)
            val states = labels.map(::courseLifecycle).distinct()
            val state = when {
                states.isEmpty() -> CourseLifecycle.ACTIVE // Known official card without a blocking badge.
                states.size == 1 -> states.single()
                else -> CourseLifecycle.UNKNOWN
            }
            McpCourse(cid, clazz, cpi.orEmpty(), name,
                if (state == CourseLifecycle.ACTIVE && cpi == null) CourseLifecycle.UNKNOWN else state,
                labels.joinToString(" / "))
        }
        val courses = parsed.groupBy { it.domain.id }.values.map { same ->
            same.first().copy(lifecycle = if (same.map { it.lifecycle }.distinct().size == 1) same.first().lifecycle else CourseLifecycle.UNKNOWN)
        }
        val empty = Regex("暂无课程|没有课程|还没有课程").containsMatchIn(doc.text())
        return McpCoursePage(courses, next(doc, page.url), cards.isNotEmpty() || empty, unresolved)
    }

    fun courseLifecycle(label: String): CourseLifecycle = when {
        Regex("已结课|已結課|课程已结束|課程已結束|^已结束$").containsMatchIn(label) -> CourseLifecycle.ENDED
        Regex("未开放|未開放|未开课|未開課|尚未开课|课程未开始").containsMatchIn(label) -> CourseLifecycle.UNAVAILABLE
        label in setOf("进行中", "進行中", "开课中", "未结课", "未結課", "课程进行中") -> CourseLifecycle.ACTIVE
        else -> CourseLifecycle.UNKNOWN
    }

    fun schoolId(page: OfficialHtml): String? =
        Regex("[\"']fid[\"']\\s*:\\s*[\"']?(\\d+)").find(page.html)?.groupValues?.get(1)
            ?: Regex("var\\s+fid\\s*=\\s*['\"](\\d+)['\"]").find(page.html)?.groupValues?.get(1)
            ?: Jsoup.parse(page.html).selectFirst("input#fid")?.attr("value")?.takeIf { it.matches(Regex("\\d+")) }

    fun workListUrl(course: McpCourse, middle: OfficialHtml, now: Instant): String {
        val doc = Jsoup.parse(middle.html)
        fun hidden(id: String) = doc.getElementById(id)?.attr("value").orEmpty()
        val workEnc = hidden("workEnc")
        if (workEnc.isBlank()) throw IOException("课程页未下发 workEnc，作业读取未完成，原缓存保留")
        return WORK_LIST.toHttpUrl().newBuilder().addQueryParameter("courseId", course.courseId)
            .addQueryParameter("classId", course.classId).addQueryParameter("cpi", course.cpi)
            .addQueryParameter("ut", "s").addQueryParameter("openc", hidden("openc"))
            .addQueryParameter("enc", workEnc).addQueryParameter("stuenc", hidden("enc"))
            .addQueryParameter("t", now.toEpochMilli().toString()).addQueryParameter("isdisplaytable", "2").build().toString()
    }

    fun homework(page: OfficialHtml, course: McpCourse): McpWorkPage {
        val doc = Jsoup.parse(page.html, page.url)
        val rows = doc.select("li[onclick*=goTask], li[data][class*=work], li[data-workid]")
        val progress = Regex("(\\d+)\\s*/\\s*(\\d+)").find(doc.select("span").joinToString(" ") { it.text() })
        val total = progress?.groupValues?.get(2)?.toIntOrNull()
        val items = rows.map { row ->
            val title = row.selectFirst("p.overHidden2, .work-title, h3")?.text()?.trim().orEmpty()
            if (title.isBlank()) throw IOException("官方作业行缺少标题，停止将其当作空列表")
            val raw = row.selectFirst("p.status, .status")?.text()?.trim().orEmpty()
            val target = officialLink(row.attr("data").ifBlank { row.attr("data-url") }
                .ifBlank { row.selectFirst("a[href]")?.attr("href").orEmpty() }, page.url)
                ?: throw IOException("作业缺少可验证的官方详情地址，原缓存保留")
            val parsed = target.toHttpUrl()
            val workId = parsed.queryParameterNames.firstOrNull { it.equals("workid", true) || it.equals("taskrefid", true) }
                ?.let(parsed::queryParameter)?.takeIf(String::isNotBlank)
                ?: row.attr("data-workid").takeIf(String::isNotBlank)
                ?: throw IOException("官方作业行缺少稳定标识，原缓存保留")
            val dates = HomeworkDates.read(row)
            Homework("${course.domain.id}:$workId", course.domain, title, target,
                publishedAt = dates.publishedAt, dueAt = dates.dueAt, dueText = dates.dueText,
                status = status(raw), rawStatus = raw)
        }
        if (items.map { it.id }.distinct().size != items.size) throw IOException("官方作业列表标识重复")
        val links = next(doc, page.url)
        val explicitEmpty = Regex("暂无作业|没有作业|还没有作业").containsMatchIn(doc.text())
        val explicitEnd = doc.select(".pagination .disabled.next, .page .disabled.next, [data-has-next=false]").isNotEmpty()
        val complete = links.isEmpty() && ((total != null && total == items.size) || explicitEmpty || explicitEnd)
        if (items.isEmpty() && !(explicitEmpty || total == 0)) throw IOException("官方响应未识别为作业列表，未覆盖原缓存")
        return McpWorkPage(items, links, complete)
    }

    /** The MCP treats 已完成 / 已提交 / 待批阅 / 已批阅 / 已批改 as done. Empty text remains unknown. */
    fun status(raw: String): HomeworkStatus = when {
        raw.isBlank() -> HomeworkStatus.UNKNOWN
        Regex("退回|打回|重做").containsMatchIn(raw) -> HomeworkStatus.RETURNED
        Regex("已批阅|已批改").containsMatchIn(raw) -> HomeworkStatus.GRADED
        Regex("已完成|已提交|待批阅").containsMatchIn(raw) -> HomeworkStatus.SUBMITTED
        else -> HomeworkStatus.NOT_SUBMITTED
    }

    fun details(item: Homework, page: OfficialHtml): Homework {
        val doc = Jsoup.parse(page.html)
        val text = doc.text()
        val dates = HomeworkDates.read(doc)
        val score = Regex("(?:最终成绩|得分|成绩|分数)[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*分").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val maxScore = Regex("满分[:：]\\s*(\\d+(?:\\.\\d+)?)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return item.copy(dueAt = dates.dueAt, dueText = dates.dueText, publishedAt = dates.publishedAt,
            score = score, maxScore = maxScore,
            status = if (score != null && item.status.isCompleted) HomeworkStatus.GRADED else item.status)
    }

    /** Follow only observed, passive official documents; never infer a start/submit endpoint. */
    fun detailDocuments(item: Homework, page: OfficialHtml): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        doc.select(".TiMu,.singleQuesId,.question-item,[data-question-id],[data-questionid]").remove()
        val frames = doc.select("iframe[src],frame[src]").map { it.attr("src") }
        val refresh = doc.select("meta[http-equiv=refresh]").mapNotNull {
            Regex("^\\s*\\d+(?:\\.\\d+)?\\s*;\\s*url\\s*=\\s*(.+)$", RegexOption.IGNORE_CASE)
                .matchEntire(it.attr("content"))?.groupValues?.get(1)?.trim()?.trim('\'', '"')
        }
        return (frames + refresh).mapNotNull { officialLink(it, page.url)?.toHttpUrlOrNull() }.filter { url ->
            val path = url.encodedPath.lowercase()
            val workId = item.id.substringAfterLast(':')
            val ids = url.queryParameterNames.filter { it.equals("workid", true) || it.equals("taskrefid", true) }
                .mapNotNull(url::queryParameter)
            (path.contains("/work/") || path.contains("/workhandle/")) &&
                !Regex("submit|handin|delete|remove|save|start|begin", RegexOption.IGNORE_CASE).containsMatchIn(url.toString()) &&
                ids.all { it == workId } &&
                url.queryParameterNames.filter { it.equals("courseid", true) }.all { url.queryParameter(it) == item.course.id.substringBefore(':') } &&
                url.queryParameterNames.filter { it.equals("classid", true) || it.equals("clazzid", true) }.all { url.queryParameter(it) == item.course.id.substringAfter(':') }
        }.map { it.newBuilder().fragment(null).build().toString() }.filter { it != page.url }.distinct()
    }

    fun exams(page: OfficialHtml): Pair<List<Exam>, List<String>> {
        val doc = Jsoup.parse(page.html, page.url)
        val titleLabels = setOf("考试名称", "考试标题", "考试名")
        val timeLabels = setOf("考试时间", "考试起止时间", "起止时间")
        val emptyMarker = Regex("暂无考试|没有考试|暂无数据|无考试安排")
        fun headers(row: org.jsoup.nodes.Element): List<String> = row.select("th, td").map { it.text().replace(Regex("\\s+"), "") }
        fun tableHeaders(row: org.jsoup.nodes.Element): List<String>? = row.closest("table")?.select("tr")
            ?.map(::headers)?.firstOrNull { names -> names.any { it in titleLabels } && names.any { it in timeLabels } }
        val rows = doc.select("tr").filter { row ->
            row.select("td").isNotEmpty() && !(row.select("td").size == 1 && emptyMarker.matches(row.text().trim())) && (row.hasClass("dataTr") ||
                (tableHeaders(row) != null && headers(row).none { it in titleLabels }))
        }
        val items = rows.map { row ->
            val cells = row.select("td").map { it.text().trim() }
            val names = tableHeaders(row)
            if (names == null && cells.size < 9) throw IOException("考试表格结构已变化，原缓存保留")
            fun cell(legacyIndex: Int, vararg labels: String): String = if (names == null) cells.getOrNull(legacyIndex).orEmpty()
                else cells.getOrNull(names.indexOfFirst { it in labels }).orEmpty()
            val title = cell(1, *titleLabels.toTypedArray())
            val time = cell(2, *timeLabels.toTypedArray())
            val duration = cell(3, "考试时长", "时长", "考试限时")
            val schedule = cell(4, "考试状态", "安排状态")
            val answer = cell(5, "答卷状态", "作答状态", "提交状态")
            val score = cell(6, "得分", "成绩", "考试成绩", "分数")
            val mode = cell(7, "考试方式", "考试模式", "方式")
            if (title.isBlank() || time.isBlank()) throw IOException("考试表格结构已变化，原缓存保留")
            val dates = Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}\\s+\\d{1,2}:\\d{2}").findAll(time).map { date(it.value) }.toList()
            val key = row.attr("data-id").ifBlank { row.id() }.ifBlank { digest(title + "|" + time + "|" + mode) }
            val links = examLinks(row, page.url)
            fun identity(attr: String, vararg keys: String): String? {
                val values = (listOf(row.attr(attr), if (attr == "data-classid") row.attr("data-clazzid") else "") + row.select("input").filter { it.attr("name") in keys || it.id() in keys }
                    .map { it.attr("value") } + links.flatMap { link -> link.queryParameterNames.filter { key -> keys.any { it.equals(key, true) } }.mapNotNull { link.queryParameter(it) } })
                    .filter { it.matches(Regex("\\d+")) }.distinct()
                // Empty sentinel means conflicting IDs; never fall back to matching a course name.
                return if (values.size > 1) "" else values.singleOrNull()
            }
            val courseId = identity("data-courseid", "courseid", "courseId")
            val classId = identity("data-classid", "clazzid", "clazzId", "classId", "classid")
            val courseName = row.attr("data-course-name").ifBlank { row.selectFirst(".course-name")?.text().orEmpty() }
                .ifBlank { if (names != null) cell(-1, "课程名称", "所属课程", "课程") else "" }.trim().takeIf(String::isNotBlank)
            Exam(key, title, time, dates.getOrNull(0), dates.getOrNull(1), duration, schedule, answer, score, mode,
                if (answer.isBlank()) HomeworkStatus.UNKNOWN else when {
                    Regex("未考|未交|未提交|未完成|未作答|作答中|考试中").containsMatchIn(answer) -> HomeworkStatus.NOT_SUBMITTED
                    Regex("已交|已考|已完成|已作答|已提交|待批阅|已批阅|已批改").containsMatchIn(answer) -> status(answer.replace("已交卷", "已提交").replace("已考", "已完成").replace("已作答", "已完成"))
                    else -> HomeworkStatus.UNKNOWN
                }, courseId, classId, courseName, links.firstOrNull()?.toString())
        }
        val empty = emptyMarker.containsMatchIn(doc.text())
        if (items.isEmpty() && !empty) throw IOException("官方响应未识别为考试列表，原缓存保留")
        if (items.map { it.id }.distinct().size != items.size) throw IOException("考试列表标识重复，原缓存保留")
        return items to next(doc, page.url)
    }

    private fun date(value: String): Instant? = runCatching {
        LocalDateTime.parse(value.trim().replace('/', '-').replace(Regex("\\s+"), " "),
            DateTimeFormatter.ofPattern("uuuu-M-d H:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)).atZone(zone).toInstant()
    }.getOrNull()

    /** Observe literal official links only; never execute onclick handlers or guess signed exam URLs. */
    private fun examLinks(row: Element, base: String): List<okhttp3.HttpUrl> {
        val nodes = sequenceOf(row) + row.select("a, button, [data-url], [data-href], [onclick]").asSequence()
        return nodes.flatMap { node ->
            val direct = listOf(node.attr("href"), node.attr("data-url"), node.attr("data-href"))
            val literals = Regex("['\"]((?:https?://|//|/)[^'\"\\r\\n]+)['\"]").findAll(node.attr("onclick"))
                .filter { node.attr("onclick").substring(it.range.last + 1).trimStart().firstOrNull() != '+' }
                .map { it.groupValues[1] }.toList()
            (direct + literals).asSequence()
        }.mapNotNull { raw -> officialLink(raw, base)?.toHttpUrlOrNull() }
            .filter { url -> url.toString() != base && url.fragment == null &&
                !Regex("delete|remove|submit|handin", RegexOption.IGNORE_CASE).containsMatchIn(url.encodedPath) &&
                url.queryParameterNames.none { it.equals("action", true) && Regex("delete|remove|submit|handin", RegexOption.IGNORE_CASE).containsMatchIn(url.queryParameter(it).orEmpty()) } &&
                (url.encodedPath.contains("/exam", true) || url.encodedPath.contains("/paper", true)) }
            .distinct().sortedByDescending { url ->
                if (url.encodedPath.contains("/examlist", true)) 0 else 1
            }.toList()
    }

    private fun next(doc: Document, base: String): List<String> = doc.select("a[href], [data-url]").filter {
        (it.hasAttr("rel") && it.attr("rel").split(' ').contains("next")) || it.text().trim() in listOf("下一页", "下页", "Next", "›", "»")
    }.filterNot { it.hasClass("disabled") || it.attr("aria-disabled") == "true" || it.parent()?.hasClass("disabled") == true }
        .mapNotNull { officialLink(it.attr("href").ifBlank { it.attr("data-url") }, base) }.filter { it != base }.distinct()

    fun officialLink(raw: String, base: String): String? {
        if (raw.isBlank()) return null
        var address = raw.trim()
        if (Regex("^(https?%3[aA]|%2[fF])").containsMatchIn(address))
            address = runCatching { URLDecoder.decode(address, "UTF-8") }.getOrNull() ?: return null
        val resolved = base.toHttpUrlOrNull()?.resolve(address)?.toString() ?: return null
        val https = policy.upgradeToHttps(resolved) ?: resolved
        return https.takeIf(policy::allows)
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val BASE = "https://i.chaoxing.com/base"
        const val COURSE_LIST = "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata"
        const val COURSE_MIDDLE = "https://mooc1.chaoxing.com/visit/stucoursemiddle"
        const val WORK_LIST = "https://mooc1.chaoxing.com/mooc2/work/list"
        const val EXAM_LIST = "https://mooc1-api.chaoxing.com/exam-ans/exam/test/examcode/examlist"
    }
}
