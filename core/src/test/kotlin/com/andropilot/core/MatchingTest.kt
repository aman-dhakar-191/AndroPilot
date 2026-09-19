package com.andropilot.core

import com.andropilot.core.model.ElementRole
import com.andropilot.core.selector.AmbiguityPolicy
import com.andropilot.core.selector.ElementMatcher
import com.andropilot.core.selector.MatchResult
import com.andropilot.core.selector.ScreenRegion
import com.andropilot.core.selector.Selector
import com.andropilot.core.selector.TextMatch
import com.andropilot.core.selector.TextScoring
import com.andropilot.core.testing.FakeScreens
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class MatchingTest {

    private val login = FakeScreens.login().toSnapshot()

    @Nested
    @DisplayName("text scoring")
    inner class Scoring {

        @Test
        fun `identical strings score 1`() {
            assertEquals(1.0, TextScoring.similarity("Sign in", "Sign in"))
        }

        @Test
        fun `case and punctuation differences do not matter`() {
            assertTrue(TextScoring.similarity("Sign in", "SIGN IN") > 0.99)
            assertTrue(TextScoring.similarity("Forgot password?", "Forgot password") > 0.99)
        }

        @Test
        fun `ampersand is expanded so Terms & Conditions matches Terms and Conditions`() {
            assertTrue(TextScoring.similarity("Terms & Conditions", "Terms and Conditions") > 0.95)
        }

        @Test
        fun `a trailing ellipsis is ignored`() {
            assertTrue(TextScoring.similarity("Loading", "Loading…") > 0.95)
        }

        @Test
        fun `a whole-word prefix scores high but below exact`() {
            val score = TextScoring.similarity("Sign in", "Sign in with Google")
            assertTrue(score in 0.9..0.96) { "prefix score was $score" }
        }

        @Test
        fun `unrelated strings score low`() {
            assertTrue(TextScoring.similarity("Sign in", "Delete account") < 0.4)
        }

        @Test
        fun `single character typos stay recognisable`() {
            assertTrue(TextScoring.similarity("Settings", "Settngs") > 0.8)
        }
    }

    @Test
    fun `finds a button by fuzzy text`() {
        val result = ElementMatcher.find(login, Selector.text("sign in"))
        val matched = assertInstanceOf<MatchResult.Matched>(result)
        assertEquals("submit", matched.candidate.element.id)
    }

    @Test
    fun `finds a field by its hint`() {
        val result = ElementMatcher.find(login, Selector(text = "password", editable = true))
        val matched = assertInstanceOf<MatchResult.Matched>(result)
        assertEquals("pass", matched.candidate.element.id)
    }

    @Test
    fun `resource id matches with or without the package prefix`() {
        val withPrefix = ElementMatcher.find(login, Selector.id("com.example.shop:id/sign_in"))
        val bare = ElementMatcher.find(login, Selector.id("sign_in"))
        assertEquals(
            assertInstanceOf<MatchResult.Matched>(withPrefix).candidate.element.id,
            assertInstanceOf<MatchResult.Matched>(bare).candidate.element.id,
        )
    }

    @Test
    fun `boolean constraints are hard filters, not preferences`() {
        // "Sign in" is the best text match but it is not editable, so it must not win.
        val result = ElementMatcher.find(login, Selector(text = "Sign in", editable = true))
        assertInstanceOf<MatchResult.NotFound>(result)
    }

    @Test
    fun `a role constraint excludes better-scoring elements of the wrong role`() {
        val result = ElementMatcher.find(
            login,
            Selector(text = "Welcome back", role = ElementRole.BUTTON),
        )
        assertInstanceOf<MatchResult.NotFound>(result)
    }

    @Test
    fun `duplicate labels are reported as ambiguous rather than guessed`() {
        val screen = FakeScreens.duplicateButtons().toSnapshot()
        val result = ElementMatcher.find(screen, Selector.text("Choose"))
        val ambiguous = assertInstanceOf<MatchResult.Ambiguous>(result)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `region disambiguates duplicates`() {
        val screen = FakeScreens.duplicateButtons().toSnapshot()
        val result = ElementMatcher.find(
            screen,
            Selector(text = "Choose", region = ScreenRegion.LEFT),
        )
        assertEquals("buy_a", assertInstanceOf<MatchResult.Matched>(result).candidate.element.id)
    }

    @Test
    fun `near disambiguates duplicates by proximity to an anchor`() {
        val screen = FakeScreens.duplicateButtons().toSnapshot()
        val result = ElementMatcher.find(
            screen,
            Selector(text = "Choose", near = Selector.text("Pro")),
        )
        assertEquals("buy_b", assertInstanceOf<MatchResult.Matched>(result).candidate.element.id)
    }

    @Test
    fun `index picks among equally ranked candidates in reading order`() {
        val screen = FakeScreens.duplicateButtons().toSnapshot()
        val first = ElementMatcher.find(screen, Selector(text = "Choose", index = 0))
        val second = ElementMatcher.find(screen, Selector(text = "Choose", index = 1))
        assertEquals("buy_a", assertInstanceOf<MatchResult.Matched>(first).candidate.element.id)
        assertEquals("buy_b", assertInstanceOf<MatchResult.Matched>(second).candidate.element.id)
    }

    @Test
    fun `the FIRST policy resolves ambiguity instead of failing`() {
        val screen = FakeScreens.duplicateButtons().toSnapshot()
        val result = ElementMatcher.find(
            screen,
            Selector(text = "Choose", onAmbiguity = AmbiguityPolicy.FIRST),
        )
        assertInstanceOf<MatchResult.Matched>(result)
    }

    @Test
    fun `within restricts the search to a subtree`() {
        val dialog = FakeScreens.confirmDialog().toSnapshot()
        val result = ElementMatcher.find(
            dialog,
            Selector(text = "Cancel", within = Selector.role(ElementRole.DIALOG)),
        )
        assertEquals("cancel", assertInstanceOf<MatchResult.Matched>(result).candidate.element.id)
    }

    @Test
    fun `an exact text selector rejects a near miss`() {
        val result = ElementMatcher.find(
            login,
            Selector(exactText = "Sign In", textMatch = TextMatch.EXACT),
        )
        assertInstanceOf<MatchResult.NotFound>(result)
    }

    @Test
    fun `not found reports near misses so an agent can refine`() {
        val result = ElementMatcher.find(login, Selector(text = "Sign out", minScore = 0.9))
        val notFound = assertInstanceOf<MatchResult.NotFound>(result)
        assertTrue(notFound.nearMisses.isNotEmpty())
        assertEquals("submit", notFound.nearMisses.first().element.id)
    }

    @Test
    fun `an empty selector never matches anything`() {
        assertInstanceOf<MatchResult.NotFound>(ElementMatcher.find(login, Selector()))
    }

    @Test
    fun `disabled elements are ranked below enabled ones`() {
        val screen = FakeScreens.login()
        screen.update("submit") { it.copy(enabled = false) }
        screen.add(
            com.andropilot.core.testing.Ui.button(
                "submit2",
                "Sign in",
                com.andropilot.core.model.Bounds(60, 1300, 1020, 1400),
            ),
        )
        screen.update("root") { it.copy(childIds = it.childIds + "submit2") }
        val result = ElementMatcher.find(screen.toSnapshot(), Selector.text("Sign in"))
        assertEquals("submit2", assertInstanceOf<MatchResult.Matched>(result).candidate.element.id)
    }

    @Test
    fun `findAll returns every passing candidate, best first`() {
        val screen = FakeScreens.duplicateButtons().toSnapshot()
        val all = ElementMatcher.findAll(screen, Selector.text("Choose"))
        assertEquals(2, all.size)
        assertTrue(all[0].score >= all[1].score)
    }

    @Test
    fun `invisible elements are excluded by default`() {
        val screen = FakeScreens.login()
        screen.update("submit") { it.copy(visible = false) }
        assertInstanceOf<MatchResult.NotFound>(
            ElementMatcher.find(screen.toSnapshot(), Selector.text("Sign in")),
        )
        assertFalse(
            ElementMatcher.find(
                screen.toSnapshot(),
                Selector(text = "Sign in", visibleOnly = false),
            ) is MatchResult.NotFound,
        )
    }
}
