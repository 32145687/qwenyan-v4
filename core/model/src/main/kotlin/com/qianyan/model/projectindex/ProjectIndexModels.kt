package com.qianyan.model.projectindex

import com.qianyan.model.ChapterId
import com.qianyan.model.DraftId
import com.qianyan.model.NovelId
import com.qianyan.model.ProjectId
import com.qianyan.model.VariantId
import com.qianyan.model.VocabularyCandidateId
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/*
 * I12 · Project Index 契约（纯领域：无 storage / provider / agent runtime / UI 依赖）。
 *
 * 定位（architecture §19 / §26 的 A2）：
 *   Canonical Project Data（Novel / Chapter / Draft / StoryFoundation / Vocabulary）
 *        ↓ rebuild（派生）
 *   **Project Index**（"这个项目里有什么内容，以及这些内容在哪里"）
 *        ↓ 定位 / 候选引用
 *   Context Engine（I6：选择 / 排序 / 截断）→ Novel Agent（I11：决定任务与执行）
 *
 * 硬边界：
 *  - **Derived / Rebuildable**：索引永远从 Canonical 数据重建；索引不是事实来源，
 *    也**不允许** Index → Canonical 的恢复路径；
 *  - **不是第二套数据库事实源**：默认纯内存派生（见 `ProjectIndexUseCases`），不新增表 / 不新增 Canonical schema；
 *  - **≠ Project State**：索引只回答"内容在哪里"，不承载 UI / Agent 运行态（不读也不复制 ProjectState）；
 *  - **≠ World Model**：不把 Character / Relationship / Timeline / Foreshadow 等故事事实塞进索引
 *    （它们没有稳定 Canonical 来源时，建立索引即为虚构）；
 *  - **≠ Context Engine**：索引只做**定位与候选发现**，不做选择 / 优先级 / 预算 / 截断 / ContextPack 组装；
 *  - **不复制完整 Canonical Entity / 不复制正文**：条目只保存定位所需信息
 *    （稳定引用 + 标签 + 可检索词 + 来源版本），正文仍由 Product Tool（I5）按引用读取；
 *  - **不建立第二套搜索基础设施**：无 FTS / embedding / vector / RAG / LLM。
 */

/** 索引条目身份（**确定性**：`<entryType>:<主键>`；不含 UUID / 时间）。 */
@JvmInline
@Serializable
value class ProjectIndexEntryId(val value: String)

/** 索引版本（派生指纹；用于"索引是否过期"判定，不是第二套全局版本系统）。 */
@JvmInline
@Serializable
value class ProjectIndexVersion(val value: Long)

/**
 * 索引条目类型（**只覆盖当前有稳定 Canonical 来源的对象**）。
 *
 * 取值顺序 = 索引内固定的结构顺序（作品 → 章节 → 正文 → 故事基础 → 词汇）。
 */
@Serializable
enum class ProjectIndexEntryType {
    NOVEL,
    CHAPTER,
    DRAFT,
    STORY_FOUNDATION,
    VOCABULARY,
}

/**
 * 稳定引用：定位一个 Canonical 对象所需的最小 ID 元组。
 *
 * 刻意**不承载任何对象内容**（不是 Novel / Chapter / Draft 的副本），
 * 形状与既有引用（I5 视图 / I6 `ContextItemRef`）对齐，但属索引自身定位模型。
 */
@Serializable
data class ProjectIndexReference(
    val novelId: NovelId,
    val variantId: VariantId? = null,
    val chapterId: ChapterId? = null,
    val draftId: DraftId? = null,
    val vocabularyCandidateId: VocabularyCandidateId? = null,
)

/**
 * 一条索引条目：**"某类内容在哪里"**。
 *
 * @param label 可读定位标签（标题 / 术语 / 名称；**不是正文**）。
 * @param parentReference 归属引用（章节 → 作品；正文 → 章节；基础 / 词汇 → 作品）。
 * @param ordinal 稳定排序位次（章节 order 等；其他类型为 0）。
 * @param sourceVersion 来源版本指纹（`updatedAt` / `version` / `status` / 正文**字数**等元数据；**不含正文**）。
 * @param keywords 可检索词（标签 + 别名 + 编号等；用于候选发现，非全文检索）。
 */
@Serializable
data class ProjectIndexEntry(
    val entryId: ProjectIndexEntryId,
    val projectId: ProjectId,
    val entryType: ProjectIndexEntryType,
    val reference: ProjectIndexReference,
    val label: String,
    val parentReference: ProjectIndexReference? = null,
    val ordinal: Int = 0,
    val sourceVersion: String = "",
    val keywords: List<String> = emptyList(),
)

