package com.homeworkassistant.data.mcp

import com.homeworkassistant.domain.model.Exam

enum class CourseLifecycle { ACTIVE, ENDED, UNAVAILABLE, UNKNOWN }
data class CourseScanScope(val courses: List<McpCourse>, val catalogComplete: Boolean = true) {
    val active get() = courses.filter { it.lifecycle == CourseLifecycle.ACTIVE }
    val ended get() = courses.filter { it.lifecycle == CourseLifecycle.ENDED }
    val unknown get() = courses.filter { it.lifecycle == CourseLifecycle.UNKNOWN }
    val excludedCourseIds get() = courses.filter { it.lifecycle in setOf(CourseLifecycle.ENDED, CourseLifecycle.UNAVAILABLE) }
        .map { it.domain.id }.toSet()

    /** IDs take priority; exact course names are allowed only when unique across all classes. */
    fun courseFor(exam: Exam): McpCourse? {
        val matches = courses.filter { course ->
            when {
                exam.courseId != null || exam.classId != null ->
                    (exam.courseId == null || course.courseId == exam.courseId) &&
                        (exam.classId == null || course.classId == exam.classId)
                !exam.courseName.isNullOrBlank() -> course.name == exam.courseName
                else -> false
            }
        }
        return matches.singleOrNull()
    }
    fun includes(exam: Exam) = courseFor(exam)?.lifecycle == CourseLifecycle.ACTIVE
    fun excludes(exam: Exam) = courseFor(exam)?.lifecycle in setOf(CourseLifecycle.ENDED, CourseLifecycle.UNAVAILABLE)
}
