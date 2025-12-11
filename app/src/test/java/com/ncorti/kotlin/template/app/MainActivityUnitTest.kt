package com.ncorti.kotlin.template.app

import org.junit.Assert.*
import org.junit.Test

class MainActivityUnitTest {

    @Test
    fun testTabConfigCreation() {
        // 测试TabConfig数据模型的创建
        val tabConfig = TabConfig(alias = "Test Tab", url = "https://example.com")
        
        assertEquals("Test Tab", tabConfig.alias)
        assertEquals("https://example.com", tabConfig.url)
        assertEquals("Test Tab", tabConfig.appName) // 默认appName与alias相同
        assertFalse(tabConfig.isPinned)
        assertFalse(tabConfig.isNotifyActive)
        assertEquals(30000L, tabConfig.checkInterval)
        assertTrue(tabConfig.rules.isEmpty())
    }

    @Test
    fun testTabConfigUpdate() {
        // 测试TabConfig数据模型的更新
        val tabConfig = TabConfig(alias = "Test Tab", url = "https://example.com")
        
        // 更新固定状态
        tabConfig.isPinned = true
        assertTrue(tabConfig.isPinned)
        
        // 更新通知状态
        tabConfig.isNotifyActive = true
        assertTrue(tabConfig.isNotifyActive)
        
        // 更新应用名称
        tabConfig.appName = "Example App"
        assertEquals("Example App", tabConfig.appName)
        
        // 更新检查间隔
        tabConfig.checkInterval = 60000L
        assertEquals(60000L, tabConfig.checkInterval)
    }

    @Test
    fun testMonitorRuleCreation() {
        // 测试MonitorRule数据模型的创建
        val rule = MonitorRule(keyword = "Error", threshold = 3, alertMessage = "严重错误")
        
        assertEquals("Error", rule.keyword)
        assertEquals(3, rule.threshold)
        assertEquals("严重错误", rule.alertMessage)
    }

    @Test
    fun testMonitorRuleUpdate() {
        // 测试MonitorRule数据模型的更新
        val rule = MonitorRule(keyword = "Error", threshold = 3, alertMessage = "严重错误")
        
        rule.keyword = "Warning"
        rule.threshold = 5
        rule.alertMessage = "警告信息"
        
        assertEquals("Warning", rule.keyword)
        assertEquals(5, rule.threshold)
        assertEquals("警告信息", rule.alertMessage)
    }

    @Test
    fun testDefaultRulesStructure() {
        // 测试默认规则应该包含的内容
        val defaultRules = listOf(
            MonitorRule("Error", 3, "严重错误"),
            MonitorRule("Timeout", 3, "网络超时"),
            MonitorRule("Exception", 3, "程序异常"),
            MonitorRule("失败", 3, "操作失败报警")
        )
        
        assertEquals(4, defaultRules.size)
        assertEquals("Error", defaultRules[0].keyword)
        assertEquals("Timeout", defaultRules[1].keyword)
        assertEquals("Exception", defaultRules[2].keyword)
        assertEquals("失败", defaultRules[3].keyword)
    }
}
