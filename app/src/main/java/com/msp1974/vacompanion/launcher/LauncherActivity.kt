package com.msp1974.vacompanion.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msp1974.vacompanion.settings.APPConfig
import com.msp1974.vacompanion.ui.layouts.AppIconImage
import com.msp1974.vacompanion.ui.layouts.FloatingLauncherBar
import com.msp1974.vacompanion.ui.layouts.HaControlScreen
import com.msp1974.vacompanion.ui.layouts.HaSettingsDialog
import com.msp1974.vacompanion.ui.layouts.MaSettingsDialog
import com.msp1974.vacompanion.utils.AppInfo
import com.msp1974.vacompanion.utils.InstalledAppsManager
import com.msp1974.vacompanion.utils.RecentAppsManager
import com.msp1974.vacompanion.utils.ShortcutsManager

private val BackgroundColor = Color(0xFF0F0F1A)
private val SurfaceColor = Color(0xFF1E1E2E)
private val AccentColor = Color(0xFF7C6FCD)

class LauncherActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PAGE       = "page"
        const val PAGE_APPS        = 0
        const val PAGE_SMART_HOME  = 1
    }

    private lateinit var config: APPConfig
    private val currentPage = mutableIntStateOf(PAGE_APPS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = APPConfig.getInstance(this)
        currentPage.intValue = intent.getIntExtra(EXTRA_PAGE, config.launcherHomePage)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPage.intValue != config.launcherHomePage) {
                    currentPage.intValue = config.launcherHomePage
                }
                // 已在主畫面：不 moveTaskToBack、不 finish，僅消耗返回鍵，畫面維持不變
            }
        })

        setContent {
            LauncherScreen(
                config = config,
                page   = currentPage.intValue
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentPage.intValue = intent.getIntExtra(EXTRA_PAGE, config.launcherHomePage)
    }
}

@Composable
private fun LauncherScreen(config: APPConfig, page: Int) {
    val isSmartHome = page == LauncherActivity.PAGE_SMART_HOME
    var showMaSettings by remember { mutableStateOf(false) }
    var showHaSettings by remember { mutableStateOf(false) }
    // Increment to force HaControlScreen to rebuild its session after HA settings are saved
    var haSessionVersion by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(BackgroundColor)) {
        // ── Page content ─────────────────────────────────────────────────
        if (isSmartHome) {
            HaControlScreen(
                config           = config,
                sessionVersion   = haSessionVersion,
                onHaSettingsClick = { showHaSettings = true }
            )
        } else {
            AppsPage(config)
        }

        // ── 浮動工具列 overlay ────────────────────────────────────────────────
        FloatingLauncherBar(
            config            = config,
            onSmartHomeClick  = {},
            onMaSettingsClick = { showMaSettings = true },
            onHaSettingsClick = { showHaSettings = true }
        )
    }

    if (showMaSettings) {
        MaSettingsDialog(config = config, onDismiss = { showMaSettings = false })
    }
    if (showHaSettings) {
        HaSettingsDialog(
            config    = config,
            onDismiss = { showHaSettings = false },
            onSave    = { haSessionVersion++ }
        )
    }
}

// ── Apps page (existing launcher logic extracted) ─────────────────────────────

@Composable
private fun AppsPage(config: APPConfig) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shortcutsManager = remember { ShortcutsManager(config) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    var contextMenuApp by remember { mutableStateOf<AppInfo?>(null) }

    val allApps by produceState(initialValue = InstalledAppsManager.cache ?: emptyList()) {
        value = InstalledAppsManager(context).getAllApps("")
    }
    val recentApps by produceState(initialValue = emptyList<AppInfo>()) {
        if (RecentAppsManager(context).hasUsagePermission()) {
            value = RecentAppsManager(context).getRecentApps(30)
                .map { AppInfo(it.packageName, it.label, it.category, "") }
        }
    }
    val frequentApps by produceState(initialValue = emptyList<AppInfo>()) {
        if (RecentAppsManager(context).hasUsagePermission()) {
            value = RecentAppsManager(context).getFrequentApps(30)
                .map { AppInfo(it.packageName, it.label, it.category, "") }
        }
    }
    var pinnedApps by remember { mutableStateOf(
        shortcutsManager.getPinnedShortcuts()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    ) }
    val resolvedPinned by produceState(initialValue = emptyList<AppInfo>(), allApps) {
        value = shortcutsManager.getPinnedShortcuts()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    }
    pinnedApps = resolvedPinned

    val appTabs = listOf("All", "Recent", "Frequent", "Pinned")
    val displayedApps: List<AppInfo> = remember(selectedTab, searchQuery, allApps, recentApps, frequentApps, pinnedApps) {
        val q = searchQuery.trim().lowercase()
        val source = when (selectedTab) {
            1    -> recentApps
            2    -> frequentApps
            3    -> pinnedApps
            else -> allApps
        }
        if (q.isEmpty()) source
        else source.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LauncherSearchBar(
            query         = searchQuery,
            onQueryChange = { searchQuery = it },
            onClear       = { searchQuery = "" },
            onDone        = { focusManager.clearFocus() },
            modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        )
        PrimaryScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor   = SurfaceColor,
            contentColor     = Color.White,
            edgePadding      = 16.dp
        ) {
            appTabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick  = { selectedTab = index; searchQuery = ""; focusManager.clearFocus() },
                    text = {
                        Text(title, color = if (selectedTab == index) Color.White else Color.White.copy(alpha = 0.5f))
                    }
                )
            }
        }
        LazyVerticalGrid(
            columns             = GridCells.Adaptive(minSize = 112.dp),
            contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier            = Modifier.fillMaxSize()
        ) {
            items(displayedApps, key = { it.packageName }) { app ->
                LauncherAppItem(
                    app       = app,
                    isPinned  = shortcutsManager.isPinned(app.packageName),
                    onClick   = { focusManager.clearFocus(); launchApp(context, app.packageName) },
                    onLongClick = { contextMenuApp = app }
                )
            }
        }
    }

    contextMenuApp?.let { app ->
        val isPinned = shortcutsManager.isPinned(app.packageName)
        AlertDialog(
            onDismissRequest = { contextMenuApp = null },
            title            = { Text(app.label) },
            text             = {
                Text(if (isPinned) "Remove '${app.label}' from pinned?" else "Pin '${app.label}'?")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isPinned) shortcutsManager.unpinShortcut(app.packageName)
                    else shortcutsManager.pinShortcut(app.packageName)
                    contextMenuApp = null
                }) { Text(if (isPinned) "Unpin" else "Pin") }
            },
            dismissButton = {
                TextButton(onClick = { contextMenuApp = null }) { Text("Cancel") }
            },
            containerColor = SurfaceColor
        )
    }
}

private fun launchApp(context: android.content.Context, packageName: String) {
    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LauncherAppItem(
    app: AppInfo,
    isPinned: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isPinned) AccentColor.copy(alpha = 0.2f) else Color.Transparent)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        AppIconImage(packageName = app.packageName, sizeDp = 72)
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

@Composable
private fun LauncherSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search apps…", color = Color.White.copy(alpha = 0.5f)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White.copy(alpha = 0.15f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.10f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
    )
}
