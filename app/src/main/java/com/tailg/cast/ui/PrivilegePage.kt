package com.tailg.cast.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailg.cast.PrivKitBridge
import priv.kit.ui.PrivilegeScaffold
import priv.kit.ui.PrivilegeUiViewModel

class TailgPrivilegeUiViewModel(
    application: Application,
    private val onBack: () -> Unit,
) : PrivilegeUiViewModel(application, PrivKitBridge.uiConfig) {
    override fun onBackClick(): Boolean {
        onBack()
        return true
    }
}

private class TailgPrivilegeUiViewModelFactory(
    private val application: Application,
    private val onBack: () -> Unit,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TailgPrivilegeUiViewModel::class.java)) {
            return TailgPrivilegeUiViewModel(application, onBack) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
fun PrivilegePage(onBack: () -> Unit) {
    BackHandler { onBack() }
    val application = LocalContext.current.applicationContext as Application
    val vm: TailgPrivilegeUiViewModel = viewModel(
        factory = remember(application, onBack) {
            TailgPrivilegeUiViewModelFactory(application, onBack)
        },
    )
    PrivilegeScaffold(
        modifier = Modifier.fillMaxSize(),
        viewModel = vm,
    )
}
