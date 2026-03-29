package com.msp1974.vacompanion.ui.layouts

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.utils.AppInfo
import com.msp1974.vacompanion.utils.InstalledAppsManager
import com.msp1974.vacompanion.utils.RecentAppInfo
import com.msp1974.vacompanion.utils.RecentAppsManager

private val BackgroundColor = Color(0xE6000000)  // 90% opaque black

@Composable
fun LauncherScreen(
    onDismiss: () -> Unit,
    onLaunch: (String) -> Unit,
    vaViewModel: VAViewModel = viewModel()
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current

    // Load app lists once, reuse from viewmodel if already populated
    val allApps by produceState(initialValue = vaViewModel.vacaState.value.launcherApps) {
        if (vaViewModel.vacaState.value.launcherApps.isEmpty()) {
            val apps = InstalledAppsManager(context).getAllApps("")
            val recent = if (RecentAppsManager(context).hasUsagePermission())
                RecentAppsManager(context).getRecentApps(20)
            else emptyList()
            val frequent = if (RecentAppsManager(context).hasUsagePermission())
                RecentAppsManager(context).getFrequentApps(20)
            else emptyList()
            vaViewModel.setLauncherData(apps, recent, frequent)
            value = apps
        }
    }
    val recentApps = vaViewModel.vacaState.value.launcherRecentApps
    val frequentApps = vaViewModel.vacaState.value.launcherFrequentApps

    val tabs = listOf("All", "Recent", "Frequent")

    val displayedApps: List<AppInfo> = remember(selectedTab, searchQuery, allApps, recentApps, frequentApps) {
        val query = searchQuery.trim().lowercase()
        val source: List<AppInfo> = when (selectedTab) {
            1 -> recentApps.map { r -> AppInfo(r.packageName, r.label, r.category, "") }
            2 -> frequentApps.map { r -> AppInfo(r.packageName, r.label, r.category, "") }
            else -> allApps
        }
        if (query.isEmpty()) source
        else source.filter { it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundColor)
            .clickable(onClick = onDismiss)  // tap outside grid dismisses
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = false, onClick = {})  // block dismiss inside
        ) {
            // Search bar
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClear = { searchQuery = "" },
                onDone = { focusManager.clearFocus() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )

            // Tab row
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
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
                                color = if (selectedTab == index) Color.White
                                        else Color.White.copy(alpha = 0.55f)
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
                    AppGridItem(
                        app = app,
                        onClick = {
                            focusManager.clearFocus()
                            onLaunch(app.packageName)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
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

@Composable
private fun AppGridItem(app: AppInfo, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
fun AppIconImage(packageName: String, sizeDp: Int = 56) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, packageName) {
        value = try {
            val drawable: Drawable = context.packageManager.getApplicationIcon(packageName)
            drawable.toBitmap(sizeDp * 2, sizeDp * 2).asImageBitmap()
        } catch (e: PackageManager.NameNotFoundException) { null }
        catch (e: Exception) { null }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}
