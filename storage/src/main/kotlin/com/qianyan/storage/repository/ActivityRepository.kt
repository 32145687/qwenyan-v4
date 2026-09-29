package com.qianyan.storage.repository

import com.qianyan.model.ActivityId
import com.qianyan.model.AgentSessionId
import com.qianyan.model.ProjectId
import com.qianyan.model.log.Activity

/**
 * Activity 持久化仓储（I4 · FD-9）。
 *
 * 只承担"已发生活动"的事实记录读写。**是记录，不是控制器**：
 *  - 不改写 AgentSession / Workflow / Task 状态，也不创建它们；
 *  - 不承载正文 / World Model / Context 内容 / Skill / Permission（属既有模块或后续阶段）。
 */
interface ActivityRepository {

    /** 创建/覆盖一条活动记录（同 activityId 覆盖，不产生第二行）。 */
    fun save(activity: Activity)

    /** 读取活动记录；不存在返回 null（不伪造记录）。 */
    fun get(activityId: ActivityId): Activity?

    /** 会话内活动（时序升序）。 */
    fun listBySession(sessionId: AgentSessionId): List<Activity>

    /** 项目内活动（时序升序；不得跨 Project 泄漏）。 */
    fun listByProject(projectId: ProjectId): List<Activity>
}