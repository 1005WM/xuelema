package com.homeworkassistant.data.ai

/** Repair escaped prose line breaks without unescaping answers, code, or TeX. */
internal object AiExplanationText {
    private val protectedText = Regex(
        """(`+)[\s\S]*?\1|~~~[\s\S]*?~~~|\$\$[\s\S]*?\$\$|\$(?:\\.|[^$\\\r\n])+\$|\\\([\s\S]*?\\\)|\\\[[\s\S]*?\\\]|\\begin\{([^}]+)\}[\s\S]*?\\end\{\2\}|[A-Za-z]:\\(?:(?!\\+n(?![A-Za-z]))[^\s`])+"""
    )
    private val escapedBreak = Regex("""\\+r\\+n|\\+n""")
    // Math regions are protected above; preserve common TeX commands used in plain prose too.
    private val texCommands = setOf(
        "nabla", "natural", "ne", "neq", "neg", "negmedspace", "negthickspace", "negthinspace",
        "nexists", "ngtr", "ngeq", "ngeqq", "ngeqslant", "nleq", "nleqq", "nleqslant", "nless",
        "nleftarrow", "nLeftarrow", "nleftrightarrow", "nLeftrightarrow", "nrightarrow", "nRightarrow",
        "nmid", "nparallel", "nprec", "npreceq", "npreccurlyeq", "nsucc", "nsucceq", "nsucccurlyeq",
        "nshortmid", "nshortparallel", "nsim", "nsimeq", "ncong", "ntriangleleft", "ntrianglelefteq",
        "ntriangleright", "ntrianglerighteq", "nsqsubseteq", "nsqsupseteq", "nsubset", "nsubseteq",
        "nsubseteqq", "nsupset", "nsupseteq", "nsupseteqq", "nu", "ni", "not", "notin", "notni",
        "nwarrow", "nearrow", "newcommand", "newenvironment", "newtheorem", "newcounter", "newlength",
        "newsavebox", "newif", "newline", "noindent", "nolimits", "nonumber", "notag", "nobreak",
        "nobreakspace", "nobreakdash", "normalfont", "normalsize", "nvdash", "nvDash", "nVdash", "nVDash"
    )

    fun normalize(text: String): String {
        if (!text.contains("\\n") && !text.contains('\r')) return text
        val output = StringBuilder(text.length)
        var start = 0
        for (match in protectedText.findAll(text)) {
            output.append(prose(text.substring(start, match.range.first)))
            output.append(match.value)
            start = match.range.last + 1
        }
        output.append(prose(text.substring(start)))
        return output.toString()
    }

    private fun prose(text: String): String {
        val source = text.replace("\r\n", "\n").replace('\r', '\n')
        return escapedBreak.replace(source) { match ->
            val end = match.range.last + 1
            var wordEnd = end
            while (wordEnd < source.length && (source[wordEnd] in 'a'..'z' || source[wordEnd] in 'A'..'Z')) wordEnd++
            val command = "n" + source.substring(end, wordEnd)
            if ('r' !in match.value && command in texCommands) match.value else "\n"
        }
    }
}
