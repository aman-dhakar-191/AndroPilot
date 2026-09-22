package com.andropilot.host.agent

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class ModelCatalogTest {

    @Test
    fun `reads ids out of an OpenAI-shaped listing`() {
        val body = """
            {"object":"list","data":[
              {"id":"openai/gpt-5","object":"model"},
              {"id":"claude/claude-sonnet-4-6","object":"model"},
              {"id":"AndroPilot","object":"model"}
            ]}
        """.trimIndent()

        assertEquals(listOf("AndroPilot", "claude/claude-sonnet-4-6", "openai/gpt-5"), ModelCatalog.parse(body))
    }

    @Test
    fun `a bare array of names is still a listing`() {
        assertEquals(listOf("a", "b"), ModelCatalog.parse("""["b","a","b"]"""))
    }

    @Test
    fun `an endpoint that answers with something else yields nothing rather than throwing`() {
        // An endpoint without /models is ordinary, and it must not break the page: the
        // caller falls back to typing a name.
        assertEquals(emptyList<String>(), ModelCatalog.parse("<html>404</html>"))
        assertEquals(emptyList<String>(), ModelCatalog.parse("{}"))
        assertEquals(emptyList<String>(), ModelCatalog.parse(""))
    }
}
