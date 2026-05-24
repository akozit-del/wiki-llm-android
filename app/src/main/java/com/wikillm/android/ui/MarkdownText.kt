package com.wikillm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal Markdown renderer for chat answers — no extra dependency, keeps the
 * APK small. Handles headings (#/##/###), bullet and numbered lists, blank-line
 * paragraph spacing, and inline **bold** / `code`. Good enough for LLM output.
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier) {
    val lines = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (line in lines) {
            when (line) {
                is MdLine.Blank -> Text("", style = MaterialTheme.typography.bodySmall)
                is MdLine.Heading -> Text(
                    inline(line.text),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = when (line.level) { 1 -> 21.sp; 2 -> 18.sp; else -> 16.sp },
                )
                is MdLine.Bullet -> Row {
                    Text("•  ", color = color)
                    Text(inline(line.text), color = color)
                }
                is MdLine.Numbered -> Row {
                    Text("${line.num} ", color = color, fontWeight = FontWeight.Medium)
                    Text(inline(line.text), color = color)
                }
                is MdLine.Para -> Text(inline(line.text), color = color)
            }
        }
    }
}

private sealed interface MdLine {
    data object Blank : MdLine
    data class Heading(val level: Int, val text: String) : MdLine
    data class Bullet(val text: String) : MdLine
    data class Numbered(val num: String, val text: String) : MdLine
    data class Para(val text: String) : MdLine
}

private val NUMBERED = Regex("""^(\d{1,3}[.)])\s+(.*)""")

private fun parseMarkdown(src: String): List<MdLine> = src.split("\n").map { raw ->
    val line = raw.trim()
    val numbered = NUMBERED.matchEntire(line)
    when {
        line.isEmpty() -> MdLine.Blank
        line.startsWith("### ") -> MdLine.Heading(3, line.removePrefix("### "))
        line.startsWith("## ") -> MdLine.Heading(2, line.removePrefix("## "))
        line.startsWith("# ") -> MdLine.Heading(1, line.removePrefix("# "))
        line.startsWith("- ") -> MdLine.Bullet(line.removePrefix("- "))
        line.startsWith("* ") -> MdLine.Bullet(line.removePrefix("* "))
        numbered != null -> MdLine.Numbered(numbered.groupValues[1], numbered.groupValues[2])
        else -> MdLine.Para(line)
    }
}

/** Parse inline **bold** and `code` into a styled string. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                } else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                } else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}
