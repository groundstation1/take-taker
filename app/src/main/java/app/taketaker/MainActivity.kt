package app.taketaker

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.taketaker.ui.C
import app.taketaker.ui.LiveScreen
import app.taketaker.ui.SetupScreen
import app.taketaker.ui.SourcesScreen
import app.taketaker.ui.T
import app.taketaker.ui.TakeTakerTheme
import app.taketaker.ui.TrimScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent { TakeTakerTheme { App(vm) } }
    }
}

@Composable
private fun App(vm: AppModel) {
    val ctx = LocalView.current.context
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(hasMic()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMic()
        if (granted) vm.rescan()
    }
    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    LaunchedEffect(Unit) {
        if (granted) vm.rescan() else launcher.launch(perms)
    }

    // one frame clock for every meter / timeline
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { tick.longValue = it } }

    val view = LocalView.current
    DisposableEffect(vm.screen) {
        view.keepScreenOn = vm.screen != Screen.SOURCES
        onDispose { }
    }
    BackHandler(enabled = vm.screen != Screen.SOURCES) { vm.back() }

    // camera lives while a session is open (setup / live / trim) and a facing is picked
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val cameraOn = vm.facing != null && vm.screen != Screen.SOURCES
    LaunchedEffect(vm.facing, cameraOn) {
        val f = vm.facing
        if (cameraOn && f != null) vm.camera.bind(owner, f) { vm.message = it } else vm.camera.unbind()
    }

    Box(Modifier.fillMaxSize().background(C.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        AnimatedContent(
            targetState = vm.screen,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "screen",
        ) { s ->
            when (s) {
                Screen.SOURCES -> SourcesScreen(vm, granted) { launcher.launch(perms) }
                Screen.SETUP -> SetupScreen(vm, tick)
                Screen.LIVE -> LiveScreen(vm, tick)
                Screen.TRIM -> TrimScreen(vm, tick)
            }
        }
        Toast(vm)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Toast(vm: AppModel) {
    val msg = vm.message ?: return
    LaunchedEffect(msg) { delay(4000); vm.message = null }
    Text(
        msg,
        style = T.data,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(C.raised)
            .clickable { vm.message = null }
            .padding(14.dp),
    )
}
