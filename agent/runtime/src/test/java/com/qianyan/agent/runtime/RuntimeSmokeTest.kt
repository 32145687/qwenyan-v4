package com.qianyan.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeSmokeTest {
    @Test
    fun `module compiles and runs a test`() {
        assertEquals(4, 2 + 2)
    }
}