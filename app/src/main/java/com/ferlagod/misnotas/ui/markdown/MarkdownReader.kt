// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ferlagod
// Mis Notas — https://github.com/MisApps-labs/mis_notas

package com.ferlagod.misnotas.ui.markdown

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.ferlagod.misnotas.R
import coil.compose.AsyncImage
import com.ferlagod.misnotas.data.attachment.AttachmentStore
import com.ferlagod.misnotas.ui.theme.AccentGreen

/**
 * Bloques Markdown reconocidos por el lector. Subconjunto de CommonMark/GFM pensado para notas.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Task(val lineIndex: Int, val checked: Boolean, val text: String, val indent: Int) : MdBlock
    data class Bullet(val text: String, val indent: Int) : MdBlock
    data class Numbered(val number: String, val text: String, val indent: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Image(val start: Int, val end: Int, val alt: String, val source: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
    data object Blank : MdBlock
}

private val TASK_REGEX = Regex("""^(\s*)[-*+] \[( |x|X)] (.*)$""")
private val BULLET_REGEX = Regex("""^(\s*)[-*+] (.*)$""")
private val NUMBERED_REGEX = Regex("""^(\s*)(\d{1,9})[.)] (.*)$""")
private val HEADING_REGEX = Regex("""^(#{1,6}) (.*)$""")
private val INLINE_IMAGE_REGEX = Regex("""!\[[^\]]*]\((?:[^()\s]|\([^()\s]*\))*\)""")
private val INLINE_LINK_REGEX = Regex("""\[([^\]]+)]\((?:[^()\s]|\([^()\s]*\))*\)""")
private val INLINE_LINK_WITH_URL_REGEX = Regex("""\[([^\]]+)]\(((?:[^()\s]|\([^()\s]*\))*)\)""")
private val RULE_REGEX = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun lineStartOffsets(markdown: String, lines: List<String>): IntArray {
    val starts = IntArray(lines.size)
    var pos = 0
    for (idx in lines.indices) {
        starts[idx] = pos
        pos += lines[idx].length
        if (pos < markdown.length) {
            if (markdown[pos] == '\r') pos++
            if (pos < markdown.length && markdown[pos] == '\n') pos++
        }
    }
    return starts
}

internal fun parseBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.lines()
    val lineStarts = lineStartOffsets(markdown, lines)
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MdBlock.Code(code.toString())
            }
            line.isBlank() -> blocks += MdBlock.Blank
            line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                val header = tableCells(line)
                val rows = mutableListOf<List<String>>()
                i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                    rows += tableCells(lines[i]).let { cells ->
                        List(header.size) { idx -> cells.getOrElse(idx) { "" } }
                    }
                    i++
                }
                i--
                blocks += MdBlock.Table(header, rows)
            }
            RULE_REGEX.matches(line) -> blocks += MdBlock.Rule
            HEADING_REGEX.matches(line) -> {
                val m = HEADING_REGEX.find(line)!!
                blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            TASK_REGEX.matches(line) -> {
                val m = TASK_REGEX.find(line)!!
                blocks += MdBlock.Task(i, m.groupValues[2].isNotBlank(), m.groupValues[3], m.groupValues[1].length / 2)
            }
            BULLET_REGEX.matches(line) -> {
                val m = BULLET_REGEX.find(line)!!
                blocks += MdBlock.Bullet(m.groupValues[2], m.groupValues[1].length / 2)
            }
            NUMBERED_REGEX.matches(line) -> {
                val m = NUMBERED_REGEX.find(line)!!
                blocks += MdBlock.Numbered(m.groupValues[2], m.groupValues[3], m.groupValues[1].length / 2)
            }
            line.startsWith(">") -> blocks += MdBlock.Quote(line.removePrefix(">").trimStart())
            line.startsWith("    ") || line.startsWith("\t") -> blocks += MdBlock.Code(line.trim())
            else -> {
                val imageTokens = InlineMarkdown.parse(line).filter { it.kind == InlineKind.IMAGE }
                if (imageTokens.isEmpty()) {
                    blocks += MdBlock.Paragraph(line)
                } else {
                    var cursor = 0
                    val lineStart = lineStarts[i]
                    for (token in imageTokens) {
                        if (token.start > cursor) {
                            val before = line.substring(cursor, token.start).trim()
                            if (before.isNotEmpty()) blocks += MdBlock.Paragraph(before)
                        }
                        blocks += MdBlock.Image(
                            start = lineStart + token.start,
                            end = lineStart + token.end,
                            alt = line.substring(token.contentStart, token.contentEnd),
                            source = token.url.orEmpty()
                        )
                        cursor = token.end
                    }
                    if (cursor < line.length) {
                        val after = line.substring(cursor).trim()
                        if (after.isNotEmpty()) blocks += MdBlock.Paragraph(after)
                    }
                }
            }
        }
        i++
    }
    return blocks
}

/** Alterna la casilla de la línea [lineIndex] (`- [ ]` <-> `- [x]`). */
fun toggleTaskLine(markdown: String, lineIndex: Int): String {
    val lines = markdown.lines().toMutableList()
    if (lineIndex !in lines.indices) return markdown
    val m = TASK_REGEX.find(lines[lineIndex]) ?: return markdown
    val checked = m.groupValues[2].isNotBlank()
    val marker = if (checked) "[ ]" else "[x]"
    lines[lineIndex] = lines[lineIndex].replaceFirst(Regex("""\[( |x|X)]"""), Regex.escapeReplacement(marker))
    return lines.joinToString("\n")
}

