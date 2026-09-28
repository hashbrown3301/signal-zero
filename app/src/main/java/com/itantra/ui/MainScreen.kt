package com.itantra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.MainViewModel
import com.itantra.MainViewModel.Link
import com.itantra.MainViewModel.Mode
import com.itantra.ui.components.BottomNav
import com.itantra.ui.components.Tab
import com.itantra.ui.components.brandBackdrop
import com.itantra.ui.theme.Palette

/**
 * The four tabs (Home · Talk · Languages · Metrics) over one running session. Switching tabs never stops the
 * session; Home opens Talk by itself when a link first comes up, and End on Talk returns to Home.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }

    // Solo has nothing to set up: go straight to Talk. A link: go to Talk once, when it first connects.
    var openedForSession by rememberSaveable(ui.mode) { mutableStateOf(false) }
    LaunchedEffect(ui.mode, ui.connected) {
        when {
            ui.mode == null -> if (tab == Tab.Talk) tab = Tab.Home
            !openedForSession && (ui.mode == Mode.SOLO || ui.connected) -> {
                tab = Tab.Talk
                openedForSession = true
            }
        }
    }
    // With the screen off the app goes to the background and Android cuts its network after about a minute,
    // which drops the link. Keep the screen on while hosting or joining, whatever tab is showing.
    val view = LocalView.current
    DisposableEffect(ui.networked) {
        view.keepScreenOn = ui.networked
        onDispose { view.keepScreenOn = false }
    }
    // Languages and Metrics both show the installed packs, so refresh the list when either opens.
    LaunchedEffect(tab) { if (tab == Tab.Languages || tab == Tab.Metrics) viewModel.openPacks() }
    if (tab != Tab.Home) BackHandler { tab = Tab.Home }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.NavyDeep)
            .brandBackdrop()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Home -> HomeScreen(
                    ui,
                    onStart = { mode, peer, link, name -> viewModel.startSession(mode, peer, link, name) },
                    onLeave = viewModel::leaveSession,
                    onOpenTalk = { tab = Tab.Talk },
                    onSelectLanguage = viewModel::selectLanguage,
                )
                Tab.Talk -> TalkScreen(
                    ui = ui,
                    onPressStart = viewModel::onPressStart,
                    onPressEnd = viewModel::onPressEnd,
                    onLeave = viewModel::leaveSession,
                    onInstallVoice = { tab = Tab.Languages },
                    onStartSolo = { viewModel.startSession(Mode.SOLO, "", Link.WIFI, "") },
                )
                Tab.Languages -> PacksScreen(
                    packs = ui.packs,
                    onBack = { tab = Tab.Home },
                    onImport = viewModel::importPack,
                    onRescan = viewModel::openPacks,
                    onDelete = viewModel::deletePack,
                    onDownload = viewModel::downloadPack,
                    onCancelDownload = viewModel::cancelDownload,
                    onRefreshCatalog = viewModel::refreshCatalog,
                )
                Tab.Metrics -> MetricsScreen(ui)
            }
        }
        BottomNav(current = tab, onSelect = { tab = it })
    }
}

