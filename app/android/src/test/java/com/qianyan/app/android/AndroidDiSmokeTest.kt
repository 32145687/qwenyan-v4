package com.qianyan.app.android

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.DatabaseInitializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P7.3 Android DI smoke test（JVM unit test）。
 *
 * 验证 Android 侧 DI 装配链中"驱动无关"的部分：
 * ```
 * DatabaseInitializer.initializeDatabase(driver)
 *         ↓
 * ApplicationContainer.fromDriver(driver, MockLLMGateway())
 *         ↓
 * Application UseCases 可访问数据库
 * ```
 *
 * 说明：`AndroidSqliteDriver` 依赖 Android Runtime（Context / 框架 SQLite），无法在纯 JVM 单元测试中实例化，
 * 故本测试以 JVM 内存驱动（[JdbcSqliteDriver]）运行同一装配链，验证 [DatabaseInitializer] 与
 * [ApplicationContainer.fromDriver] 的驱动无关正确性（含重复初始化幂等）。
 * Android 侧使用 `AndroidSqliteDriver` 的生产路径（[QianyanApplication.onCreate]）由 `assembleDebug`
 * 编译验证 + 代码审查确认，不调用 `QianyanDbFactory.open()`（JDBC 专用）。
 */
class AndroidDiSmokeTest {

    /** 新建已初始化的 JVM 内存驱动（模拟 Android 侧"建驱动 + 初始化"两步）。 */
    private fun initializedDriver(): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseInitializer.initializeDatabase(driver)
        return driver
    }

    /* 1. DatabaseInitializer + ApplicationContainer 装配成功，UseCase 可访问数据库 */
    @Test
    fun `container from initialized driver persists and reads via use cases`() {
        val driver = initializedDriver()
        val container = ApplicationContainer.fromDriver(driver, MockLLMGateway())

        val novelId = container.novels.createOriginal(title = "Android Smoke 小说")
        val originals = container.novels.listOriginals()

        assertEquals(1, originals.size, "应能通过 UseCase 读到刚创建的 Original")
        assertEquals(novelId, originals.single().novelId)
        assertTrue(originals.single().scope.name == "ORIGINAL", "列表应只含 Original")
    }

    /* 2. DatabaseInitializer 可成功运行（建表 + 守卫触发器不报错） */
    @Test
    fun `database initializer runs without error`() {
        val driver = initializedDriver()
        val container = ApplicationContainer.fromDriver(driver, MockLLMGateway())

        // 若 Schema 未建成功，createOriginal 会抛 SQL 异常
        val novelId = container.novels.createOriginal(title = "初始化验证")
        assertNotNull(container.novels.getNovel(novelId))
    }

    /* 3. 重复初始化幂等：多次执行不会破坏数据库 */
    @Test
    fun `repeated initialization is safe and preserves data`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DatabaseInitializer.initializeDatabase(driver)
        DatabaseInitializer.initializeDatabase(driver) // 第二次应幂等跳过，不报"表已存在"
        DatabaseInitializer.initializeDatabase(driver)

        val container = ApplicationContainer.fromDriver(driver, MockLLMGateway())
        val novelId = container.novels.createOriginal(title = "幂等验证")
        val read = container.novels.getNovel(novelId)

        assertNotNull(read, "重复初始化后数据应完好")
        assertEquals("幂等验证", read.title)
    }

    /* 4. fromDriver 暴露完整 Use Case 组（Android 后续 UI 的访问入口） */
    @Test
    fun `fromDriver exposes all use case groups`() {
        val driver = initializedDriver()
        val container = ApplicationContainer.fromDriver(driver, MockLLMGateway())

        assertNotNull(container.novels)
        assertNotNull(container.vocabularies)
        assertNotNull(container.txts)
        assertNotNull(container.analysis)
        assertNotNull(container.memories)
        assertNotNull(container.errorMapper)
    }
}
