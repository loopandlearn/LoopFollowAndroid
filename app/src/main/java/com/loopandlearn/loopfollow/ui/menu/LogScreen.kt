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

/** Shares today's and yesterday's log files through the system share sheet. */
fun shareLogs(context: Context): Boolean {
    val files = LogManager.logFilesForTodayAndYesterday()
    if (files.isEmpty()) return false
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        putExtra(Intent.EXTRA_SUBJECT, "LoopFollow logs")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Logs"))
    return true
}
