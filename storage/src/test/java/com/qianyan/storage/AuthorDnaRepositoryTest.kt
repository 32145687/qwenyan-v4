package com.qianyan.storage

import com.qianyan.model.AuthorDnaFeatureId
import com.qianyan.model.AuthorDnaSourceId
import com.qianyan.model.AuthorDnaVersionId
import com.qianyan.model.AuthorProfileId
import com.qianyan.model.NovelId
import com.qianyan.model.TxtDocumentId
import com.qianyan.model.author.AuthorDnaDimension
import com.qianyan.model.author.AuthorDnaFeature
import com.qianyan.model.author.AuthorDnaFeatureStatus
import com.qianyan.model.author.AuthorDnaSource
import com.qianyan.model.author.AuthorDnaSourceRef
import com.qianyan.model.author.AuthorDnaVersion
import com.qianyan.model.author.AuthorDnaVersionStatus
import com.qianyan.model.author.Confidence
import com.qianyan.storage.db.QianyanDbFactory
import com.qianyan.storage.repository.SqliteAuthorDnaRepository
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuthorDnaRepositoryTest {

    private fun repo() = SqliteAuthorDnaRepository(QianyanDbFactory.open(JdbcSqliteDriver.IN_MEMORY).db)

    private val now: Instant = Instant.fromEpochSeconds(1700000000, 0)

    @Test
    fun `version feature source roundtrip`() {
        val r = repo()
        val author = AuthorProfileId("author-1")
        val version = AuthorDnaVersion(
            versionId = AuthorDnaVersionId("v1"),
            authorId = author,
            version = 1,
            status = AuthorDnaVersionStatus.ACTIVE,
            ruleVersion = "p18c:v1",
            createdAt = now,
            updatedAt = now,
        )
        r.upsertAuthorDnaVersion(version)

        val feature = AuthorDnaFeature(
            featureId = AuthorDnaFeatureId("f1"),
            versionId = version.versionId,
            authorId = author,
            dimension = AuthorDnaDimension.DIALOGUE,
            featureKey = "dialogue.density",
            value = "high",
            statement = "对话占比较高",
            confidence = Confidence.HIGH,
            status = AuthorDnaFeatureStatus.CANDIDATE,
            sourceRefs = listOf(AuthorDnaSourceRef(TxtDocumentId("doc1"), NovelId("n1"), 0, 100)),
            createdAt = now,
            updatedAt = now,
        )
        r.upsertAuthorDnaFeature(feature)

        val source = AuthorDnaSource(
            sourceId = AuthorDnaSourceId("s1"),
            authorId = author,
            txtDocumentId = TxtDocumentId("doc1"),
            sourceNovelId = NovelId("n1"),
            contentHash = "abc",
            createdAt = now,
            updatedAt = now,
        )
        r.upsertAuthorDnaSource(source)

        assertEquals(version, r.getAuthorDnaVersion(version.versionId))
        val got = r.getAuthorDnaFeature(feature.featureId)
        assertEquals("dialogue.density", got!!.featureKey)
        assertEquals("high", got.value)
        assertEquals(1, got.sourceRefs.size)
        assertEquals(source, r.getAuthorDnaSource(source.sourceId))
        assertEquals(listOf(source), r.listAuthorDnaSources(author))

        // status 迁移
        r.setAuthorDnaFeatureStatus(feature.featureId, AuthorDnaFeatureStatus.ACTIVE)
        assertEquals(AuthorDnaFeatureStatus.ACTIVE, r.getAuthorDnaFeature(feature.featureId)!!.status)

        // 删除来源 / feature / version
        r.deleteAuthorDnaSource(source.sourceId)
        assertNull(r.getAuthorDnaSource(source.sourceId))
        r.deleteAuthorDnaFeatures(version.versionId)
        assertEquals(0, r.listAuthorDnaFeatures(version.versionId).size)
    }
}