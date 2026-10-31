package com.homeworkassistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.homeworkassistant.domain.model.*
import com.homeworkassistant.ui.exams.ExamsScreen
import com.homeworkassistant.ui.home.HomeworkList
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class DeadlineExamUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pendingHomeworkShowsOfficialMonthDayEvenWithoutAConfirmedYear() {
        val item = Homework("1", Course("math", "数学"), "课后作业", "https://mooc1.chaoxing.com/work/task",
            status = HomeworkStatus.NOT_SUBMITTED, dueText = "10-30 23:59（年份待确认）")
        compose.setContent { HomeworkTheme { HomeworkList(listOf(item), false, Instant.EPOCH) } }
        compose.onNodeWithText("截止 10-30 23:59（年份待确认）").assertIsDisplayed()
        compose.onNodeWithText("课后作业").assertIsDisplayed()
    }
    @Test fun examCardsOpenTheirOwnIdentityAndCompletedFilteringStillWorks() {
        val pending = Exam("one", "本场数学考试", "另行通知", null, null, "60分钟", "进行中", "未交卷", "--", "线上",
            HomeworkStatus.NOT_SUBMITTED, detailUrl = "https://mooc1.chaoxing.com/exam-ans/exam/test?examId=1")
        val done = pending.copy(id = "two", title = "已交英语考试", submissionStatus = HomeworkStatus.SUBMITTED,
            detailUrl = "https://mooc1.chaoxing.com/exam-ans/exam/test?examId=2")
        var opened: Exam? = null
        compose.setContent { HomeworkTheme { ExamsScreen(ExamSnapshot("test", listOf(pending, done), Instant.EPOCH), false,
            null, {}, {}, {}, onOpen = { opened = it }) } }
        compose.onNodeWithText(pending.title).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(pending, opened) }
        compose.onNodeWithText("已完成", substring = false).performScrollTo().performClick()
        compose.onNodeWithText(done.title).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(done, opened) }
    }
    @Test fun examWithNoVerifiedLinkProvidesAnExplicitOfficialListFallback() {
        val exam = Exam("one", "入口待确认", "另行通知", null, null, "60分钟", "进行中", "未交卷", "--", "线上", HomeworkStatus.NOT_SUBMITTED)
        var opens = 0
        compose.setContent { HomeworkTheme { ExamsScreen(ExamSnapshot("test", listOf(exam), Instant.EPOCH), false,
            null, {}, { opens++ }, {}) } }
        compose.onNodeWithText("未获取到本场入口，点击从官方列表进入").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }
}
