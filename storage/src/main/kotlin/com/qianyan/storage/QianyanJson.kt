package com.qianyan.storage

import kotlinx.serialization.json.Json

/**
 * Storage 层共享的 JSON 实例（kotlinx.serialization）。
 * replacedValue / suggested / genre / aliases 等一律以结构化 JSON 持久化，
 * 禁用 Map<String, Any>（P2.5 / P1 决策）。
 */
object QianyanJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}