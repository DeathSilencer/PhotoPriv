package com.david.photopriv

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.david.photopriv.ui.HomeScreen
import com.david.photopriv.ui.PinLoginScreen
import com.david.photopriv.ui.PhotoPrivViewModel
import com.david.photopriv.ui.theme.PhotoPrivTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PhotoPrivViewModel by viewModels()
    private var isUnlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PhotoPrivTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionGuard {
                        if (isUnlocked) {
                            HomeScreen(viewModel = viewModel)
                        } else {
                            PinLoginScreen(
                                correctPin = "0908",
                                onUnlockSuccess = { isUnlocked = true }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            am?.appTasks?.forEach { task ->
                try {
                    task.setExcludeFromRecents(true)
                } catch (ignored: Exception) {}
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-bloqueo automático al salir de la aplicación o apagar la pantalla
        isUnlocked = false
        // Destruir y remover la tarea para garantizar invisibilidad en la lista de apps recientes
        finishAndRemoveTask()
    }

    @Composable
    private fun PermissionGuard(content: @Composable () -> Unit) {
        val requiredPermissions = remember {
            buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    add(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }

            }
        }

        var hasAllPermissions by remember {
            mutableStateOf(
                requiredPermissions.all {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                }
            )
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            hasAllPermissions = results.values.all { it }
        }

        LaunchedEffect(hasAllPermissions) {
            if (!hasAllPermissions) {
                permissionLauncher.launch(requiredPermissions.toTypedArray())
            } else {
                viewModel.ensurePermanentSessionActive()
            }
        }

        if (hasAllPermissions) {
            content()
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "🛡️ Permisos Requeridos",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "PhotoPriv necesita acceso a fotos para detectar cuándo se extraen archivos de la Carpeta Bloqueada y notificaciones para avisarte en tiempo real.",
                        textAlign = TextAlign.Center,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                        }
                    ) {
                        Text("Conceder Permisos")
                    }
                }
            }
        }
    }
}