package com.loopandlearn.loopfollow.ui.menu

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.ui.components.FormTextField
import com.loopandlearn.loopfollow.ui.components.NavScreen
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.loopandlearn.loopfollow.helpers.TelemetryClient
import com.loopandlearn.loopfollow.ui.components.FormSection
import com.loopandlearn.loopfollow.ui.components.clickableNoRipple
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Composable
fun LogScreen(onBack: () -> Unit) {
    val colors = LocalAppColors.current
    var lines by remember { mutableStateOf(emptyList<String>()) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        lines = withContext(Dispatchers.IO) {
            LogManager.logFilesForTodayAndYesterday().reversed().flatMap { it.readLines() }
        }
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    val shown = if (query.isBlank()) lines else lines.filter { it.contains(query, ignoreCase = true) }

    NavScreen("Log", onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            FormTextField(
                query, { query = it }, "Search",
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(colors.cell, RoundedCornerShape(10.dp)).padding(10.dp),
            )
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), state = listState) {
                items(shown) { line ->
                    Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.primaryText, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

/** Shares today's and yesterday's log files, with the user's description, as one zip through the system share sheet. */
suspend fun shareLogs(context: Context, noticeText: String) {
    val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
    val archive = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "LogShare").apply {
            deleteRecursively()
            mkdirs()
        }
        val files = LogManager.logFilesForTodayAndYesterday()
        val notice = writeShareNoticeFile(directory, noticeText, timestamp)
        val archive = File(directory, "LoopFollow Logs $timestamp.zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            for (file in listOfNotNull(notice) + files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        archive
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "LoopFollow logs")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Logs"))
}

private fun writeShareNoticeFile(directory: File, text: String, timestamp: String): File? {
    val body = text.trim().ifEmpty { "(no description provided)" }
    val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
    val contents = """
        |LoopFollow Log Share Notice
        |Date: $date
        |App version: ${TelemetryClient.buildIdentifier}
        |
        |User description:
        |$body
    """.trimMargin()
    val file = File(directory, "ShareNotice_$timestamp.txt")
    return try {
        file.writeText(contents)
        file
    } catch (e: Exception) {
        LogManager.log(LogManager.Category.GENERAL, "Failed to write share notice file: $e")
        null
    }
}

/** Asks for a description of the problem before the logs are shared. */
@Composable
fun ShareLogNoticeScreen(onCancel: () -> Unit, onShare: (String) -> Unit) {
    val colors = LocalAppColors.current
    var noticeText by remember { mutableStateOf("") }
    NavScreen(
        "Share Logs",
        leading = {
            Text("Cancel", color = colors.blue, fontSize = 17.sp, modifier = Modifier.padding(4.dp).clickableNoRipple(onCancel))
        },
        actions = {
            Text(
                "Share",
                color = colors.blue,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(4.dp).clickableNoRipple { onShare(noticeText) },
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
            FormSection {
                Text(
                    "Thanks for sharing these logs to help us find the problem. Please describe it in as much detail as possible — what time did it happen, what did you do, and what did you expect to happen that didn't?",
                    fontSize = 15.sp,
                    color = colors.secondaryText,
                    modifier = Modifier.padding(16.dp),
                )
            }
            FormSection(header = "Description") {
                BasicTextField(
                    value = noticeText,
                    onValueChange = { noticeText = it },
                    textStyle = TextStyle(fontSize = 17.sp, color = colors.primaryText),
                    cursorBrush = SolidColor(colors.blue),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(12.dp),
                )
            }
        }
    }
}
