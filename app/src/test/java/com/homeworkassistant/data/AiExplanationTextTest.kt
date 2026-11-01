package com.homeworkassistant.data

import com.google.gson.Gson
import com.homeworkassistant.data.ai.AiAnswerCodec
import com.homeworkassistant.data.ai.AiExplanationText
import com.homeworkassistant.domain.model.Question
import com.homeworkassistant.domain.model.QuestionType
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AiExplanationTextTest {
    private val question = Question("fill", 1, QuestionType.FILL, "x + 1 = 3，x = ____。", emptyList())
    private fun decode(explanation: String, field: String = "explanation") = AiAnswerCodec.decode(
        Gson().toJson(mapOf("answers" to listOf(mapOf("id" to question.id, "answer" to "\\n", field to explanation)))),
        listOf(question)
    ).single()

    @Test fun doubleEscapedStepsBecomeRealLinesWithoutChangingTheAnswer() {
        val decoded = decode("1. 列式。\\n2. 计算。\\n\\n3. 核对。")
        assertEquals("1. 列式。\n2. 计算。\n\n3. 核对。", decoded.explanation)
        assertEquals("\\n", decoded.answer)
    }

    @Test fun upstreamReasonUsesTheSameNormalization() {
        assertEquals("第一步\n第二步", decode("第一步\\n第二步", "reason").explanation)
    }

    @Test fun nativeWindowsAndRepeatedEscapingAreNormalizedOnce() {
        for (separator in listOf("\n", "\r\n", "\\r\\n", "\\n", "\\\\n")) {
            val normalized = AiExplanationText.normalize("第一步" + separator + "第二步")
            assertEquals("第一步\n第二步", normalized)
            assertEquals(normalized, AiExplanationText.normalize(normalized))
        }
    }

    @Test fun englishStepTextIsAlsoSplitIntoLines() {
        assertEquals("First step\nNext step\nthen verify", decode("First step\\nNext step\\nthen verify").explanation)
    }

    @Test fun texCommandsAndAllCommonMathDelimitersStayVerbatim() {
        val math = listOf(
            """公式 \nu、\nabla f、\neq、\notin 与 \nRightarrow。""",
            """$\begin{matrix}a\\n & b\end{matrix}$""",
            "\$\$" + """a\\n & b""" + "\$\$",
            """\(\nu + \nabla f\\n\)""",
            """\[\begin{matrix}a\\n & b\end{matrix}\]""",
            """\begin{align}a\\n & b\end{align}"""
        )
        for (formula in math) {
            assertEquals(formula + "\n下一步", decode(formula + "\\n下一步").explanation)
        }
    }

    @Test fun inlineAndFencedCodePreserveLiteralEscapes() {
        for (code in listOf(
            """`print("\\n")`""",
            """``包含 ` 与 \\n``""",
            "```python\nprint(\"\\\\n\")\n```",
            "~~~python\nprint(\"\\\\n\")\n~~~"
        )) {
            assertEquals(code + "\n下一步", decode(code + "\\n下一步").explanation)
        }
    }

    @Test fun windowsPathsRemainIntactBeforeTheNextStep() {
        val path = """路径 C:\new\notes\file.txt"""
        assertEquals(path + "\n下一步", decode(path + "\\n下一步").explanation)
    }

    @Test fun lineBreakOnlyOutputDoesNotSatisfyTheFillStepsRequirement() {
        assertThrows(IOException::class.java) { decode("\\n\\n") }
    }
}
