package com.qianyan.app.android

import android.app.Application
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.qianyan.application.di.ApplicationContainer
import com.qianyan.provider.impl.MockLLMGateway
import com.qianyan.storage.db.DatabaseInitializer
import com.qianyan.storage.db.QianyanDb

/**
 * Android 应用级 DI 根（P7.3）。
 *
 * 装配链（仅组合根，不含任何业务逻辑 / UI）：
 * ```
 * QianyanApplication
 *       │  AndroidSqliteDriver（context.filesDir/qianyan.db）
 *       │  DatabaseInitializer.initializeDatabase(driver)   // 幂等建表 + 守卫触发器
 *       │  MockLLMGateway()                                 // P7 仍使用 Mock Provider
 *       ▼
 * ApplicationContainer.fromDriver(driver, analysisGateway)
 *       ▼
 * Application UseCases（novels / vocabularies / txts / analysis / ...）
 * ```
 *
 * 约束：
 *  - 只通过 `ApplicationContainer`（:application 组合根）访问能力；
 *  - 不直接实例化 Sqlite 仓储、不执行 SQL、不调用 [com.qianyan.storage.db.QianyanDbFactory.open]（JDBC 专用）；
 *  - 不在此实现业务逻辑、不读取 TXT、不执行 Analysis。
 *  - 容器只创建一次，供后续 Activity / ViewModel 使用（P7.4 起）。
 */
class QianyanApplication : Application() {

    /** 应用级 ApplicationContainer（只创建一次，外部只读）。 */
    lateinit var container: ApplicationContainer
        private set

    override fun onCreate() {
        super.onCreate()

        val driver = AndroidSqliteDriver(
            schema = QianyanDb.Schema,
            context = this,
            name = DB_NAME,
        )
        // 幂等初始化：仅首次建表，后续启动跳过（P7.0 DatabaseInitializer 语义）。
        DatabaseInitializer.initializeDatabase(driver)

        container = ApplicationContainer.fromDriver(
            driver = driver,
            analysisGateway = MockLLMGateway(),
        )
    }

    private companion object {
        /** 应用私有目录下的数据库文件名（context.filesDir）。 */
        const val DB_NAME: String = "qianyan.db"
    }
}
