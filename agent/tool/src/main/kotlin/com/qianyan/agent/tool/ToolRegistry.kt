package com.qianyan.agent.tool

import com.qianyan.model.agent.ToolName

/**
 * 工具注册表（P10，架构 §21.2）。
 *
 * 只负责注册 / 查找。P10 不做：Tool Discovery、动态插件、跨进程注册、权限系统。
 * 查询结果用 [find] 返回 nullable；需要抛类型化错误由调用方（ToolExecutor）转 [ToolException]。
 */
class ToolRegistry {

    private val tools: MutableMap<ToolName, Tool> = LinkedHashMap()

    /** 注册工具；同名覆盖。 */
    @Synchronized
    fun register(tool: Tool) {
        tools[tool.definition.toolName] = tool
    }

    /** 按注册名查找；不存在返回 null。 */
    @Synchronized
    fun find(name: ToolName): Tool? = tools[name]

    /** 当前已注册工具（迭代顺序 = 注册顺序）。 */
    @Synchronized
    fun all(): List<Tool> = tools.values.toList()

    /** 是否已注册。 */
    @Synchronized
    fun contains(name: ToolName): Boolean = tools.containsKey(name)

    fun size(): Int = tools.size
}