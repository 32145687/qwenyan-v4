package com.qianyan.agent.tool

/**
 * 工具执行上下文（P10 最小）。
 *
 * 职责边界：跨工具执行传递的角色/作用域/追踪信息。P10 保持最小（当前无域状态），
 * 后续需携带 VariantContext 等作用域时在此扩展，不修改 core:model。
 */
class ToolContext {
    private val tags: MutableMap<String, String> = LinkedHashMap()
    fun tag(key: String, value: String) = apply { tags[key] = value }
    fun tags(): Map<String, String> = LinkedHashMap(tags)
}