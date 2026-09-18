package com.david.photopriv.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.david.photopriv.data.preferences.AppPreferences
import com.david.photopriv.data.preferences.SmtpConfig
import com.david.photopriv.data.preferences.TelegramConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    currentTelegramConfig: TelegramConfig,
    currentSmtpConfig: SmtpConfig,
    currentAppPreferences: AppPreferences,
    onDismiss: () -> Unit,
    onSaveTelegram: (TelegramConfig) -> Unit,
    onSaveSmtp: (SmtpConfig) -> Unit,
    onSaveAppPreferences: (AppPreferences) -> Unit,
    onTestTelegram: (String, String, (Boolean, String) -> Unit) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Telegram State
    var botToken by remember { mutableStateOf(currentTelegramConfig.botToken) }
    var chatId by remember { mutableStateOf(currentTelegramConfig.chatId) }
    var telegramEnabled by remember { mutableStateOf(currentTelegramConfig.enabled) }
    var telegramAutoSend by remember { mutableStateOf(currentTelegramConfig.autoSendImmediately) }

    var testStatusMessage by remember { mutableStateOf<String?>(null) }
    var isTestingTelegram by remember { mutableStateOf(false) }

    // App Preferences State (Sigilo, Ahorro e Invisibilidad)
    var stealthMode by remember { mutableStateOf(currentAppPreferences.stealthMode) }
    var dataSaverMode by remember { mutableStateOf(currentAppPreferences.dataSaverMode) }
    var hideAppIcon by remember { mutableStateOf(currentAppPreferences.hideAppIcon) }

    // SMTP State
    var host by remember { mutableStateOf(currentSmtpConfig.host) }
    var port by remember { mutableStateOf(currentSmtpConfig.port.toString()) }
    var useTls by remember { mutableStateOf(currentSmtpConfig.useTls) }
    var senderEmail by remember { mutableStateOf(currentSmtpConfig.senderEmail) }
    var senderPassword by remember { mutableStateOf(currentSmtpConfig.senderPassword) }
    var recipientEmail by remember { mutableStateOf(currentSmtpConfig.recipientEmail) }
    var smtpAutoSend by remember { mutableStateOf(currentSmtpConfig.autoSendImmediately) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Ajustes y Configuración", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Telegram") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Sigilo/Datos") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("SMTP") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        // --- TELEGRAM TAB ---
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 Telegram soporta fotos y videos. Archivos mayores a 45MB se envían automáticamente divididos en partes.",
                                fontSize = 12.sp,
                                color = Color(0xFF1E40AF),
                                modifier = Modifier.padding(10.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = botToken,
                            onValueChange = { botToken = it },
                            label = { Text("Bot Token") },
                            placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRs...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = chatId,
                            onValueChange = { chatId = it },
                            label = { Text("Chat ID de Destino") },
                            placeholder = { Text("ej. -1004363656402") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Habilitar envío por Telegram", modifier = Modifier.weight(1f))
                            Switch(checked = telegramEnabled, onCheckedChange = { telegramEnabled = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Envío individual inmediato", modifier = Modifier.weight(1f))
                            Switch(checked = telegramAutoSend, onCheckedChange = { telegramAutoSend = it })
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                if (botToken.isNotBlank() && chatId.isNotBlank()) {
                                    isTestingTelegram = true
                                    testStatusMessage = null
                                    onTestTelegram(botToken, chatId) { success, msg ->
                                        isTestingTelegram = false
                                        testStatusMessage = msg
                                    }
                                } else {
                                    testStatusMessage = "Ingresa primero el Token y Chat ID."
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isTestingTelegram
                        ) {
                            if (isTestingTelegram) {
                                CircularProgressIndicator(modifier = Modifier.width(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Probando conexión...")
                            } else {
                                Text("🧪 Probar Conexión con Telegram")
                            }
                        }

                        testStatusMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = msg,
                                fontSize = 12.sp,
                                color = if (msg.contains("éxito", ignoreCase = true)) Color(0xFF15803D) else Color(0xFFDC2626)
                            )
                        }
                    }

                    1 -> {
                        // --- SIGILO Y AHORRO DE DATOS TAB ---
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "🥷 Modo Camuflaje (Sigilo)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(checked = stealthMode, onCheckedChange = { stealthMode = it })
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Disfraza la notificación del sistema como 'Servicios de Google Play: Comprobando estado de sincronización...' para no levantar sospechas si alguien ve tu teléfono.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF4B5563)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "📉 Ahorro de Datos Móviles",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(checked = dataSaverMode, onCheckedChange = { dataSaverMode = it })
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Comprime las fotos a Full HD (~300 KB) antes del envío. Ahorra 90% de datos móviles y permite que las fotos se envíen en menos de 1 segundo incluso con señal 4G moderada.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF166534)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "👻 Ocultar Icono de la App (Nivel 4)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = Color(0xFF991B1B),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(checked = hideAppIcon, onCheckedChange = { hideAppIcon = it })
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "El icono desaparecerá por completo de tu pantalla de inicio y del cajón de apps (parecerá desinstalada).\n\n" +
                                            "📞 Para volver a abrirla cuando esté oculta:\n" +
                                            "• Marca en el Teléfono: *#*#0000#*#* (o *#*#1234#*#*)\n" +
                                            "• O abre en el navegador web: photopriv://open",
                                    fontSize = 11.sp,
                                    color = Color(0xFF7F1D1D)
                                )
                            }
                        }
                    }

                    2 -> {
                        // --- SMTP TAB ---
                        OutlinedTextField(
                            value = recipientEmail,
                            onValueChange = { recipientEmail = it },
                            label = { Text("Correo Destino") },
                            placeholder = { Text("ejemplo@gmail.com") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = senderEmail,
                            onValueChange = { senderEmail = it },
                            label = { Text("Correo Emisor") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = senderPassword,
                            onValueChange = { senderPassword = it },
                            label = { Text("Contraseña de Aplicación") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("Servidor SMTP") },
                                modifier = Modifier.weight(2f),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it },
                                label = { Text("Puerto") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Habilitar STARTTLS / SSL", modifier = Modifier.weight(1f))
                            Switch(checked = useTls, onCheckedChange = { useTls = it })
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Envío inmediato al detectar", modifier = Modifier.weight(1f))
                            Switch(checked = smtpAutoSend, onCheckedChange = { smtpAutoSend = it })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveTelegram(
                        TelegramConfig(
                            botToken = botToken.trim(),
                            chatId = chatId.trim(),
                            enabled = telegramEnabled,
                            autoSendImmediately = telegramAutoSend
                        )
                    )

                    onSaveAppPreferences(
                        AppPreferences(
                            stealthMode = stealthMode,
                            dataSaverMode = dataSaverMode,
                            hideAppIcon = hideAppIcon
                        )
                    )

                    val parsedPort = port.toIntOrNull() ?: 587
                    onSaveSmtp(
                        SmtpConfig(
                            host = host.trim(),
                            port = parsedPort,
                            useTls = useTls,
                            senderEmail = senderEmail.trim(),
                            senderPassword = senderPassword.trim(),
                            recipientEmail = recipientEmail.trim(),
                            autoSendImmediately = smtpAutoSend
                        )
                    )
                    onDismiss()
                }
            ) {
                Text("Guardar Cambios")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cerrar")
            }
        }
    )
}
