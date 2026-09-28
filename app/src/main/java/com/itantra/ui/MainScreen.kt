package com.itantra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itantra.MainViewModel

/** Routes between the start screen, the language packs screen and a running session. */
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.state.collectAsState()
    // Language packs opens from the start screen, or from a session's "Install … voice" prompt (the session
    // keeps running underneath and resumes on Back).
    if (ui.showPacks) {
        BackHandler { viewModel.closePacks() }
        PacksScreen(
            packs = ui.packs,
            onBack = viewModel::closePacks,
            onImport = viewModel::importPack,
            onRescan = viewModel::openPacks,
            onDelete = viewModel::deletePack,
        )
    } else if (ui.mode == null) {
        HomeScreen(
            ui,
            onStart = viewModel::startSession,
            onOpenPacks = viewModel::openPacks,
            onSelectLanguage = viewModel::selectLanguage,
        )
    } else {
        BackHandler { viewModel.leaveSession() }
        SessionScreen(
            ui = ui,
            onLeave = viewModel::leaveSession,
            onPressStart = viewModel::onPressStart,
            onPressEnd = viewModel::onPressEnd,
            onOpenPacks = viewModel::openPacks,
        )
    }
}
