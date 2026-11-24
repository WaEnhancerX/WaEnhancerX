package com.waenhancer.theme

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Compiles a theme stylesheet into rules using the WaEnhancer CustomView selector model, so a
 * theme written for WaEnhancer renders the same way in WAEX.
 *
 * A selector is a whitespace-separated chain of compounds:
 * - `.com_whatsapp_HomeActivity` (first only): scopes the rule to that Activity or a subclass.
 * - `#view_id` / `#android_id`: a host (or framework) view id, matched anywhere below the
 *   previous compound, or on the same view.
 * - `TextView`: a direct child of the previous compound whose class (or a superclass up to the
 *   first framework class) has that simple name. Framework widget names also look through
 *   intermediate containers that are not of that type.
 * - `:nth-child(n)` counts among same-type siblings; `:contains(text)` matches TextView text.
 *
 * Declarations stay as strings: the hook resolves units and colors against the view.
 */
object CssThemeRules {

    sealed class Part {
        abstract val pseudos: List<Pseudo>

        data class Id(val name: String, override val pseudos: List<Pseudo> = emptyList()) : Part()
        data class Type(val name: String, override val pseudos: List<Pseudo> = emptyList()) : Part()
    }

    sealed class Pseudo {
        data class NthChild(val position: Int) : Pseudo()
        data class Contains(val text: String) : Pseudo()
    }

    data class Rule(
        /** Source order; later rules win, as in the CSS cascade. */
        val order: Int,
        val activityClass: String?,
        val parts: List<Part>,
        val declarations: Map<String, String>
    ) {
        val leaf: Part get() = parts.last()
    }

    fun parse(css: String): List<Rule> {
        val clean = COMMENTS.replace(css, "")
        val blocks = RULE.findAll(clean).toList()
        // `:root { --accent: #ff5500; }` defines variables usable anywhere as var(--accent).
        val variables = HashMap<String, String>()
        blocks.filter { it.groupValues[1].trim() == ":root" }.forEach { block ->
            parseDeclarations(block.groupValues[2]).forEach { (key, value) ->
                if (key.startsWith("--")) variables[key] = value
            }
        }
        val rules = ArrayList<Rule>()
        blocks.forEach { block ->
            val selectorText = block.groupValues[1].trim()
            if (selectorText == ":root" || selectorText.startsWith("@")) return@forEach
            val declarations = parseDeclarations(block.groupValues[2])
                .mapValues { (_, value) -> resolveVariables(value, variables) }
                .filterKeys { !it.startsWith("--") }
            if (declarations.isEmpty()) return@forEach
            selectorText.split(',').forEach { raw ->
                parseSelector(raw.trim())?.let { (activity, parts) ->
                    rules += Rule(rules.size, activity, parts, declarations)
                }
            }
        }
        return rules
    }

    /** Returns the Activity scope and compound chain, or null when nothing targets a view. */
    fun parseSelector(raw: String): Pair<String?, List<Part>>? {
        if (raw.isBlank()) return null
        val tokens = tokenize(raw)
        var activity: String? = null
        val parts = ArrayList<Part>()
        tokens.forEachIndexed { index, token ->
            val compound = parseCompound(token) ?: return null
            when (compound) {
                is Compound.Activity -> if (index == 0) activity = compound.name
                is Compound.View -> parts += compound.part
            }
        }
        return if (parts.isEmpty()) null else activity to parts
    }

    private sealed class Compound {
        data class Activity(val name: String) : Compound()
        data class View(val part: Part) : Compound()
    }

