package com.qianyan.application.usecase.writing.consistency

import com.qianyan.application.error.ApplicationError
import com.qianyan.application.error.ErrorMapper
import com.qianyan.model.characterconsistency.CharacterConsistencyConfidence
import com.qianyan.model.characterconsistency.CharacterConsistencyEvidenceSource
import com.qianyan.model.characterconsistency.CharacterConsistencyFindingType
import com.qianyan.model.characterconsistency.CharacterConsistencyMissingInput
import com.qianyan.model.characterconsistency.CharacterConsistencyScope
import com.qianyan.model.characterconsistency.CharacterConsistencyStatus
import com.qianyan.model.memory.MemoryLayer
import com.qianyan.model.spec.IssueSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Phase 6 · CharacterConsistency Parser 严格解析（T2）。 */
class CharacterConsistencyParserTest {

    private val validIssuesJson = """
        {
          "status":"ISSUES",
          "scope":"CURRENT_CHAPTER",
          "findings":[
            {
              "type":"TRAIT",
              "severity":"WARNING",
              "characterId":"c1",
              "characterName":"林岚",
              "message":"性格描述冲突",
              "evidence":[
                {
                  "quote":"她很胆小",
                  "sourceKind":"CURRENT_DRAFT",
                  "sourceId":"d1",
                  "field":"content",
                  "startOffset":0,
                  "endOffset":4
                }
              ],
              "confidence":"EXPLICIT",
              "suggestion":"调整台词"
            }
          ],
          "checkedCharacters":[{"characterId":"c1","name":"林岚"}],
          "missingInputs":[],
          "summary":"发现 1 处人物一致性问题"
        }
    """.trimIndent()

    @Test
    fun `valid issues json parses strictly`() {
        val raw = CharacterConsistencyParser.parse(validIssuesJson)
        assertEquals(CharacterConsistencyStatus.ISSUES, raw.status)
        assertEquals(CharacterConsistencyScope.CURRENT_CHAPTER, raw.scope)
        assertEquals(1, raw.findings.size)
        assertEquals(CharacterConsistencyFindingType.TRAIT, raw.findings[0].type)
        assertEquals(IssueSeverity.WARNING, raw.findings[0].severity)
        assertEquals("c1", raw.findings[0].characterId)
        assertEquals(CharacterConsistencyConfidence.EXPLICIT, raw.findings[0].confidence)
        assertEquals(CharacterConsistencyEvidenceSource.CURRENT_DRAFT, raw.findings[0].evidence[0].sourceKind)
        assertEquals(0, raw.findings[0].evidence[0].startOffset)
        assertEquals(4, raw.findings[0].evidence[0].endOffset)
        assertEquals(1, raw.checkedCharacters.size)
        assertTrue(raw.missingInputs.isEmpty())
        assertEquals("发现 1 处人物一致性问题", raw.summary)
    }

    @Test
    fun `valid pass with empty lists parses`() {
        val raw = CharacterConsistencyParser.parse(
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"未发现问题"}""",
        )
        assertEquals(CharacterConsistencyStatus.PASS, raw.status)
        assertTrue(raw.findings.isEmpty())
        assertTrue(raw.checkedCharacters.isEmpty())
        assertTrue(raw.missingInputs.isEmpty())
    }

    @Test
    fun `memory evidence and unresolved character parse`() {
        val raw = CharacterConsistencyParser.parse(
            """
            {
              "status":"ISSUES",
              "scope":"CONTINUITY",
              "findings":[
                {
                  "type":"UNRESOLVED_CHARACTER",
                  "severity":"INFO",
                  "characterName":"神秘人",
                  "message":"未解析人物",
                  "evidence":[
                    {"quote":"神秘人出现","sourceKind":"MEMORY","field":"content","memoryLayer":"WRITING","sourceIndex":0}
                  ],
                  "confidence":"INFERRED"
                }
              ],
              "checkedCharacters":[],
              "missingInputs":["CHARACTER_STATE"],
              "summary":"存在未解析人物"
            }
            """.trimIndent(),
        )
        assertEquals(CharacterConsistencyScope.CONTINUITY, raw.scope)
        assertEquals(CharacterConsistencyFindingType.UNRESOLVED_CHARACTER, raw.findings[0].type)
        assertEquals(null, raw.findings[0].characterId)
        assertEquals(MemoryLayer.WRITING, raw.findings[0].evidence[0].memoryLayer)
        assertEquals(0, raw.findings[0].evidence[0].sourceIndex)
        assertEquals(listOf(CharacterConsistencyMissingInput.CHARACTER_STATE), raw.missingInputs)
    }

    @Test
    fun `blank or non json output fails`() {
        listOf("", "   ", "这段写得不好").forEach { raw ->
            val ex = assertFailsWith<CharacterConsistencyException.InvalidOutput> {
                CharacterConsistencyParser.parse(raw)
            }
            assertEquals(CharacterConsistencyErrorCodes.INVALID_OUTPUT, ex.code)
        }
    }

    @Test
    fun `missing required fields fail`() {
        val cases = listOf(
            """{"scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","checkedCharacters":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[]}""",
        )
        cases.forEach { raw ->
            assertFailsWith<CharacterConsistencyException.InvalidOutput> {
                CharacterConsistencyParser.parse(raw)
            }
        }
    }

    @Test
    fun `wrong types and illegal enums fail`() {
        val cases = listOf(
            """{"status":true,"scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":{},"checkedCharacters":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"FATAL","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"FATAL","characterName":"n","message":"m","evidence":[],"confidence":"EXPLICIT"}],"checkedCharacters":[],"missingInputs":[],"summary":"s"}""",
            """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":["UNKNOWN_INPUT"],"summary":"s"}""",
        )
        cases.forEach { raw ->
            assertFailsWith<CharacterConsistencyException.InvalidOutput> {
                CharacterConsistencyParser.parse(raw)
            }
        }
    }

