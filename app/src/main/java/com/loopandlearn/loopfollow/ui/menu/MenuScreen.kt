package com.loopandlearn.loopfollow.ui.menu

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Plagiarism
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loopandlearn.loopfollow.BuildConfig
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.TabItem
import com.loopandlearn.loopfollow.ui.NavStack
import com.loopandlearn.loopfollow.ui.Route
import com.loopandlearn.loopfollow.ui.components.FormDivider
import com.loopandlearn.loopfollow.ui.components.FormList
import com.loopandlearn.loopfollow.ui.components.FormRow
import com.loopandlearn.loopfollow.ui.components.FormText
import com.loopandlearn.loopfollow.ui.components.LabeledValueRow
import com.loopandlearn.loopfollow.ui.components.NavigationRow
import com.loopandlearn.loopfollow.ui.components.formSection
import com.loopandlearn.loopfollow.ui.icon
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors

private val supportLinks = listOf(
    Triple("LoopFollow Docs", Icons.Outlined.Book, "https://loopfollowdocs.org/"),
    Triple("Loop and Learn Discord", Icons.Outlined.Forum, "https://discord.gg/KQgk3gzuYU"),
    Triple("LoopFollow Facebook Group", Icons.Outlined.Groups, "https://www.facebook.com/groups/loopfollowlnl"),
)

@Composable
fun MenuScreen(nav: NavStack) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(colors.groupedBackground)) {
        Text(
            "Menu",
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            color = colors.primaryText,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        FormList {
            formSection {
                NavigationRow("Settings", Icons.Outlined.Settings) { nav.push(Route.Settings) }
            }
            formSection(header = "Features") {
                TabItem.featureOrder.forEachIndexed { index, item ->
                    if (index > 0) FormDivider()
                    NavigationRow(item.displayName, item.icon) {
                        val tabIndex = Storage.orderedTabBarItems().indexOf(item)
                        if (tabIndex >= 0) Observable.selectedTabIndex.value = tabIndex else nav.push(Route.Feature(item))
                    }
                }
            }
            formSection(header = "Logging") {
                NavigationRow("View Log", Icons.Outlined.Plagiarism) { nav.push(Route.Log) }
                FormDivider()
                FormRow(onClick = {
                    if (!shareLogs(context)) Toast.makeText(context, "There are no logs to share.", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Outlined.Share, null, tint = colors.blue, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    FormText("Share Logs")
                }
            }
            formSection(header = "Support & Community") {
                supportLinks.forEachIndexed { index, (title, icon, url) ->
                    if (index > 0) FormDivider()
                    FormRow(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) {
                        Icon(icon, null, tint = colors.blue, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        FormText(title, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = colors.gray, modifier = Modifier.size(18.dp))
                    }
                }
            }
            formSection(header = "Build Information") {
                LabeledValueRow("Version", BuildConfig.VERSION_NAME)
                FormDivider()
                LabeledValueRow("Platform", "Android ${android.os.Build.VERSION.RELEASE}")
            }
        }
    }
}
