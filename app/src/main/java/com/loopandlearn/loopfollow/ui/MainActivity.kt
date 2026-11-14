package com.loopandlearn.loopfollow.ui

import android.os.Bundle
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.loopandlearn.loopfollow.engine.MainEngine
import com.loopandlearn.loopfollow.engine.TaskScheduler
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import com.loopandlearn.loopfollow.storage.TabItem
import com.loopandlearn.loopfollow.ui.components.BannerView
import com.loopandlearn.loopfollow.ui.theme.LocalAppColors
import com.loopandlearn.loopfollow.ui.theme.LoopFollowTheme
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : FragmentActivity() {
    /** True while the keyguard is up; the app then shows only the Snoozer for an active alarm. */
    private val keyguardLocked = mutableStateOf(false)

    /** Set while one of the app's own activities (scanner, picker, share sheet) is on top. */
    private var childActivityOpen = false

    private fun updateKeyguard() {
        keyguardLocked.value = (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MainEngine.bootstrap()
        com.loopandlearn.loopfollow.service.LoopFollowService.createChannel(this)
        com.loopandlearn.loopfollow.service.LoopFollowService.start(this)

        Storage.forcePortraitMode.flow.onEach { portrait ->
            requestedOrientation = if (portrait) {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }.launchIn(lifecycleScope)

        // ANDROID-DEVIATION: The app shows over the lock screen and turns the screen on only while an alarm is
        // active, and then only its Snoozer; everything else needs the phone unlocked.
        Observable.currentAlarm.flow.onEach { alarmId ->
            val alarming = alarmId != null
            setShowWhenLocked(alarming)
            setTurnScreenOn(alarming)
            updateKeyguard()
        }.launchIn(lifecycleScope)

        Storage.screenlockSwitchState.flow.onEach { keepOn ->
            if (keepOn) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.launchIn(lifecycleScope)

        setContent {
            LoopFollowTheme {
                val onboarded by Storage.hasCompletedOnboarding.flow.collectAsStateWithLifecycle()
                val telemetryDecided by Storage.telemetryConsentDecisionMade.flow.collectAsStateWithLifecycle()
                val alarmId by Observable.currentAlarm.flow.collectAsStateWithLifecycle()
                if (keyguardLocked.value && alarmId != null) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.fillMaxSize().background(LocalAppColors.current.background).safeDrawingPadding(),
                    ) { com.loopandlearn.loopfollow.ui.alarms.SnoozerScreen() }
                    return@LoopFollowTheme
                }
                MainTabView()
                when {
                    !onboarded -> com.loopandlearn.loopfollow.ui.onboarding.OnboardingScreen(onClose = {})
                    // Telemetry consent first; the notification request follows once it is decided.
                    !telemetryDecided -> com.loopandlearn.loopfollow.ui.settings.TelemetryConsentScreen()
                    else -> com.loopandlearn.loopfollow.ui.onboarding.RequestNotificationsIfUndecided()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val volumeKey = event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (volumeKey && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
            com.loopandlearn.loopfollow.alarm.VolumeButtonSnooze.onVolumeKey()
        ) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        com.loopandlearn.loopfollow.engine.NightscoutSocketManager.connectIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        updateKeyguard()
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        childActivityOpen = true
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun startActivity(intent: android.content.Intent?, options: Bundle?) {
        childActivityOpen = true
        super.startActivity(intent, options)
    }

    override fun onResume() {
        super.onResume()
        childActivityOpen = false
        updateKeyguard()
        TaskScheduler.checkTasksNow()
        com.loopandlearn.loopfollow.alarm.AlarmPermissions.updateBanner(this)
    }

    override fun onStop() {
        super.onStop()
        com.loopandlearn.loopfollow.engine.NightscoutSocketManager.disconnect()
        // Leaving the app returns it to the home screen; a configuration change or one of the app's own
        // activities (scanner, picker, share sheet) keeps the current tab and navigation.
        if (!isChangingConfigurations && !childActivityOpen) Observable.selectedTabIndex.value = 0
    }
}

@Composable
fun MainTabView() {
    val colors = LocalAppColors.current
    val selectedTab by Observable.selectedTabIndex.flow.collectAsStateWithLifecycle()
    // Re-read the tab layout whenever any position changes.
    val positions = TabItem.entries.map { Storage.positionValue(it).flow.collectAsStateWithLifecycle().value }
    val orderedItems = remember(positions) { Storage.orderedTabBarItems().take(4) }

    val stacks = remember { HashMap<Int, NavStack>() }
    fun stackFor(index: Int) = stacks.getOrPut(index) { NavStack() }

    val currentStack = stackFor(selectedTab)
    BackHandler(enabled = currentStack.canPop) { currentStack.pop() }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.statusBarsPadding()) {
            BannerView()
        }
        Box(
            Modifier
                .weight(1f)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            if (selectedTab < orderedItems.size && selectedTab < 4) {
                TabRoot(orderedItems[selectedTab], stackFor(selectedTab))
            } else {
                MenuRoot(stackFor(4))
            }
        }
        NavigationBar(containerColor = if (colors.isDark) Color(0xFF121212) else Color(0xFFF7F7F7)) {
            val tabColors = NavigationBarItemDefaults.colors(
                selectedIconColor = colors.blue,
                selectedTextColor = colors.blue,
                indicatorColor = Color.Transparent,
                unselectedIconColor = colors.gray,
                unselectedTextColor = colors.gray,
            )
            orderedItems.forEachIndexed { index, item ->
                NavigationBarItem(
                    selected = selectedTab == index,
                    onClick = {
                        if (selectedTab == index) stackFor(index).popToRoot()
                        Observable.selectedTabIndex.value = index
                    },
                    icon = { Icon(item.icon, contentDescription = item.displayName) },
                    label = { Text(item.displayName, fontSize = 10.sp) },
                    colors = tabColors,
                )
            }
            NavigationBarItem(
                selected = selectedTab == 4,
                onClick = {
                    if (selectedTab == 4) stackFor(4).popToRoot()
                    Observable.selectedTabIndex.value = 4
                },
                icon = { Icon(Icons.Filled.Menu, contentDescription = "Menu") },
                label = { Text("Menu", fontSize = 10.sp) },
                colors = tabColors,
            )
        }
    }
}
