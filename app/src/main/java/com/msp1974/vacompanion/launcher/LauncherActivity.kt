package com.msp1974.vacompanion.launcher

import android.os.Bundle
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
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
import com.msp1974.vacompanion.utils.AppInfo
import com.msp1974.vacompanion.utils.InstalledAppsManager
import com.msp1974.vacompanion.utils.RecentAppsManager
import com.msp1974.vacompanion.utils.ShortcutsManager

private val BackgroundColor = Color(0xFF0F0F1A)
private val SurfaceColor = Color(0xFF1E1E2E)
private val AccentColor = Color(0xFF7C6FCD)

class LauncherActivity : ComponentActivity() {

    private lateinit var config: APPConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = APPConfig.getInstance(this)

        setContent {
            LauncherScreen(
                config = config,
                onReturnToHA = {
                    startActivity(HADashboardActivity.buildIntent(this))
                }
            )
        }
    }
}

@Composable
private fun LauncherScreen(config: APPConfig, onReturnToHA: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val shortcutsManager = remember { ShortcutsManager(config) }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    var contextMenuApp by remember { mutableStateOf<AppInfo?>(null) }

    // Use the static cache as initialValue so the grid shows instantly if already pre-loaded
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

    // Refresh pinned list when allApps loads
    val resolvedPinned by produceState(initialValue = emptyList<AppInfo>(), allApps) {
        value = shortcutsManager.getPinnedShortcuts()
            .mapNotNull { pkg -> allApps.find { it.packageName == pkg } }
    }
    pinnedApps = resolvedPinned

    val tabs = listOf("All", "Recent", "Frequent", "Pinned")
    val query = searchQuery.trim().lowercase()
    val displayedApps: List<AppInfo> = remember(selectedTab, searchQuery, allApps, recentApps, frequentApps, pinnedApps) {
        val q = searchQuery.trim().lowercase()
        val source = when (selectedTab) {
            1 -> recentApps
            2 -> frequentApps
            3 -> pinnedApps
            else -> allApps
        }
        if (q.isEmpty()) source
        else source.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
    ) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Apps",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onReturnToHA,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentColor,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(6.dp))
                Text("Return to HA", fontSize = 13.sp)
            }
        }

        // Search bar
        LauncherSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onClear = { searchQuery = "" },
            onDone = { focusManager.clearFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        // Tab row
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = SurfaceColor,
            contentColor = Color.White,
            edgePadding = 16.dp
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        searchQuery = ""
                        focusManager.clearFocus()
                    },
                    text = {
                        Text(
                            text = title,
                            color = if (selectedTab == index) Color.White else Color.White.copy(alpha = 0.5f)
                        )
                    }
                )
            }
        }

        // App grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 96.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(displayedApps, key = { it.packageName }) { app ->
                LauncherAppItem(
                    app = app,
                    isPinned = shortcutsManager.isPinned(app.packageName),
                    onClick = {
                        focusManager.clearFocus()
                        launchApp(context, app.packageName)
                    },
                    onLongClick = { contextMenuApp = app }
                )
            }
        }
    }

    // Long-press context menu
    contextMenuApp?.let { app ->
        val isPinned = shortcutsManager.isPinned(app.packageName)
        AlertDialog(
            onDismissRequest = { contextMenuApp = null },
            title = { Text(app.label) },
            text = {
                Text(if (isPinned) "Remove '${app.label}' from pinned shortcuts?" else "Pin '${app.label}' to shortcuts?")
            },
            confirmButton = {
                TextButton(onClick = {
                    if (isPinned) shortcutsManager.unpinShortcut(app.packageName)
                    else shortcutsManager.pinShortcut(app.packageName)
                    contextMenuApp = null
                }) {
                    Text(if (isPinned) "Unpin" else "Pin")
                }
            },
            dismissButton = {
                TextButton(onClick = { contextMenuApp = null }) {
                    Text("Cancel")
                }
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
        AppIconImage(packageName = app.packageName, sizeDp = 56)
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
