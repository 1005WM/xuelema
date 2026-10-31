package com.homeworkassistant.data.web

import android.webkit.WebView
import com.google.gson.Gson
import com.homeworkassistant.data.mcp.McpChaoxingParser
import com.homeworkassistant.data.mcp.OfficialHtml
import com.homeworkassistant.domain.model.Homework
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

private data class RenderedMetadataDto(val url: String = "", val text: String = "", val fields: Map<String, String> = emptyMap())

/** Supplement HTTP metadata from the already opened task, including AJAX-rendered text. */
class RenderedHomeworkMetadataReader {
    private val gson = Gson()
    private val policy = TrustedOriginPolicy()

    private fun matches(url: String, item: Homework, requireWorkId: Boolean): Boolean {
        if (!policy.allows(url)) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!Regex("/work/|/workHandle/", RegexOption.IGNORE_CASE).containsMatchIn(parsed.encodedPath)) return false
        fun values(vararg names: String) = parsed.queryParameterNames.filter { key -> names.any { it.equals(key, true) } }.mapNotNull(parsed::queryParameter)
        val work = values("workid", "taskrefid")
        return (!requireWorkId || work.isNotEmpty()) && work.all { it == item.id.substringAfterLast(':') } &&
            values("courseid").all { it == item.course.id.substringBefore(':') } &&
            values("classid", "clazzid").all { it == item.course.id.substringAfter(':') }
    }

    suspend fun read(web: WebView, item: Homework): Homework? = withContext(Dispatchers.Main.immediate) {
        val top = web.url.orEmpty()
        if (!matches(top, item, requireWorkId = true)) return@withContext null
        val raw = web.evaluate(SCRIPT)
        if (web.url != top || raw.length > 800_000) return@withContext null
        val pages = runCatching {
            gson.fromJson(gson.fromJson(raw, String::class.java), Array<RenderedMetadataDto>::class.java)
        }.getOrNull() ?: return@withContext null
        pages.asSequence().filter { matches(it.url, item, requireWorkId = false) }.map { page ->
            val doc = Document(page.url)
            doc.appendElement("body").text(page.text)
            page.fields.forEach { (name, value) -> doc.body().appendElement("input").attr("name", name).attr("value", value) }
            McpChaoxingParser().details(item, OfficialHtml(page.url, doc.outerHtml()))
        }.firstOrNull { it.dueAt != null || it.dueText != null }
    }

    companion object {
        private val SCRIPT = """
            (() => {
              const documents = [document], result = [];
              for (let i = 0; i < documents.length && i < 4; i++) {
                const d = documents[i];
                for (const frame of d.querySelectorAll('iframe,frame')) {
                  try { if (frame.contentDocument?.body && !documents.includes(frame.contentDocument)) documents.push(frame.contentDocument); } catch (_) {}
                }
                const root = d.body?.cloneNode(true);
                if (!root) continue;
                root.querySelectorAll('.TiMu,.singleQuesId,.question-item,[data-question-id],[data-questionid],script,style,textarea,img,svg,canvas,iframe,frame').forEach(e => e.remove());
                const fields = {};
                for (const e of root.querySelectorAll('input')) {
                  const name = (e.name || e.id || '').toLowerCase();
                  if (['endtime','workendtime','deadline','enddate'].includes(name)) fields[name] = e.value;
                }
                for (const e of root.querySelectorAll('[data-endtime],[data-end-time],[data-deadline]')) {
                  fields.endtime ||= e.getAttribute('data-endtime') || e.getAttribute('data-end-time') || e.getAttribute('data-deadline');
                }
                result.push({url:d.URL, text:(root.textContent || '').slice(0,64000), fields});
              }
              return JSON.stringify(result);
            })()
        """.trimIndent()
    }
}