/** Resuelve el origen de una imagen Markdown a un modelo que entiende Coil. */
@Composable
fun rememberImageModel(source: String): Any? {
    val context = LocalContext.current
    return remember(source) {
        // La indicación de tamaño (`#w=50`) no forma parte de la dirección de la imagen.
        val address = ImageSizing.stripSize(source)
        when {
            address.startsWith(AttachmentStore.SCHEME) ->
                AttachmentStore.fileFor(context, address.removePrefix(AttachmentStore.SCHEME))
            address.startsWith("data:image/") || address.startsWith("https://") || address.startsWith("http://") -> address
            else -> null
        }
    }
}

/**
 * Renderizador de Markdown nativo para el modo lectura.
 */
@Composable
fun MarkdownReader(
    markdownText: String,
    primaryBlue: Color,
    onToggleChecklist: (lineIndex: Int) -> Unit,
    onImageClick: ((Any) -> Unit)? = null,
    /** Pulsación larga en una imagen (para cambiar su tamaño): rango [start, end) y su dirección. */
    onImageLongClick: ((start: Int, end: Int, source: String) -> Unit)? = null,
    /** Pulsación en un enlace `[[nota]]`: recibe el título de destino. */
    onOpenNoteLink: ((String) -> Unit)? = null
) {
    val blocks = remember(markdownText) { parseBlocks(markdownText) }
    val linkColor = primaryBlue

    // Los enlaces `attachment://` abren el fichero adjunto; el resto, el navegador.
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val systemHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val currentText by androidx.compose.runtime.rememberUpdatedState(markdownText)
    val currentNoteLink by androidx.compose.runtime.rememberUpdatedState(onOpenNoteLink)
    val uriHandler = remember(systemHandler) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith(NoteLinks.SCHEME)) {
                    currentNoteLink?.invoke(uri.removePrefix(NoteLinks.SCHEME))
                } else if (uri.startsWith(com.ferlagod.misnotas.data.attachment.AttachmentStore.SCHEME)) {
                    val id = uri.removePrefix(com.ferlagod.misnotas.data.attachment.AttachmentStore.SCHEME).substringBefore('#')
                    val name = com.ferlagod.misnotas.data.attachment.AttachmentStore.fileNames(currentText)[id]
                    scope.launch { com.ferlagod.misnotas.util.AttachmentOpener.open(context, id, name) }
                } else {
                    runCatching { systemHandler.openUri(uri) }
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides uriHandler) {
    Column(modifier = Modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> Text(
                    text = inline(block.text, linkColor),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                        2 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = if (block.level == 1) 14.dp else 10.dp, bottom = 4.dp)
                )

                MdBlock.Rule -> HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                is MdBlock.Quote -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(primaryBlue, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = inline(block.text, linkColor),
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is MdBlock.Task -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleChecklist(block.lineIndex) }
                        .padding(start = (block.indent * 20).dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = block.checked,
                        onCheckedChange = { onToggleChecklist(block.lineIndex) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = AccentGreen,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = inline(block.text, linkColor),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = if (block.checked) TextDecoration.LineThrough else TextDecoration.None
                        ),
                        color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }

                is MdBlock.Bullet -> ListRow(marker = "•", indent = block.indent, markerColor = primaryBlue) {
                    Text(inline(block.text, linkColor), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }

                is MdBlock.Numbered -> ListRow(marker = "${block.number}.", indent = block.indent, markerColor = primaryBlue) {
                    Text(inline(block.text, linkColor), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }

                is MdBlock.Code -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = block.text,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(10.dp)
                    )
                }

                is MdBlock.Image -> {
                    val model = rememberImageModel(block.source)
                    if (model != null) {
                        NoteImage(
                            model = model,
                            contentDescription = block.alt.ifBlank { null },
                            widthPercent = ImageSizing.widthPercent(block.source),
                            onClick = onImageClick?.let { click -> { click(model) } },
                            onLongClick = onImageLongClick?.let { longClick -> { longClick(block.start, block.end, block.source) } }
                        )
                    }
                }

                is MdBlock.Paragraph -> Text(
                    text = inline(block.text, linkColor),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 3.dp)
                )

                MdBlock.Blank -> Spacer(modifier = Modifier.height(8.dp))

                is MdBlock.Table -> MarkdownTable(block, linkColor)
            }
        }
    }
    }
}

