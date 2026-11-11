package com.perkus.imagoedit

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.perkus.imagoedit.data.Feedback
import com.perkus.imagoedit.data.TooLarge
import com.perkus.imagoedit.data.withAppLanguage
import com.perkus.imagoedit.ui.common.ErrorDialog
import com.perkus.imagoedit.ui.common.RecoveryDialog
import com.perkus.imagoedit.ui.common.ScreenFrame
import com.perkus.imagoedit.ui.common.TooLargeDialog
import com.perkus.imagoedit.ui.common.hideSystemBars
import com.perkus.imagoedit.ui.editor.EditorScreen
import com.perkus.imagoedit.ui.menu.CanvasSizeDialog
import com.perkus.imagoedit.ui.menu.MainMenuScreen
import com.perkus.imagoedit.ui.theme.ImagoEditTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(newBase.withAppLanguage())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Feedback.init(this)
        if (savedInstanceState == null) openSharedImage(intent) // recreation doesn't handle the Intent twice
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        window.hideSystemBars()
        setContent {
            ImagoEditTheme {
                ScreenFrame {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val editing = vm.editing
                        // Keyed: the editor's remembered state belongs to one project
                        if (editing != null) key(editing.id) { EditorScreen(vm, editing) } else MainMenuScreen(vm)
                        if (vm.recovered != null && !vm.busy) RecoveryDialog(vm::resolveRecovery) // not while busy: open() wouldn't start
                        vm.reducing?.let { (size, fit) ->
                            CanvasSizeDialog(R.string.import_reduce_title, fit.width, fit.height, { vm.resolveImport(null) }, image = size) { w, h -> vm.resolveImport(Size(w, h)) }
                        }
                        vm.error?.let { if (it is TooLarge) TooLargeDialog(it, vm::resolveTooLarge) else ErrorDialog(it, vm::dismissError) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSharedImage(intent)
    }

    /** applicationContext: the resolver outlives the Activity */
    private fun openSharedImage(intent: Intent) {
        val uri = intent.data ?: IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        vm.openExternal(applicationContext.contentResolver, uri)
    }

    override fun onStart() { super.onStart(); Feedback.visible = true }
    // The pending recovery write goes now: the process may be killed in the background
    override fun onStop() { super.onStop(); Feedback.visible = false; vm.flushAutosave() }

    /** Also the return from "All files access" settings */
    override fun onResume() {
        super.onResume()
        vm.refreshAccess()
    }

    /** Dialogs and the swipe shade may bring system bars back */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.hideSystemBars()
    }
}