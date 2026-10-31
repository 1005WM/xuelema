package com.homeworkassistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.homeworkassistant.ui.components.*
import com.homeworkassistant.ui.theme.HomeworkTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LoginOriginDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun approvalShowsExactOriginAndRequiresExplicitButton() {
        var allowed = false
        var declined = false
        compose.setContent { HomeworkTheme {
            LoginOriginDialog(LoginRedirectApproval("https://sso.school.example", allow = { allowed = true }, decline = { declined = true }))
        } }
        compose.onNodeWithText("https://sso.school.example", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertFalse(allowed); assertFalse(declined) }
        compose.onNodeWithText("本次允许").performClick()
        compose.runOnIdle { assertTrue(allowed); assertFalse(declined) }
    }
    @Test fun cancelDoesNotAuthorizeNavigation() {
        var allowed = false
        var declined = false
        compose.setContent { HomeworkTheme {
            LoginOriginDialog(LoginRedirectApproval("https://sso.school.example", allow = { allowed = true }, decline = { declined = true }))
        } }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertFalse(allowed); assertTrue(declined) }
    }
}