    private fun parseCompound(token: String): Compound? {
        val pseudos = ArrayList<Pseudo>()
        var base = PSEUDO.replace(token) { match ->
            val argument = match.groupValues[2].trim().trim('"', '\'')
            when (match.groupValues[1].lowercase(Locale.ROOT)) {
                "nth-child" -> argument.toIntOrNull()?.takeIf { it > 0 }?.let { pseudos += Pseudo.NthChild(it) }
                "contains" -> if (argument.isNotEmpty()) pseudos += Pseudo.Contains(argument)
            }
            ""
        }
        // Other pseudo-classes (:hover, :focus...) have no meaning on Android views.
        base = base.substringBefore(':').trim()
        if (base.isEmpty()) return null
        val hash = base.indexOf('#')
        return when {
            hash >= 0 -> base.substring(hash + 1).substringBefore('.').takeIf(ID::matches)
                ?.let { Compound.View(Part.Id(it, pseudos)) }
            base.startsWith('.') -> base.substring(1).takeIf(String::isNotBlank)
                ?.let { Compound.Activity(it.replace('_', '.')) }
            else -> base.substringBefore('.').takeIf(TYPE::matches)
                ?.let { Compound.View(Part.Type(it, pseudos)) }
        }
    }

    /** Splits on whitespace and child combinators, keeping `:contains(two words)` intact. */
    private fun tokenize(raw: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var depth = 0
        raw.forEach { c ->
            when {
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> { depth = maxOf(0, depth - 1); current.append(c) }
                depth == 0 && (c.isWhitespace() || c == '>' || c == '+' || c == '~') -> {
                    if (current.isNotEmpty()) tokens += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    fun parseDeclarations(block: String): Map<String, String> = linkedMapOf<String, String>().apply {
        block.split(';').forEach { raw ->
            val separator = raw.indexOf(':')
            if (separator <= 0) return@forEach
            val key = raw.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = raw.substring(separator + 1).trim()
                .removeSuffix("!important").trim()
            if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
        }
    }

    private fun resolveVariables(value: String, variables: Map<String, String>): String {
        var resolved = value
        repeat(4) { // bounded depth: variables may reference other variables, but never loop forever
            if (!resolved.contains("var(", ignoreCase = true)) return resolved
            resolved = VAR.replace(resolved) { match ->
                variables[match.groupValues[1].lowercase(Locale.ROOT)] ?: match.groupValues[2].trim()
            }
        }
        return resolved
    }

    /**
     * Parses a CSS color into ARGB, or null when [raw] is not a literal color.
     * Eight-digit hex follows the Android `#AARRGGBB` order used by WaEnhancer themes.
     */
    fun parseColor(raw: String): Int? {
        val value = raw.trim().trimEnd(';').lowercase(Locale.ROOT)
        if (value == "transparent") return 0
        NAMED[value]?.let { return it }
        if (value.startsWith('#')) {
            val hex = value.substring(1)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
            return when (hex.length) {
                3 -> (0xFF000000 or expand(hex).toLong(16)).toInt()
                4 -> expand(hex).toLong(16).toInt() // #ARGB
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        }
        val function = RGB_FUNCTION.matchEntire(value) ?: return null
        val channels = function.groupValues[2].split(',', '/', ' ').map(String::trim).filter(String::isNotEmpty)
        if (channels.size !in 3..4) return null
        val rgb = channels.take(3).map { channel ->
            val number = channel.removeSuffix("%").toFloatOrNull() ?: return null
            (if (channel.endsWith('%')) number * 2.55f else number).roundToInt().coerceIn(0, 255)
        }
        val alpha = channels.getOrNull(3)?.let { channel ->
            val number = channel.removeSuffix("%").toFloatOrNull() ?: return null
            ((if (channel.endsWith('%')) number / 100f else number) * 255f).roundToInt().coerceIn(0, 255)
        } ?: 255
        return (alpha shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }

    private fun expand(hex: String): String = buildString { hex.forEach { append(it).append(it) } }

    private val COMMENTS = Regex("(?s)/\\*.*?\\*/")
    private val RULE = Regex("(?s)([^{}]+)\\{([^{}]*)\\}")
    private val VAR = Regex("(?i)var\\(\\s*(--[a-z0-9_-]+)\\s*(?:,([^)]*))?\\)")
    private val PSEUDO = Regex("(?i):([a-z-]+)\\(([^)]*)\\)")
    private val RGB_FUNCTION = Regex("(rgba?)\\((.*)\\)")
    private val ID = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val TYPE = Regex("[A-Za-z_][A-Za-z0-9_$]*")
    private val NAMED = mapOf(
        "black" to 0xFF000000.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt()
    )
}
