package com.qianyan.app.android

import android.app.Application

/**
 * P0 占位 Application。
 * 仅用于让 App 模块可构建；不包含任何业务逻辑（后续 Phase 填充 DI / 初始化）。
 */
class QianyanApplication : Application()