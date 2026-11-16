package com.waenhancer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AptoideVersionRepositoryTest {

    @Test
    fun `parses version names from a listAppVersions response`() {
        val json = """
            {"info":{"status":"OK"},"list":[
              {"package":"com.whatsapp","file":{"vername":"2.26.45.71","vercode":264507149}},
              {"package":"com.whatsapp","file":{"vername":"2.26.45.70","vercode":264507049}},
              {"package":"com.whatsapp","file":{"vername":"2.26.45.70","vercode":1}},
              {"package":"com.whatsapp","file":{"vercode":5}},
              {"package":"com.whatsapp"}
            ]}
        """.trimIndent()
        assertEquals(listOf("2.26.45.71", "2.26.45.70"), AptoideVersionRepository.parseVersionNames(json))
    }

    @Test
    fun `malformed or empty responses give an empty list`() {
        assertTrue(AptoideVersionRepository.parseVersionNames("").isEmpty())
        assertTrue(AptoideVersionRepository.parseVersionNames("not json").isEmpty())
        assertTrue(AptoideVersionRepository.parseVersionNames("""{"list":"oops"}""").isEmpty())
        assertTrue(AptoideVersionRepository.parseVersionNames("""{"info":{}}""").isEmpty())
    }
}
