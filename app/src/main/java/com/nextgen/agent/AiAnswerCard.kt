package com.nextgen.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

sealed class MarkdownSegment {
    data class Code(val lang: String, val code: String) : MarkdownSegment()
    data class Text(val content: String) : MarkdownSegment()
}

/** Parses raw markdown text into code blocks and rich text segments */
fun parseMarkdownSegments(raw: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val codeBlockRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n?([\\s\\S]*?)```")
    var lastIndex = 0
    for (match in codeBlockRegex.findAll(raw)) {
        if (match.range.first > lastIndex) {
            val textBefore = raw.substring(lastIndex, match.range.first).trim()
            if (textBefore.isNotEmpty()) {
                segments.add(MarkdownSegment.Text(textBefore))
            }
        }
        val lang = match.groupValues[1].ifBlank { "CODE" }.uppercase(Locale.ROOT)
        val code = match.groupValues[2].trim()
        segments.add(MarkdownSegment.Code(lang, code))
        lastIndex = match.range.last + 1
    }
    if (lastIndex < raw.length) {
        val remaining = raw.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) {
            segments.add(MarkdownSegment.Text(remaining))
        }
    }
    if (segments.isEmpty() && raw.isNotBlank()) {
        segments.add(MarkdownSegment.Text(raw.trim()))
    }
    return segments
}

/** Converts inline bold (**text**) and inline code (`code`) into Compose AnnotatedString */
fun buildAnnotatedMarkdown(raw: String, isDark: Boolean): AnnotatedString {
    return buildAnnotatedString {
        val regex = Regex("(\\*{2}(.+?)\\*{2})|(`(.+?)`)")
        var lastIdx = 0
        for (match in regex.findAll(raw)) {
            if (match.range.first > lastIdx) {
                append(raw.substring(lastIdx, match.range.first))
            }
            val boldContent = match.groupValues[2]
            val codeContent = match.groupValues[4]
            if (boldContent.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldContent)
                }
            } else if (codeContent.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = if (isDark) Color(0xFF334155).copy(alpha = 0.55f) else Color(0xFFE2E8F0),
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
                    )
                ) {
                    append(" $codeContent ")
                }
            }
            lastIdx = match.range.last + 1
        }
        if (lastIdx < raw.length) {
            append(raw.substring(lastIdx))
        }
    }
}

/**
 * State-of-the-art AI Answer Card displaying API responses efficiently with:
 * - Syntax-highlighted code blocks with dedicated Copy Code buttons
 * - Section headings, bullet points, and numbered steps
 * - Inline bold and monospace formatting
 * - Text-To-Speech listen & stop controls with dynamic audio wave
 * - One-tap full copy & native Android sharing
 * - Contextual follow-up suggestions (Translate, Simplify, Google Search)
 */
@Composable
fun AiAnswerCard(
    msg: Msg,
    activity: MainActivity,
    isDark: Boolean,
    onFollowUp: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val segments = remember(msg.text) { parseMarkdownSegments(msg.text) }
    var isExpanded by remember { mutableStateOf(false) }
    val isLongResponse = remember(msg.text) { msg.text.length > 900 || msg.text.lines().size > 22 }

    val isSpeakingThis = activity.currentlySpeakingText == (msg.spokenSummary.ifBlank { msg.text })

    fun copyText(str: String, toastMsg: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Answer", str))
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Left: Futuristic Holographic Sparkle Avatar
        Surface(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(34.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.3.dp, Brush.linearGradient(listOf(Color(0xFF00E5FF), Color(0xFF8B5CF6)))),
            color = if (isDark) Color(0xFF131A2B) else Color(0xFFEFF3FA),
            shadowElevation = 4.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                GeminiSparkle(size = 20.dp)
            }
        }

        Spacer(Modifier.width(10.dp))

        // Main Answer Card Column
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 4.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
                color = if (msg.isError) MaterialTheme.colorScheme.errorContainer
                        else if (isDark) Color(0xFF0E1322)
                        else Color(0xFFFFFFFF),
                tonalElevation = 3.dp,
                border = if (msg.isError) {
                    BorderStroke(1.2.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                } else if (isDark) {
                    BorderStroke(1.2.dp, Brush.horizontalGradient(listOf(Color(0xFF00E5FF).copy(alpha = 0.45f), Color(0xFF8B5CF6).copy(alpha = 0.45f))))
                } else {
                    BorderStroke(1.2.dp, Brush.horizontalGradient(listOf(Color(0xFF4F46E5).copy(alpha = 0.35f), Color(0xFF00E5FF).copy(alpha = 0.35f))))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    // Futuristic Header tag: Model identity & Time
                    if (msg.isAiResponse) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.5.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF))
                                )
                                Text(
                                    text = "[ AI CORE // ${(activity.cfg.model.ifBlank { "Aero Fenix" }).uppercase(Locale.ROOT)} ]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFF00E5FF) else Color(0xFF4F46E5)
                                )
                            }
                            if (msg.time.isNotBlank()) {
                                Text(
                                    text = "[ ${msg.time} ]",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }

                    // Content Rendering
                    val displayedSegments = if (isLongResponse && !isExpanded) {
                        segments.take(2)
                    } else {
                        segments
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        displayedSegments.forEach { segment ->
                            when (segment) {
                                is MarkdownSegment.Code -> {
                                    // Cyber Monospace Code Container with Language Badge & Copy Button
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isDark) Color(0xFF0F172A) else Color(0xFF1E293B),
                                        border = BorderStroke(1.dp, GeminiBlue.copy(alpha = 0.35f)),
                                        shadowElevation = 4.dp,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column {
                                            // Code Block Top Bar
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(if (isDark) Color(0xFF1E293B) else Color(0xFF334155))
                                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Code,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp),
                                                        tint = GeminiCyan
                                                    )
                                                    Text(
                                                        text = segment.lang,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFE2E8F0)
                                                    )
                                                }

                                                // One-tap Copy Code Button
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = GeminiBlue.copy(alpha = 0.25f),
                                                    border = BorderStroke(0.8.dp, GeminiBlue.copy(alpha = 0.6f)),
                                                    modifier = Modifier.clickable {
                                                        copyText(segment.code, activity.tr("code_copied"))
                                                    }
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ContentCopy,
                                                            contentDescription = "Copy code",
                                                            modifier = Modifier.size(11.dp),
                                                            tint = Color.White
                                                        )
                                                        Text(
                                                            text = activity.tr("copy_code"),
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White
                                                        )
                                                    }
                                                }
                                            }

                                            // Code Text Body
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState())
                                                    .padding(12.dp)
                                            ) {
                                                Text(
                                                    text = segment.code,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    lineHeight = 18.5.sp,
                                                    color = Color(0xFFF8FAFC)
                                                )
                                            }
                                        }
                                    }
                                }

                                is MarkdownSegment.Text -> {
                                    // Rich Text Block: Headings, Bullets, Bold
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        segment.content.lines().forEach { rawLine ->
                                            val line = rawLine.trim()
                                            when {
                                                line.startsWith("### ") -> {
                                                    Text(
                                                        text = line.removePrefix("### ").trim(),
                                                        fontSize = 15.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Indigo
                                                    )
                                                }
                                                line.startsWith("## ") -> {
                                                    Text(
                                                        text = line.removePrefix("## ").trim(),
                                                        fontSize = 16.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = GeminiPurple
                                                    )
                                                }
                                                line.startsWith("# ") -> {
                                                    Text(
                                                        text = line.removePrefix("# ").trim(),
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = GeminiBlue
                                                    )
                                                }
                                                line.startsWith("* ") || line.startsWith("- ") || line.startsWith("• ") -> {
                                                    val bulletContent = line.replaceFirst(Regex("^[*\\-•]\\s*"), "")
                                                    Row(
                                                        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                                                        verticalAlignment = Alignment.Top,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .padding(top = 7.dp)
                                                                .size(5.dp)
                                                                .clip(CircleShape)
                                                                .background(GeminiCyan)
                                                        )
                                                        Text(
                                                            text = buildAnnotatedMarkdown(bulletContent, isDark),
                                                            fontSize = 14.5.sp,
                                                            lineHeight = 20.sp,
                                                            color = if (msg.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                                line.matches(Regex("^\\d+\\.\\s*.*")) -> {
                                                    val numMatch = Regex("^(\\d+)\\.\\s*(.*)").find(line)
                                                    val num = numMatch?.groupValues?.get(1) ?: "1"
                                                    val numContent = numMatch?.groupValues?.get(2) ?: line
                                                    Row(
                                                        modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                                                        verticalAlignment = Alignment.Top,
                                                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                                                    ) {
                                                        Surface(
                                                            shape = CircleShape,
                                                            color = Indigo.copy(alpha = 0.18f),
                                                            modifier = Modifier.padding(top = 2.dp).size(18.dp)
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Text(
                                                                    text = num,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Indigo
                                                                )
                                                            }
                                                        }
                                                        Text(
                                                            text = buildAnnotatedMarkdown(numContent, isDark),
                                                            fontSize = 14.5.sp,
                                                            lineHeight = 20.sp,
                                                            color = if (msg.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                                line.isNotBlank() -> {
                                                    Text(
                                                        text = buildAnnotatedMarkdown(line, isDark),
                                                        fontSize = 14.5.sp,
                                                        lineHeight = 21.sp,
                                                        color = if (msg.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Expand / Collapse Toggle for Long Responses
                    if (isLongResponse) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { isExpanded = !isExpanded }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = GeminiBlue
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = if (isExpanded) activity.tr("show_less") else activity.tr("read_more"),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = GeminiBlue
                            )
                        }
                    }

                    // Action Execution Chips (if commands executed)
                    if (msg.actions.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            msg.actions.forEach { act ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Indigo.copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Indigo.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Bolt, null, Modifier.size(13.dp), tint = Indigo)
                                        Text(act, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Indigo)
                                    }
                                }
                            }
                        }
                    }

                    // Interactive Toolbar: Audio Speak/Stop + Copy All + Share
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left: Voice Audio Control Button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSpeakingThis) Color(0xFFEF4444).copy(alpha = 0.15f) else GeminiBlue.copy(alpha = 0.12f),
                            border = BorderStroke(
                                1.dp,
                                if (isSpeakingThis) Color(0xFFEF4444).copy(alpha = 0.4f) else GeminiBlue.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.clickable {
                                activity.speakText(msg.spokenSummary.ifBlank { msg.text })
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                    contentDescription = "Speak",
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSpeakingThis) Color(0xFFEF4444) else GeminiBlue
                                )
                                Text(
                                    text = if (isSpeakingThis) activity.tr("stop_speech") else activity.tr("listen_speech"),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSpeakingThis) Color(0xFFEF4444) else GeminiBlue
                                )
                                if (isSpeakingThis) {
                                    EqualizerWave()
                                }
                            }
                        }

                        // Right: Copy Full Answer & Native Share
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.clickable {
                                    copyText(msg.text, activity.tr("copy_done"))
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy all",
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = activity.tr("copy_done"),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.clickable {
                                    activity.shareText(msg.text)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                    Text(
                                        text = activity.tr("share_response"),
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Smart Follow-up Suggestion Chips (Under AI Answers)
            if (msg.isAiResponse && !msg.isError) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Chip 1: Translate to Tamil / English
                    val transPrompt = if (activity.cfg.lang == "ta-IN") "இதை தமிழில் விளக்கு" else "Explain this in Tamil"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Magenta.copy(alpha = 0.12f),
                        border = BorderStroke(0.6.dp, Magenta.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { onFollowUp(transPrompt) }
                    ) {
                        Text(
                            text = "🌐 " + activity.tr("translate_tamil"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Magenta,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Chip 2: Simplify / summarize
                    val simplifyPrompt = if (activity.cfg.lang == "ta-IN") "இதை சுருக்கமாக சொல்" else "Explain this more simply"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Amber.copy(alpha = 0.12f),
                        border = BorderStroke(0.6.dp, Amber.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable { onFollowUp(simplifyPrompt) }
                    ) {
                        Text(
                            text = "💡 " + activity.tr("explain_simple"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Amber,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Chip 3: Search Google
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Cyan.copy(alpha = 0.12f),
                        border = BorderStroke(0.6.dp, Cyan.copy(alpha = 0.35f)),
                        modifier = Modifier.clickable {
                            val q = msg.text.take(70).replace(Regex("[\\n\\r*`#]+"), " ").trim()
                            onFollowUp(if (activity.cfg.lang == "ta-IN") "Google-ல $q தேடு" else "Search Google for $q")
                        }
                    ) {
                        Text(
                            text = "🔍 " + activity.tr("search_google"),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Cyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
