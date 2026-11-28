package com.mangaverse.mobile.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.mangaverse.mobile.BuildConfig
import com.mangaverse.mobile.app
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Headers
import okio.buffer
import okio.sink
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/** Versão publicada numa Release do GitHub com o APK do app. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val size: Long, val page: String)

/**
 * Atualização do próprio app: procura nas Releases do GitHub ([BuildConfig.UPDATE_REPO]) uma versão mais nova que
 * tenha um `.apk`, baixa, confere se é o mesmo app com a mesma assinatura e instala pelo PackageInstaller.
 *
 * O Android pede confirmação na primeira atualização; a partir do Android 12, quando o próprio app instalou a versão
 * atual, as seguintes podem ser instaladas sem perguntar.
 */
class AppUpdater(private val context: Context, private val network: NetworkHelper, private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val progress: Float) : State
        data class Installing(val release: AppRelease) : State
        /** O usuário precisa permitir "instalar apps desconhecidos" para o MangaVerse. */
        data class NeedsPermission(val release: AppRelease) : State
        data class Failed(val message: String, val release: AppRelease?) : State
    }

    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val dir = File(context.cacheDir, "updates").apply { mkdirs() }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    data class Settings(val checkOnStart: Boolean, val autoInstall: Boolean)

    private val _settings = MutableStateFlow(Settings(prefs.getBoolean("check_on_start", true), prefs.getBoolean("auto_install", true)))
    val settings: StateFlow<Settings> = _settings

    fun updateSettings(s: Settings) {
        prefs.edit().putBoolean("check_on_start", s.checkOnStart).putBoolean("auto_install", s.autoInstall).apply()
        _settings.value = s
    }

    /** Versão que o usuário mandou ignorar (não aparece de novo sozinha). */
    var skippedVersion: String?
        get() = prefs.getString("skipped", null)
        private set(v) = prefs.edit().putString("skipped", v).apply()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Na abertura do app: procura no máximo a cada 6 h e, se "instalar sozinho" estiver ligado, já baixa e instala. */
    fun checkOnStart() {
        cleanup()
        if (!_settings.value.checkOnStart) return
        val last = prefs.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return
        scope.launch {
            val release = check(manual = false) ?: return@launch
            if (release.version == skippedVersion) { _state.value = State.Idle; return@launch }
            if (_settings.value.autoInstall) downloadAndInstall(release)
        }
    }

    /** Procura uma versão nova. Devolve a release se houver uma mais nova que a instalada. */
    suspend fun check(manual: Boolean = true): AppRelease? {
        if (_state.value is State.Downloading || _state.value is State.Installing) return null
        _state.value = State.Checking
        return try {
            val release = withContext(Dispatchers.IO) { latestRelease() }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            if (release != null && isNewer(release.version, currentVersion)) {
                if (manual && release.version == skippedVersion) skippedVersion = null
                _state.value = State.Available(release)
                release
            } else {
                _state.value = State.UpToDate
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao procurar atualização", e)
            _state.value = if (manual) State.Failed("Não foi possível procurar atualizações: ${e.message ?: e.javaClass.simpleName}", null) else State.Idle
            null
        }
    }

    fun skip(release: AppRelease) {
        skippedVersion = release.version
        _state.value = State.Idle
    }

    fun dismiss() {
        if (_state.value !is State.Downloading && _state.value !is State.Installing) _state.value = State.Idle
    }

    private suspend fun latestRelease(): AppRelease? {
        val url = "https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases?per_page=15"
        val headers = Headers.headersOf("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28")
        val text = network.client.newCall(GET(url, headers, CacheControl.FORCE_NETWORK)).awaitSuccess().use { it.body.string() }
        val arr = JSONArray(text)
        // a release mais nova (não rascunho, não pré-lançamento) que tenha um APK
        val releases = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
            .mapNotNull { r ->
                val assets = r.optJSONArray("assets") ?: return@mapNotNull null
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) }
                    ?: return@mapNotNull null
                val version = versionOf(r.optString("tag_name")).ifBlank { versionOf(r.optString("name")) }
                if (version.isBlank()) return@mapNotNull null
                AppRelease(version, r.optString("body").trim(), apk.optString("browser_download_url"), apk.optLong("size"), r.optString("html_url"))
            }
        return releases.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
    }

    /** Baixa o APK e manda instalar. */
    fun downloadAndInstall(release: AppRelease) {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedsPermission(release)
            return
        }
        scope.launch(Dispatchers.IO) {
            val file = File(dir, "MangaVerse-${release.version}.apk")
            try {
                _state.value = State.Downloading(release, 0f)
                if (!file.isFile || (release.size > 0 && file.length() != release.size)) download(release, file)
                verify(file)
                _state.value = State.Installing(release)
                install(file)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao atualizar", e)
                file.delete()
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    private suspend fun download(release: AppRelease, file: File) {
        val tmp = File(dir, file.name + ".part")
        network.client.newCall(GET(release.apkUrl, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().use { res ->
            val body = res.body
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            var done = 0L
            tmp.sink().buffer().use { out ->
                val src = body.source()
                val buf = okio.Buffer()
                while (true) {
                    val n = src.read(buf, 64 * 1024)
                    if (n == -1L) break
                    out.write(buf, n)
                    done += n
                    if (total > 0) _state.value = State.Downloading(release, (done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        check(tmp.renameTo(file)) { "Não foi possível salvar o arquivo baixado" }
    }

    /** Recusa APKs de outro app, de versão igual/mais velha ou assinados com outra chave. */
    private fun verify(file: File) {
        val pm = context.packageManager
        val info = archiveInfo(file) ?: error("O arquivo baixado não é um APK válido")
        check(info.packageName == context.packageName) { "O APK é de outro app (${info.packageName})" }
        val current = pm.getPackageInfo(context.packageName, signingFlags())
        check(versionCode(info) > versionCode(current)) { "O APK baixado não é mais novo que a versão instalada" }
        val mine = signatures(current)
        val theirs = signatures(info)
        check(theirs.isNotEmpty() && theirs.any { it in mine }) {
            "O APK foi assinado com outra chave — o Android não deixaria instalar por cima. Instale manualmente."
        }
    }

    private fun install(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("MangaVerse.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    /** Resultado vindo do PackageInstaller (pelo [InstallReceiver]). */
    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val release = (_state.value as? State.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Idle // normalmente o app já foi reiniciado
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { State.Available(it) } ?: State.Idle
            else -> _state.value = State.Failed("A instalação falhou: ${message ?: "erro $status"}", release)
        }
    }

    /** Abre a tela do sistema para permitir que o MangaVerse instale atualizações. */
    fun permissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Apaga APKs baixados de versões que já estão instaladas. */
    private fun cleanup() {
        dir.listFiles().orEmpty().forEach { f ->
            val v = versionOf(f.nameWithoutExtension.removeSuffix(".apk"))
            if (f.name.endsWith(".part") || v.isBlank() || !isNewer(v, currentVersion)) f.delete()
        }
    }

    private fun archiveInfo(file: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags())

    @Suppress("DEPRECATION")
    private fun signingFlags() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo ?: return emptySet()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return sigs.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    companion object {
        private const val TAG = "AppUpdater"

        /** "mobile-v2.3", "v2.3.1", "MangaVerse 2.3" → "2.3" / "2.3.1". */
        fun versionOf(text: String): String = Regex("(\\d+(?:\\.\\d+)+|\\d+)").find(text)?.value.orEmpty()

        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        fun isNewer(candidate: String, current: String) = compareVersions(versionOf(candidate), versionOf(current)) > 0
    }
}

/** Recebe o resultado da sessão do PackageInstaller. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        context.app.updater.onInstallStatus(status, message, confirm)
    }
}
