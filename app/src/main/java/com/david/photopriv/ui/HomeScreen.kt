package com.david.photopriv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.david.photopriv.data.model.BackupStatus
import com.david.photopriv.data.model.TrackedPhoto
import com.david.photopriv.data.preferences.AppPreferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: PhotoPrivViewModel) {
    val activeSession by viewModel.activeSession.collectAsState()
    val sessionPhotos by viewModel.sessionPhotos.collectAsState()
    val smtpConfig by viewModel.smtpConfig.collectAsState()
    val telegramConfig by viewModel.telegramConfig.collectAsState()
    val appPreferences by viewModel.appPreferences.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    val stats = viewModel.computeStats(sessionPhotos)
    val isSessionActive = activeSession != null

    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showSettingsDialog) {
        SettingsDialog(
            currentTelegramConfig = telegramConfig,
            currentSmtpConfig = smtpConfig,
            currentAppPreferences = appPreferences,
            onDismiss = { showSettingsDialog = false },
            onSaveTelegram = { viewModel.saveTelegramConfig(it) },
            onSaveSmtp = { viewModel.saveSmtpConfig(it) },
            onSaveAppPreferences = { viewModel.saveAppPreferences(it) },
            onTestTelegram = { token, chat, cb -> viewModel.testTelegram(token, chat, cb) }
        )
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PhotoPriv",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                },
                actions = {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        IconButton(onClick = { viewModel.refreshScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualizar estado")
                        }
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Configuración")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Control de la Sesión
            item {
                SessionControlCard(
                    isActive = isSessionActive,
                    onStartClick = { viewModel.startExtractionSession() },
                    onStopClick = { viewModel.stopExtractionSession() }
                )
            }

            // 2. Tarjetas de Estadísticas
            item {
                StatsGrid(stats = stats)
            }

            // 3. Asistente Paso a Paso y Alertas Situacionales
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimatedVisibility(visible = stats.unprotected > 0) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = Color(0xFFC62828),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${stats.unprotected} archivo(s) expuestos en la galería pública",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFB71C1C),
                                        fontSize = 14.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "🛡️ Archivos aislados en la bóveda secreta interna. ¡Puedes re-bloquearlos en Google Fotos de inmediato sin esperar a que termine la subida!",
                                    fontSize = 12.sp,
                                    color = Color(0xFF7F1D1D)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = { viewModel.openNextPendingMedia() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB91C1C)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Re-bloquear Siguiente en Google Fotos (${stats.unprotected} restantes)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = stats.total > 0 && stats.unprotected == 0) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "¡100% Protegidos!",
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text(
                                        text = "Todos los archivos extraídos han regresado a la Carpeta Bloqueada.",
                                        fontSize = 12.sp,
                                        color = Color(0xFF1B5E20)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Encabezado de Lista Auditada
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Archivos Auditados (${sessionPhotos.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (stats.failed > 0 || (stats.backedUp < stats.total && stats.total > 0)) {
                        val retryLabel = if (stats.failed > 0) "Reintentar (${stats.failed} fallidos)" else "Reintentar Envío"
                        OutlinedButton(
                            onClick = { viewModel.retryBackup() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (stats.failed > 0) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(retryLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            if (sessionPhotos.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (isSessionActive)
                                    "Esperando que saques fotos o videos de la Carpeta Bloqueada..."
                                else
                                    "Inicia una sesión para comenzar a auditar.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(sessionPhotos, key = { it.mediaStoreId }) { photo ->
                    PhotoChecklistCard(
                        photo = photo,
                        onOpenInGallery = { viewModel.openInGooglePhotos(photo) },
                        onRetryClick = { viewModel.retrySinglePhoto(photo) }
                    )
                }
            }
        }
    }
}

@Composable
fun SessionControlCard(
    isActive: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFF1E3A8A) else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF22C55E) else Color.Gray)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isActive) "CENTINELA ACTIVO (FOTOS + VIDEOS)" else "CENTINELA EN ESPERA",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isActive)
                    "Monitoreando la galería pública. Desbloquea la Carpeta Bloqueada en Google Fotos y extrae los archivos deseados. Se enviarán individualmente a Telegram al instante."
                else
                    "Pulsa 'Iniciar Extracción' antes de sacar archivos en Google Fotos. El filtro temporal garantiza que SOLO audite lo que extraigas en esta sesión.",
                fontSize = 13.sp,
                color = if (isActive) Color(0xFFE2E8F0) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            if (isActive) {
                Button(
                    onClick = onStopClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("🛑 Finalizar y Cerrar Sesión", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStartClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("🚀 Iniciar Sesión de Extracción", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StatsGrid(stats: SessionStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatItem(
            title = "Extraídos",
            value = stats.total.toString(),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        StatItem(
            title = "Enviados",
            value = stats.backedUp.toString(),
            color = Color(0xFF2563EB),
            modifier = Modifier.weight(1f)
        )
        StatItem(
            title = "En Riesgo",
            value = stats.unprotected.toString(),
            color = if (stats.unprotected > 0) Color(0xFFDC2626) else Color.Gray,
            modifier = Modifier.weight(1f)
        )
        StatItem(
            title = "Re-bloqueados",
            value = stats.protected.toString(),
            color = Color(0xFF16A34A),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun StatItem(title: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Text(text = title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PhotoChecklistCard(
    photo: TrackedPhoto,
    onOpenInGallery: () -> Unit,
    onRetryClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (photo.isReProtected) Color(0xFFF0FDF4) else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenInGallery() }
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Miniatura con Coil o Icono según tipo
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.LightGray),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(photo.uriString)
                        .crossfade(true)
                        .build(),
                    contentDescription = photo.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                if (photo.isVideo) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (photo.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (photo.isVideo) Color(0xFF7C3AED) else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = photo.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = "${photo.fileSizeBytes / 1024} KB • ${photo.mimeType}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Badges
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (photo.isReProtected) {
                        BadgePill(
                            text = "🔒 Re-bloqueado",
                            bgColor = Color(0xFFDCFCE7),
                            textColor = Color(0xFF15803D)
                        )
                    } else {
                        BadgePill(
                            text = "🔴 Expuesto",
                            bgColor = Color(0xFFFEE2E2),
                            textColor = Color(0xFFB91C1C)
                        )
                    }

                    if (photo.localStagingPath != null) {
                        BadgePill(
                            text = "🛡️ En Bóveda",
                            bgColor = Color(0xFFEDE9FE),
                            textColor = Color(0xFF6D28D9)
                        )
                    }

                    when (photo.backupStatus) {
                        BackupStatus.BACKED_UP -> {
                            BadgePill(
                                text = "✈️ Enviado",
                                bgColor = Color(0xFFDBEAFE),
                                textColor = Color(0xFF1D4ED8)
                            )
                        }
                        BackupStatus.BACKING_UP -> {
                            val statusText = if (!photo.lastError.isNullOrBlank() && photo.lastError.startsWith("Enviando")) {
                                "⏳ ${photo.lastError}"
                            } else {
                                "⏳ Enviando..."
                            }
                            BadgePill(
                                text = statusText,
                                bgColor = Color(0xFFFEF3C7),
                                textColor = Color(0xFFB45309)
                            )
                        }

                        BackupStatus.FAILED -> {
                            BadgePill(
                                text = "⚠️ Falló (Reintentar)",
                                bgColor = Color(0xFFFEE2E2),
                                textColor = Color(0xFFB91C1C),
                                onClick = onRetryClick
                            )
                        }
                        BackupStatus.PENDING -> {
                            BadgePill(
                                text = "⏳ Pendiente",
                                bgColor = Color(0xFFF3F4F6),
                                textColor = Color(0xFF4B5563)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Botón directo para abrir en Google Fotos
            IconButton(
                onClick = onOpenInGallery,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Abrir en Fotos",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun BadgePill(
    text: String,
    bgColor: Color,
    textColor: Color,
    onClick: (() -> Unit)? = null
) {
    val modifier = Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(bgColor)
        .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
        .padding(horizontal = 6.dp, vertical = 2.dp)

    Box(modifier = modifier) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}
