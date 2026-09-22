package com.andropilot.host.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModelCatalogTest {

    @Test
    fun `a gateway's own groups arrive in the same listing as its models`() {
        // The whole reason this class exists: a combo is not a separate endpoint, it is an
        // entry in /v1/models distinguishable only by owned_by. Reading the list without
        // that distinction hides the one name somebody configured among hundreds.
        val body = """
            {"object":"list","data":[
              {"id":"aug/claude-sonnet-4.6","owned_by":"auggie","capabilities":{"tool_calling":true}},
              {"id":"AndroPilot","owned_by":"combo","capabilities":{"tool_calling":true}},
              {"id":"felo/felo-chat","owned_by":"felo-web","capabilities":{"tool_calling":false}}
            ]}
        """.trimIndent()

        val options = ModelCatalog.parse(body)

        // Combos first: a handful of deliberate names against hundreds of models.
        assertEquals("AndroPilot", options.first().id)
        assertEquals("combo", options.first().group)
        assertEquals("model", options.single { it.id == "aug/claude-sonnet-4.6" }.group)
    }

    @Test
    fun `an endpoint that says a model cannot call tools is believed`() {
        val body = """{"data":[{"id":"chat-only","capabilities":{"tool_calling":false}}]}"""
        assertEquals(false, ModelCatalog.parse(body).single().toolCalling)
    }

    @Test
    fun `tool calling is assumed when the endpoint does not say`() {
        // Refusing on silence would rule out every endpoint that publishes no capabilities.
        assertTrue(ModelCatalog.parse("""{"data":[{"id":"plain"}]}""").single().toolCalling)
    }

    @Test
    fun `a bare array of names is still a listing`() {
        assertEquals(listOf("a", "b"), ModelCatalog.parse("""["b","a","b"]""").map { it.id })
    }

    @Test
    fun `an endpoint that answers with something else yields nothing rather than throwing`() {
        assertEquals(emptyList<ModelOption>(), ModelCatalog.parse("<html>404</html>"))
        assertEquals(emptyList<ModelOption>(), ModelCatalog.parse("{}"))
        assertEquals(emptyList<ModelOption>(), ModelCatalog.parse(""))
        assertEquals(emptyList<ModelOption>(), ModelCatalog.parse(null))
    }
}
