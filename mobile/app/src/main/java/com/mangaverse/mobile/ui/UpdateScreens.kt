package com.mangaverse.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangaverse.mobile.app
import com.mangaverse.mobile.update.AppRelease
import com.mangaverse.mobile.update.AppUpdater.State
import kotlinx.coroutines.launch

private fun sizeText(bytes: Long) = if (bytes > 0) " (%.1f MB)".format(bytes / 1_048_576.0) else ""

/**
 * Avisos de atualização por cima de qualquer tela: janela quando há versão nova (ou falta permissão) e um cartão com o
 * progresso enquanto baixa/instala.
 */
@Composable
fun UpdatePrompt() {
    val ctx = LocalContext.current
    val updater = ctx.app.updater
    val state by updater.state.collectAsState()

    when (val s = state) {
        is State.Available -> ReleaseDialog(
            s.release,
            onUpdate = { updater.downloadAndInstall(s.release) },
            onLater = { updater.dismiss() },
            onSkip = { updater.skip(s.release) },
        )
        is State.NeedsPermission -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Permitir atualizações") },
            text = {
                Text(
                    "Para o MangaVerse instalar a versão ${s.release.version} sozinho, o Android precisa que você permita " +
                        "“Instalar apps desconhecidos” para ele. É só ligar a opção e voltar aqui.",
                )
            },
            confirmButton = {
                TextButton({ ctx.startActivity(updater.permissionIntent()) }) { Text("Abrir ajustes") }
                TextButton({ updater.downloadAndInstall(s.release) }) { Text("Já permiti") }
            },
            dismissButton = { TextButton({ updater.dismiss() }) { Text("Depois") } },
        )
        is State.Failed -> if (s.release != null) AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("A atualização falhou") },
            text = { Text(s.message) },
            confirmButton = { TextButton({ updater.downloadAndInstall(s.release) }) { Text("Tentar de novo") } },
            dismissButton = { TextButton({ updater.dismiss() }) { Text("Fechar") } },
        )
        is State.Downloading, is State.Installing -> Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 88.dp), contentAlignment = Alignment.BottomCenter) {
            val (title, progress) = when (s) {
                is State.Downloading -> "Baixando a versão ${s.release.version}…" to s.progress
                is State.Installing -> "Instalando a versão ${s.release.version}…" to null
            }
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.SystemUpdate, null, tint = AccentLight)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (progress != null) {
                        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(top = 6.dp), color = AccentLight)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = AccentLight)
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun ReleaseDialog(release: AppRelease, onUpdate: () -> Unit, onLater: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Default.SystemUpdate, null, tint = AccentLight) },
        title = { Text("Nova versão ${release.version}") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("Você está na versão ${LocalContext.current.app.updater.currentVersion}.", color = Muted, fontSize = 13.sp)
                if (release.notes.isNotBlank()) Text(release.notes, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { Button(onUpdate) { Text("Atualizar${sizeText(release.size)}") } },
        dismissButton = {
            Row {
                TextButton(onSkip) { Text("Pular versão", color = Muted) }
                TextButton(onLater) { Text("Depois") }
            }
        },
    )
}

/** Ajustes gerais: atualizações do app e onde fica o modo +18. */
@Composable
fun SettingsScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val updater = app.updater
    val state by updater.state.collectAsState()
    val settings by updater.settings.collectAsState()
    val extSettings by app.extensions.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton({ nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            title = { Text("Ajustes", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Atualizações do app", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).padding(14.dp)) {
                Text("MangaVerse ${updater.currentVersion}", fontWeight = FontWeight.SemiBold)
                val status = when (val s = state) {
                    State.Idle -> "As versões novas são procuradas nas Releases do GitHub do projeto."
                    State.Checking -> "Procurando…"
                    State.UpToDate -> "Você já está na versão mais nova."
                    is State.Available -> "Versão ${s.release.version} disponível."
                    is State.Downloading -> "Baixando a versão ${s.release.version}… ${(s.progress * 100).toInt()}%"
                    is State.Installing -> "Instalando a versão ${s.release.version}…"
                    is State.NeedsPermission -> "Falta permitir que o MangaVerse instale apps."
                    is State.Failed -> s.message
                }
                Text(status, color = if (state is State.Failed) Color(0xFFF87171) else Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val busy = state is State.Checking || state is State.Downloading || state is State.Installing
                    val available = (state as? State.Available)?.release
                    if (available != null) {
                        Button({ updater.downloadAndInstall(available) }) { Text("Atualizar para ${available.version}") }
                    } else {
                        OutlinedButton({ scope.launch { updater.check(manual = true) } }, enabled = !busy) { Text("Procurar atualização") }
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AccentLight)
                }
            }
            ToggleRow(
                "Procurar ao abrir o app", "Confere se há versão nova (no máximo a cada 6 horas).", settings.checkOnStart,
            ) { updater.updateSettings(settings.copy(checkOnStart = it)) }
            ToggleRow(
                "Baixar e instalar sozinho",
                "Quando houver versão nova, já baixa e instala. Na primeira vez o Android pede para confirmar; depois, a partir do Android 12, instala sem perguntar.",
                settings.autoInstall, enabled = settings.checkOnStart,
            ) { updater.updateSettings(settings.copy(autoInstall = it)) }

            Text("Conteúdo", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface2).clickable { nav.extensions() }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Modo +18: " + if (extSettings.adultMode) "ligado" else "desligado", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Extensões de hentai/conteúdo adulto ficam separadas e só funcionam com o modo ligado. Muda em Extensões → ícone de ajustes.",
                        color = Muted, fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = if (enabled) Color.Unspecified else Muted)
            Text(text, color = Muted, fontSize = 12.sp)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
