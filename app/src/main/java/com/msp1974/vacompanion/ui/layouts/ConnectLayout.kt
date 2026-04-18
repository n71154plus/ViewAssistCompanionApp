package com.msp1974.vacompanion.ui.layouts

import android.content.res.Configuration
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msp1974.vacompanion.R
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.InfoItem
import com.msp1974.vacompanion.ui.components.LabelledSwitch
import com.msp1974.vacompanion.ui.components.UUIDEditDialog
import com.msp1974.vacompanion.ui.theme.AppTheme
import com.msp1974.vacompanion.ui.theme.CustomColours

@Composable
fun ConnectionScreen(vaViewModel: VAViewModel = viewModel()) {
    val vaUiState by vaViewModel.vacaState.collectAsState()
    val orientation = LocalConfiguration.current.orientation

    when(orientation) {
        Configuration.ORIENTATION_SQUARE,
        Configuration.ORIENTATION_UNDEFINED,
        Configuration.ORIENTATION_PORTRAIT -> {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .safeDrawingPadding()
                    .background(MaterialTheme.colorScheme.background)
                    .fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 30.dp)
                        .weight(0.25f),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LogoImage(orientation, vaViewModel::showClearPairedDeviceDialog)
                }
                Column (
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.75f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    InfoTextBlock(vaUiState.appInfo,  vaViewModel::showUUIDChangeDialog)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                        LaunchOnBootSwitch(vaUiState.launchOnBoot, callback = {
                            vaViewModel.launchOnBoot = it
                        })
                    } else {
                        Text(
                            text="To launch on boot, set this app as the launcher",
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .width(280.dp)
                                .padding(16.dp)

                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .zIndex(2f),
                        verticalArrangement = Arrangement.Top,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when {
                            vaUiState.updates.updateAvailable -> UpdateButton(
                                text = stringResource(R.string.button_update_required),
                                modifier = Modifier.padding(top=30.dp),
                                onClick = { vaViewModel.checkForUpdate() })
                        }
                        if (!vaUiState.permissions.hasCorePermissions || !vaUiState.permissions.hasOptionalPermissions) {
                            PermissionStatusButton(
                                text = "Permissions",
                                colour = if (!vaUiState.permissions.hasCorePermissions) CustomColours.RED else CustomColours.AMBER,
                                modifier = Modifier.padding(top=30.dp),
                                onClick = { vaViewModel.requestPermissions() }
                            )
                        }
                    }

                }
                Column() {
                    StatusText(vaUiState.statusMessage)
                }
                if (vaUiState.showUUIDChangeDialog) {
                    UUIDEditDialog(
                        onDismissRequest = {vaViewModel.showUUIDChangeDialog(false)},
                        onConfirmation = vaViewModel::setUUID,
                        initText = vaUiState.appInfo["UUID"]!!
                    )
                }
            }
        }
        Configuration.ORIENTATION_LANDSCAPE -> {
            Column(
                modifier = Modifier.background(MaterialTheme.colorScheme.background)
            ) {
                Row() {
                    Column(
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxSize()
                            .padding(start = 10.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        LogoImage(orientation, vaViewModel::showClearPairedDeviceDialog)
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp)
                                    .zIndex(2f),
                                verticalArrangement = Arrangement.Top,
                                horizontalAlignment = Alignment.End
                            ) {
                                when {
                                    vaUiState.updates.updateAvailable -> UpdateButton(
                                        text = stringResource(R.string.button_update_required),
                                        modifier = Modifier.padding(16.dp),
                                        onClick = { vaViewModel.checkForUpdate() })
                                }
                                if (!vaUiState.permissions.hasCorePermissions || !vaUiState.permissions.hasOptionalPermissions) {
                                    PermissionStatusButton(
                                        text = "Permissions",
                                        colour = if (!vaUiState.permissions.hasCorePermissions) CustomColours.RED else CustomColours.AMBER,
                                        modifier = Modifier.padding(16.dp),
                                        onClick = { vaViewModel.requestPermissions() }
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                InfoTextBlock(vaUiState.appInfo, vaViewModel::showUUIDChangeDialog)
                                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                                    LaunchOnBootSwitch(vaUiState.launchOnBoot, callback = {
                                        vaViewModel.launchOnBoot = it
                                    })
                                } else {
                                    Text(
                                        text="To launch on boot, set this app as the launcher",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier
                                            .width(280.dp)
                                            .padding(16.dp)

                                    )
                                }
                            }
                        }
                    }
                }
            }
            Column() {
                StatusText(vaUiState.statusMessage)
            }
            if (vaUiState.showUUIDChangeDialog) {
                UUIDEditDialog(
                    onDismissRequest = {vaViewModel.showUUIDChangeDialog(false)},
                    onConfirmation = vaViewModel::setUUID,
                    initText = vaUiState.appInfo["UUID"]!!
                )
            }
        }
    }
}

@Composable
fun LogoImage(orientation: Int, onLongPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Image(
        painter = painterResource(id = R.drawable.main_logo),
        contentDescription = "Logo",
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.surfaceBright),
        modifier =
                when(orientation) {
                    Configuration.ORIENTATION_PORTRAIT -> Modifier.padding(start=48.dp, end=48.dp, top=8.dp)
                    else -> Modifier.padding(start=24.dp, end=24.dp, top=8.dp)
                }
                .combinedClickable (
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                    onClick = {}
                )

    )
}

@Composable
fun InfoTextBlock(infoItems: Map<String, String>, onClick: () -> Unit) {
    Column(
        modifier=Modifier
            .width(280.dp)
            .padding(16.dp)
            .clickable {
                onClick()
            }
    ) {
        infoItems.forEach { (label, value) ->
            InfoItem(label, value)
        }
    }
}



@Composable
fun StatusText(statusMessage: String) {
    Column(
        modifier = Modifier
        .fillMaxSize()
        .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            statusMessage,
            color = MaterialTheme.colorScheme.onPrimary, textAlign = TextAlign.Start,
            fontSize = 20.sp
        )
    }
}

@Composable
fun LaunchOnBootSwitch(isOn: Boolean, callback: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LabelledSwitch(isOn, callback)
    }
}

@Composable
fun UpdateButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = { onClick() },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = CustomColours.AMBER,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    ) { Text(text) }
}

@Composable
fun PermissionStatusButton(text: String, colour: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = { onClick() },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = colour
        )
    ) { Text(text) }
}

@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    name = "DefaultPreviewDark",
    apiLevel = 36
)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_NO,
    name = "DefaultPreviewLight"
)
@Preview(heightDp = 480, widthDp = 800)
@Composable
fun AppPreview() {
    AppTheme(
        dynamicColor = false,
        darkMode = false
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ConnectionScreen()
        }
    }
}