/**
 * 一次索引构建的**冻结结果**（派生数据；可丢弃、可重建）。
 *
 * @param scopeVariantId 本次索引覆盖的作用域（`null` = Original 基座）。
 *   作用域由调用方**显式**指定（不由 ProjectState 推断 ⇒ 索引不依赖可变运行态）。
 * @param version 派生指纹（输入变化 ⇒ 版本变化 ⇒ 可由 `isStale` 判定过期）。
 * @param builtAt 构建时间（可注入时钟；**不参与**版本指纹）。
 */
@Serializable
data class ProjectIndex(
    val projectId: ProjectId,
    val novelId: NovelId,
    val scopeVariantId: VariantId? = null,
    val entries: List<ProjectIndexEntry> = emptyList(),
    val version: ProjectIndexVersion,
    val builtAt: Instant,
) {
    /** 按类型筛选（稳定顺序）。 */
    fun ofType(type: ProjectIndexEntryType): List<ProjectIndexEntry> = entries.filter { it.entryType == type }
}

/** 条目构造 / 规范化（确定性：entryId 唯一 + 固定排序）。 */
object ProjectIndexEntries {

    /** 确定性条目 ID：`<entryType 小写>:<主键>`。 */
    fun entryId(type: ProjectIndexEntryType, primaryKey: String): ProjectIndexEntryId =
        ProjectIndexEntryId("${type.name.lowercase()}:$primaryKey")

    /** 可检索词规范化：去除空白项 / 去重 / 保持声明顺序（确定性）。 */
    fun keywords(vararg values: String?): List<String> = values
        .mapNotNull { it?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    /** 固定排序：结构顺序（类型）→ 位次 → 条目 ID；None 依赖插入序 / Map 迭代序。 */
    val ORDER: Comparator<ProjectIndexEntry> =
        compareBy({ it.entryType.ordinal }, { it.ordinal }, { it.entryId.value })

    /** 规范化（按 entryId 去重 + 固定排序）：重复 rebuild 不产生重复条目。 */
    fun normalize(entries: List<ProjectIndexEntry>): List<ProjectIndexEntry> =
        entries.distinctBy { it.entryId.value }.sortedWith(ORDER)
}

/**
 * 派生版本指纹（FNV-1a；与 `ContextPackVersion` / `ChangeFingerprint` 同一范式）。
 *
 * 覆盖：项目 / 作品 / 作用域 + 全部条目的（id / 类型 / 引用 / 标签 / 位次 / 来源版本 / 可检索词）。
 * 不覆盖 `builtAt`（输出元数据，不参与内容等价性）。
 */
object ProjectIndexVersioning {

    private const val FNV_OFFSET_BASIS_64 = -3750763034362895579L
    private const val FNV_PRIME_64 = 1099511628211L

    fun of(
        projectId: ProjectId,
        novelId: NovelId,
        scopeVariantId: VariantId?,
        entries: List<ProjectIndexEntry>,
    ): ProjectIndexVersion {
        val fp = buildString {
            append(projectId.value).append('|')
            append(novelId.value).append('|')
            append(scopeVariantId?.value).append('|')
            entries.forEach { e ->
                append(e.entryId.value).append('#')
                    .append(e.entryType.name).append('#')
                    .append(e.reference.novelId.value).append('#')
                    .append(e.reference.variantId?.value).append('#')
                    .append(e.reference.chapterId?.value).append('#')
                    .append(e.reference.draftId?.value).append('#')
                    .append(e.reference.vocabularyCandidateId?.value).append('#')
                    .append(e.label).append('#')
                    .append(e.parentReference?.chapterId?.value).append('#')
                    .append(e.parentReference?.draftId?.value).append('#')
                    .append(e.ordinal).append('#')
                    .append(e.sourceVersion).append('#')
                    .append(e.keywords.joinToString(",")).append(';')
            }
        }
        return ProjectIndexVersion(fnv1a64(fp))
    }

    private fun fnv1a64(s: String): Long {
        var h = FNV_OFFSET_BASIS_64
        for (c in s) {
            h = h xor c.code.toLong()
            h *= FNV_PRIME_64
        }
        return h
    }
}

/**
 * Project Index 的稳定错误码（承载在既有 `ApplicationError` 的 detail 中；不新建错误体系）。
 */
object ProjectIndexErrorCodes {

    /** 索引尚未构建（查询必须先经 `rebuild`）。 */
    const val INDEX_NOT_BUILT: String = "INDEX_NOT_BUILT"

    /** Project / 其 Novel 不存在（越界统一表现为不存在，不泄漏存在性）。 */
    const val PROJECT_NOT_FOUND: String = "PROJECT_NOT_FOUND"

    /** 指定的作用域（variantId）不属于本 Project 的 Novel。 */
    const val SCOPE_MISMATCH: String = "SCOPE_MISMATCH"

    /** 索引构建失败（来源读取异常等）。 */
    const val INDEX_BUILD_FAILED: String = "INDEX_BUILD_FAILED"
}