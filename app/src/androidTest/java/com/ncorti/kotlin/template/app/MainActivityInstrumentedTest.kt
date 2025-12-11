package com.ncorti.kotlin.template.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @get:Rule
    val rule = activityScenarioRule<MainActivity>()

    @Test
    fun testTabCreationAndNavigation() {
        // 测试创建新标签页
        onView(withId(R.id.etAlias)).perform(typeText("Test Tab"), closeSoftKeyboard())
        onView(withId(R.id.etUrl)).perform(typeText("https://example.com"), closeSoftKeyboard())
        onView(withId(R.id.btnGo)).perform(click())

        // 验证WebView容器是否可见
        onView(withId(R.id.webviewContainer)).check(matches(isDisplayed()))

        // 测试返回主页
        onView(withId(R.id.btnHome)).perform(click())
        onView(withId(R.id.layoutHome)).check(matches(isDisplayed()))
    }

    @Test
    fun testTabPinFunctionality() {
        // 创建两个标签页
        createTestTab("Tab 1", "https://example.com")
        createTestTab("Tab 2", "https://example.org")

        // 测试固定第一个标签页
        onView(withId(R.id.btnPinTab)).perform(click())

        // 测试切换标签页
        onView(withId(R.id.btnSwitch)).perform(click())
        // 这里需要添加选择第二个标签页的测试逻辑
    }

    @Test
    fun testNotificationToggle() {
        // 创建测试标签页
        createTestTab("Test Tab", "https://example.com")

        // 测试切换通知状态
        onView(withId(R.id.btnToggleNotify)).perform(click())
    }

    @Test
    fun testTabSettingsAccess() {
        // 创建测试标签页
        createTestTab("Test Tab", "https://example.com")

        // 测试打开标签页设置
        onView(withId(R.id.btnTabSettings)).perform(click())
    }

    private fun createTestTab(alias: String, url: String) {
        // 确保在主页
        onView(withId(R.id.layoutHome)).check(matches(isDisplayed()))
        
        // 输入标签页信息并创建
        onView(withId(R.id.etAlias)).perform(typeText(alias), closeSoftKeyboard())
        onView(withId(R.id.etUrl)).perform(typeText(url), closeSoftKeyboard())
        onView(withId(R.id.btnGo)).perform(click())
    }
}