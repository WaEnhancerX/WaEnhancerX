package com.waenhancer.theme

import com.waenhancer.theme.CssThemeRules.Part
import com.waenhancer.theme.CssThemeRules.Pseudo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CssThemeRulesTest {

    @Test
    fun activityScopedChainKeepsEveryCompound() {
        val rules = CssThemeRules.parse(
            ".com_whatsapp_HomeActivity #root TextView:nth-child(2) #title { color: #fff; }"
        )
        val rule = rules.single()
        assertEquals("com.whatsapp.HomeActivity", rule.activityClass)
        assertEquals(
            listOf(
                Part.Id("root"),
                Part.Type("TextView", listOf(Pseudo.NthChild(2))),
                Part.Id("title")
            ),
            rule.parts
        )
        assertEquals("#fff", rule.declarations["color"])
    }

    @Test
    fun selectorListsSplitIntoOrderedRules() {
        val rules = CssThemeRules.parse("#a, #b { display: none } #c { alpha: 0.5 }")
        assertEquals(listOf(0, 1, 2), rules.map { it.order })
        assertEquals(listOf("a", "b", "c"), rules.map { (it.leaf as Part.Id).name })
    }

    @Test
    fun containsMayHoldSpacesAndChildCombinatorsAreIgnored() {
        val (_, parts) = CssThemeRules.parseSelector("#list > TextView:contains(Meta AI)")!!
        assertEquals(listOf(Part.Id("list"), Part.Type("TextView", listOf(Pseudo.Contains("Meta AI")))), parts)
    }

    @Test
    fun activityOnlySelectorTargetsNothing() {
        assertNull(CssThemeRules.parseSelector(".com_whatsapp_HomeActivity"))
        assertTrue(CssThemeRules.parse(".com_whatsapp_HomeActivity { color: red }").isEmpty())
    }

    @Test
    fun rootVariablesAndImportantAreResolved() {
        val css = """
            /* name = Demo
               change_dpi = 400 */
            :root { --accent: #ff5500; }
            #fab { background-color: var(--accent) !important; color: var(--missing, #000); }
        """.trimIndent()
        val declarations = CssThemeRules.parse(css).single().declarations
        assertEquals("#ff5500", declarations["background-color"])
        assertEquals("#000", declarations["color"])
        assertEquals("400", ThemeHeader.properties(css)["change_dpi"])
    }

    @Test
    fun twoColorBackgroundIsKeptForReplacement() {
        val rule = CssThemeRules.parse("#bubble { background-color: #00a884 #3b82f6 }").single()
        assertEquals("#00a884 #3b82f6", rule.declarations["background-color"])
    }

    @Test
    fun parsesCssColorForms() {
        assertEquals(0xFFFFFFFF.toInt(), CssThemeRules.parseColor("#fff"))
        assertEquals(0xFF00A884.toInt(), CssThemeRules.parseColor("#00A884"))
        assertEquals(0x8000A884.toInt(), CssThemeRules.parseColor("#8000a884"))
        assertEquals(0x80FF0000.toInt(), CssThemeRules.parseColor("rgba(255, 0, 0, 0.5)"))
        assertEquals(0xFF0080FF.toInt(), CssThemeRules.parseColor("rgb(0 128 255)"))
        assertEquals(0, CssThemeRules.parseColor("transparent"))
        assertNull(CssThemeRules.parseColor("12dp"))
        assertNull(CssThemeRules.parseColor("#12345"))
    }
}