    @Test
    fun `unknown fields fail at top level and nested level`() {
        val topLevel = """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":"s","extra":1}"""
        assertFailsWith<CharacterConsistencyException.InvalidOutput> {
            CharacterConsistencyParser.parse(topLevel)
        }

        val nested = """
            {
              "status":"ISSUES",
              "scope":"CURRENT_CHAPTER",
              "findings":[{
                "type":"TRAIT","severity":"WARNING","characterName":"林岚","message":"m",
                "evidence":[{"quote":"q","sourceKind":"CURRENT_DRAFT","sourceId":"d1","field":"content","extra":1}],
                "confidence":"EXPLICIT"
              }],
              "checkedCharacters":[],
              "missingInputs":[],
              "summary":"s"
            }
        """.trimIndent()
        assertFailsWith<CharacterConsistencyException.InvalidOutput> {
            CharacterConsistencyParser.parse(nested)
        }
    }

    @Test
    fun `trailing content fails`() {
        assertFailsWith<CharacterConsistencyException.InvalidOutput> {
            CharacterConsistencyParser.parse("""$validIssuesJson {}""")
        }
    }

    @Test
    fun `blank required strings fail`() {
        val blankSummary = """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[],"missingInputs":[],"summary":" "}"""
        val blankMessage = """
            {"status":"ISSUES","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"WARNING","characterName":"林岚","message":" ","evidence":[{"quote":"q","sourceKind":"CURRENT_DRAFT","sourceId":"d1","field":"content"}],"confidence":"EXPLICIT"}],"checkedCharacters":[],"missingInputs":[],"summary":"s"}
        """.trimIndent()
        val blankQuote = """
            {"status":"ISSUES","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"WARNING","characterName":"林岚","message":"m","evidence":[{"quote":" ","sourceKind":"CURRENT_DRAFT","sourceId":"d1","field":"content"}],"confidence":"EXPLICIT"}],"checkedCharacters":[],"missingInputs":[],"summary":"s"}
        """.trimIndent()
        val blankName = """
            {"status":"ISSUES","scope":"CURRENT_CHAPTER","findings":[{"type":"TRAIT","severity":"WARNING","characterName":" ","message":"m","evidence":[{"quote":"q","sourceKind":"CURRENT_DRAFT","sourceId":"d1","field":"content"}],"confidence":"EXPLICIT"}],"checkedCharacters":[],"missingInputs":[],"summary":"s"}
        """.trimIndent()
        val blankChecked = """{"status":"PASS","scope":"CURRENT_CHAPTER","findings":[],"checkedCharacters":[{"characterId":" ","name":" "}],"missingInputs":[],"summary":"s"}"""
        listOf(blankSummary, blankMessage, blankQuote, blankName, blankChecked).forEach { raw ->
            assertFailsWith<CharacterConsistencyException.InvalidOutput> {
                CharacterConsistencyParser.parse(raw)
            }
        }
    }

    @Test
    fun `optional evidence fields may be omitted`() {
        val raw = CharacterConsistencyParser.parse(
            """
            {
              "status":"ISSUES",
              "scope":"CURRENT_CHAPTER",
              "findings":[{
                "type":"TRAIT","severity":"WARNING","characterName":"林岚","message":"m",
                "evidence":[{"quote":"q","sourceKind":"CURRENT_DRAFT"}],
                "confidence":"EXPLICIT"
              }],
              "checkedCharacters":[],
              "missingInputs":[],
              "summary":"s"
            }
            """.trimIndent(),
        )
        assertEquals(null, raw.findings[0].evidence[0].sourceId)
        assertEquals(null, raw.findings[0].evidence[0].field)
        assertEquals(null, raw.findings[0].suggestion)
    }

    @Test
    fun `parser errors map to typed application errors`() {
        val ex = assertFailsWith<CharacterConsistencyException.InvalidOutput> {
            CharacterConsistencyParser.parse("")
        }
        val mapped = ErrorMapper.map(ex)
        assertIs<ApplicationError.InvalidCharacterConsistencyOutput>(mapped.error)
        assertTrue(mapped.error.detail.contains(CharacterConsistencyErrorCodes.INVALID_OUTPUT))
    }
}