@Composable
fun NoteImage(
    model: Any,
    contentDescription: String?,
    widthPercent: Int,
    modifier: Modifier = Modifier,
    /** Alto máximo, como fracción del alto de la pantalla. */
    maxHeightFraction: Float = 0.75f,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * maxHeightFraction).dp
    // Las imágenes de internet no se descargan solas: avisarían al servidor de que se ha abierto
    // la nota (y desde qué IP). Se cargan solo si el usuario lo pide, una vez por imagen.
    val remoteHost = (model as? String)?.let(::remoteImageHost)
    var allowRemote by rememberSaveable(model) { mutableStateOf(false) }
    if (remoteHost != null && !allowRemote) {
        OutlinedButton(
            onClick = { allowRemote = true },
            shape = RoundedCornerShape(12.dp),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.load_external_image, remoteHost),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth(widthPercent.coerceIn(ImageSizing.MIN_PERCENT, ImageSizing.DEFAULT_PERCENT) / 100f)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(12.dp))
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
                    } else Modifier
                )
        )
    }
}

/** Servidor de una imagen de internet, o null si no es una dirección http(s). */
internal fun remoteImageHost(address: String): String? {
    if (!address.startsWith("https://", ignoreCase = true) && !address.startsWith("http://", ignoreCase = true)) return null
    return address.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').ifBlank { null }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, linkColor: Color) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            table.header.indices.forEach { col ->
                Column(modifier = Modifier.width(IntrinsicSize.Max)) {
                    TableCell(table.header[col], linkColor, header = true)
                    table.rows.forEach { row -> TableCell(row[col], linkColor, header = false) }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, linkColor: Color, header: Boolean) {
    Text(
        text = inline(text, linkColor),
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.Bold else FontWeight.Normal),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (header) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .widthIn(max = 260.dp)
    )
}

@Composable
private fun ListRow(marker: String, indent: Int, markerColor: Color, content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 20).dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = markerColor,
            modifier = Modifier.padding(end = 8.dp)
        )
        content()
    }
}

/**
 * Formato en línea: **negrita**, *cursiva*, ~~tachado~~, `código` y [enlaces](https://…),
 * con anidamiento (***negrita y cursiva***). Usa el mismo analizador que el editor visual.
 */
fun inline(text: String, linkColor: Color = Color.Unspecified): AnnotatedString {
    val colors = MarkdownColors(
        accent = linkColor,
        text = Color.Unspecified,
        muted = Color.Unspecified,
        codeBackground = Color.Gray.copy(alpha = 0.15f),
        rule = Color.Unspecified
    )
    return renderMarkdown(text, MarkdownPresenter.inline(text, imageLabel = ""), colors, clickableLinks = true).text
}

/**
 * Texto legible de una nota para compartir o copiar: sin sintaxis Markdown ni imágenes internas.
 */
fun markdownPlainText(markdown: String): String =
    markdown.lineSequence()
        .filterNot { it.trimStart().startsWith("```") || TABLE_SEPARATOR.matches(it) }
        .mapNotNull { line ->
            val wasBlank = line.isBlank()
            val cleaned = line.replace(Regex("""^#{1,6} """), "")
                .replace(Regex("""^(\s*)[-*+] \[(x|X)] """), "$1☑ ")
                .replace(Regex("""^(\s*)[-*+] \[ ] """), "$1☐ ")
                .replace(Regex("""^(\s*)[-*+] """), "$1• ")
                .replace(Regex("""\*\*|~~|`"""), "")
                .replace(INLINE_IMAGE_REGEX, "")
                .replace(INLINE_LINK_WITH_URL_REGEX, "$1 ($2)")
                .replace(Regex(" {2,}"), " ")
                .trimEnd()
            if (!wasBlank && cleaned.isBlank()) null else cleaned
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** Texto plano aproximado de una nota, para vistas previas en tarjetas. */
fun markdownPreview(markdown: String, maxLines: Int = 8): String =
    markdown.lineSequence()
        .filterNot { it.isBlank() || it.trimStart().startsWith("```") || TABLE_SEPARATOR.matches(it) }
        .map { line ->
            line.replace(Regex("""^#{1,6} """), "")
                .replace(Regex("""^(\s*)[-*+] \[(x|X)] """), "$1☑ ")
                .replace(Regex("""^(\s*)[-*+] \[ ] """), "$1☐ ")
                .replace(Regex("""^(\s*)[-*+] """), "$1• ")
                .replace(Regex("""^> ?"""), "")
                .replace(Regex("""\*\*|__|~~|`"""), "")
                .replace(INLINE_IMAGE_REGEX, "")
                .replace(INLINE_LINK_REGEX, "$1")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .take(maxLines)
        .joinToString("\n")
