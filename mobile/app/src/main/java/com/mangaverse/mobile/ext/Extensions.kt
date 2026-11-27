package com.mangaverse.mobile.ext

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.Enumeration
import java.util.Locale

data class RepoSource(val id: Long, val name: String, val lang: String)

/** Extensão disponível num repositório (formato do Keiyoushi/Mihon). */
data class RepoExtension(
    val name: String, val pkg: String, val apkUrl: String, val iconUrl: String?, val versionCode: Long, val versionName: String,
    val nsfw: Boolean, val lang: String, val sources: List<RepoSource>, val signingKey: String?,
    /** Aviso "NSFW" do formato novo do repositório: só extensões adultas (as comuns com algum título +18 vêm como "MIXED"). */
    val strictNsfw: Boolean = false,
)

/** Extensão só de conteúdo adulto (hentai etc.). */
val RepoExtension.adult: Boolean get() = strictNsfw || ExtensionManager.isAdult(name, pkg)

/**
 * Extensão instalada no app. As comuns ficam em `files/exts`; as só de conteúdo adulto ficam à parte, em
 * `files/exts-adult`, e só são carregadas com o modo +18 ligado.
 */
data class InstalledExtension(
    val pkg: String, val name: String, val versionCode: Long, val versionName: String, val lang: String, val nsfw: Boolean,
    val sources: List<Source>, val error: String?, val adult: Boolean = false,
)

const val KEIYOUSHI_REPO = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.json"

/**
 * Instala e carrega extensões Tachiyomi (extensions-lib 1.4–1.6) como o Mihon faz com as "extensões privadas":
 * o APK é baixado do repositório, a assinatura é conferida com a do repositório e as classes são carregadas
 * direto do arquivo, sem instalar nada no sistema.
 */
class ExtensionManager(private val context: Context, private val network: NetworkHelper, private val scope: CoroutineScope) {

    private val prefs = context.getSharedPreferences("extensions", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "exts").apply { mkdirs() }
    /** Extensões +18 ficam separadas das outras e só são lidas com o modo +18 ligado. */
    private val adultDir = File(context.filesDir, "exts-adult").apply { mkdirs() }
    private val repoCache = File(context.filesDir, "repo-cache.json")
    private val lock = Mutex()

    private val _installed = MutableStateFlow<List<InstalledExtension>>(emptyList())
    val installed: StateFlow<List<InstalledExtension>> = _installed

    private val _available = MutableStateFlow<List<RepoExtension>>(emptyList())
    val available: StateFlow<List<RepoExtension>> = _available

    private val _repoError = MutableStateFlow<String?>(null)
    val repoError: StateFlow<String?> = _repoError

    private val _loadingRepo = MutableStateFlow(false)
    val loadingRepo: StateFlow<Boolean> = _loadingRepo

    private val _ready = MutableStateFlow(false)
    /** `true` depois que as extensões instaladas foram carregadas na abertura do app. */
    val ready: StateFlow<Boolean> = _ready

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<ExtSettings> = _settings

    /**
     * [adultMode]: extensões +18 (hentai etc.) aparecem e funcionam. Vem desligado; o usuário precisa ligar de propósito
     * (com confirmação de idade). Desligado, elas não são carregadas e somem de todas as telas.
     */
    data class ExtSettings(val repos: List<String>, val langs: Set<String>, val adultMode: Boolean)

    private fun readSettings(): ExtSettings {
        // a chave antiga "nsfw" vinha ligada por padrão; o modo +18 novo começa desligado para todo mundo
        if (prefs.contains("nsfw")) prefs.edit().remove("nsfw").apply()
        return ExtSettings(
            repos = prefs.getString("repos", null)?.split('\n')?.filter { it.isNotBlank() } ?: listOf(KEIYOUSHI_REPO),
            langs = prefs.getStringSet("langs", null) ?: setOf("pt-BR", "all", "en"),
            adultMode = prefs.getBoolean("adult_mode", false),
        )
    }

    fun updateSettings(s: ExtSettings) {
        prefs.edit().putString("repos", s.repos.joinToString("\n")).putStringSet("langs", s.langs).putBoolean("adult_mode", s.adultMode).apply()
        val old = _settings.value
        _settings.value = s
        if (s.repos != old.repos) scope.launch { refreshRepo() }
        if (s.adultMode != old.adultMode) scope.launch(Dispatchers.IO) { if (s.adultMode) loadAdult() else unloadAdult() }
    }

    val adultMode: Boolean get() = _settings.value.adultMode

    fun init() {
        scope.launch(Dispatchers.IO) {
            runCatching { if (repoCache.isFile) _available.value = parseCache(repoCache.readText()) }
            loadInstalled()
            _ready.value = true
            refreshRepo()
        }
    }

    // ---------------- fontes ----------------

    /** Todas as fontes de catálogo das extensões carregadas (as +18 só entram com o modo +18 ligado). */
    fun allSources(): List<CatalogueSource> =
        _installed.value.filter { adultMode || !it.adult }.flatMap { it.sources }.filterIsInstance<CatalogueSource>()

    /** Fontes nos idiomas escolhidos (extensões "all" como MangaDex criam uma fonte por idioma). */
    fun visibleSources(): List<CatalogueSource> {
        val langs = _settings.value.langs.map { it.lowercase() }.toSet()
        return allSources().filter { val l = it.lang.lowercase(); l in langs || l == "all" || l == "other" || l == "" }
            .sortedWith(compareBy({ langOrder(it.lang) }, { it.name.lowercase() }))
    }

    fun source(id: Long): Source? = _installed.value.firstNotNullOfOrNull { e -> e.sources.firstOrNull { it.id == id } }

    /** Nome de uma fonte mesmo sem a extensão instalada (pelo índice do repositório). */
    fun sourceName(id: Long): String = source(id)?.name
        ?: _available.value.firstNotNullOfOrNull { e -> e.sources.firstOrNull { it.id == id }?.name }
        ?: "Fonte $id"

    /** Extensão do repositório que contém a fonte (para sugerir a instalação). Com o modo +18 desligado, nunca uma +18. */
    fun extensionForSource(id: Long): RepoExtension? =
        _available.value.firstOrNull { e -> e.sources.any { it.id == id } }?.takeIf { adultMode || !it.adult }

    /** Ids de todas as fontes de extensões +18 conhecidas (instaladas, já instaladas antes ou no repositório). */
    fun adultSourceIds(): Set<Long> = buildSet {
        addAll(prefs.getStringSet("adult_sources", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() })
        _installed.value.filter { it.adult }.forEach { e -> e.sources.forEach { add(it.id) } }
        _available.value.filter { it.adult }.forEach { e -> e.sources.forEach { add(it.id) } }
    }

    /** Fontes que devem sumir da biblioteca, do histórico etc. (vazio com o modo +18 ligado). */
    fun hiddenSourceIds(): Set<Long> = if (adultMode) emptySet() else adultSourceIds()

    private fun rememberAdultSources(ext: InstalledExtension) {
        if (!ext.adult || ext.sources.isEmpty()) return
        val known = prefs.getStringSet("adult_sources", emptySet()).orEmpty()
        val ids = ext.sources.map { it.id.toString() }
        if (!known.containsAll(ids)) prefs.edit().putStringSet("adult_sources", known + ids).apply()
    }

    /** Quantas extensões +18 estão instaladas (mesmo com o modo desligado, quando não aparecem). */
    fun adultInstalledCount(): Int = adultDir.listFiles { f -> f.isFile && f.extension == "ext" }?.size ?: 0

    fun updatesCount(): Int = _installed.value.filter { adultMode || !it.adult }.count { i -> _available.value.any { it.pkg == i.pkg && it.versionCode > i.versionCode } }

    // ---------------- repositório ----------------

    suspend fun refreshRepo() = withContext(Dispatchers.IO) {
        _loadingRepo.value = true
        _repoError.value = null
        val errors = mutableListOf<String>()
        val all = mutableListOf<RepoExtension>()
        val raw = JSONArray()
        for (url in _settings.value.repos) {
            try {
                val (list, cacheEntry) = fetchRepo(url)
                all += list
                raw.put(cacheEntry)
            } catch (e: Exception) {
                Log.w(TAG, "Repositório $url", e)
                errors += "${URL(url).host}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (all.isNotEmpty() || errors.isEmpty()) {
            _available.value = all.distinctBy { it.pkg }
            runCatching { repoCache.writeText(raw.toString()) }
        }
        _repoError.value = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        _loadingRepo.value = false
    }

    private suspend fun getText(url: String): String {
        val req = GET(url, cache = CacheControl.FORCE_NETWORK)
        return network.client.newCall(req).awaitSuccess().use { it.body.string() }
    }

    /** Aceita o `index.json` (formato novo), o `index.min.json` (antigo) ou o `repo.json` de um repositório. */
    private suspend fun fetchRepo(input: String): Pair<List<RepoExtension>, JSONObject> {
        val url = input.trim()
        val base = url.substringBeforeLast('/')
        val candidates = when {
            url.endsWith("index.json") || url.endsWith("index.min.json") -> listOf(url)
            else -> listOf("$base/index.json", "$base/index.min.json")
        }
        var last: Exception? = null
        for (c in candidates) {
            try {
                val text = getText(c)
                val entry = JSONObject().put("url", c).put("body", text)
                if (text.trimStart().startsWith("[")) {
                    // formato antigo: a chave de assinatura fica no repo.json
                    val key = runCatching { JSONObject(getText("${c.substringBeforeLast('/')}/repo.json")).getJSONObject("meta").getString("signingKeyFingerprint") }.getOrNull()
                    entry.put("key", key)
                }
                return parseRepo(c, text, entry.optString("key").ifBlank { null }) to entry
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("repositório inválido")
    }

    private fun parseCache(text: String): List<RepoExtension> {
        val arr = JSONArray(text)
        return (0 until arr.length()).flatMap {
            val o = arr.getJSONObject(it)
            runCatching { parseRepo(o.getString("url"), o.getString("body"), o.optString("key").ifBlank { null }) }.getOrDefault(emptyList())
        }.distinctBy { it.pkg }
    }

    private fun parseRepo(url: String, text: String, legacyKey: String?): List<RepoExtension> {
        val base = url.substringBeforeLast('/')
        return if (text.trimStart().startsWith("[")) {
            val arr = JSONArray(text)
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                val sources = o.optJSONArray("sources").objects().map { RepoSource(it.optString("id").toLongOrNull() ?: 0, it.optString("name"), it.optString("lang")) }
                RepoExtension(
                    name = o.optString("name").removePrefix("Tachiyomi: "), pkg = o.optString("pkg"), apkUrl = "$base/apk/${o.optString("apk")}",
                    iconUrl = "$base/icon/${o.optString("pkg")}.png", versionCode = o.optLong("code"), versionName = o.optString("version"),
                    nsfw = o.optInt("nsfw") == 1, lang = o.optString("lang"), sources = sources, signingKey = legacyKey,
                )
            }
        } else {
            val root = JSONObject(text)
            val key = root.optString("signingKey").ifBlank { null }
            val list = root.optJSONObject("extensionList")?.optJSONArray("extensions") ?: JSONArray()
            (0 until list.length()).map { list.getJSONObject(it) }.mapNotNull { o ->
                val lib = o.optString("extensionLib").toDoubleOrNull() ?: 0.0
                if (lib !in SUPPORTED_LIBS) return@mapNotNull null
                val res = o.optJSONObject("resources") ?: JSONObject()
                val sources = o.optJSONArray("sources").objects().map { RepoSource(it.optString("id").toLongOrNull() ?: 0, it.optString("name"), it.optString("language")) }
                val langs = sources.map { it.lang }.toSet()
                RepoExtension(
                    name = o.optString("name").removePrefix("Tachiyomi: "), pkg = o.optString("packageName"), apkUrl = res.optString("apkUrl"),
                    iconUrl = res.optString("iconUrl").ifBlank { null }, versionCode = o.optString("versionCode").toLongOrNull() ?: 0,
                    versionName = o.optString("versionName"), nsfw = o.optString("contentWarning") == "CONTENT_WARNING_NSFW", // "MIXED" (ex.: MangaDex) não conta como adulta
                    lang = if (langs.size == 1) langs.first() else "all", sources = sources, signingKey = key,
                    strictNsfw = o.optString("contentWarning") == "CONTENT_WARNING_NSFW",
                )
            }
        }
    }

    // ---------------- instalação ----------------

    /** Baixa, confere a assinatura e carrega a extensão. Lança exceção com mensagem legível em caso de erro. */
    suspend fun install(ext: RepoExtension, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        check(adultMode || !ext.adult) { "Extensão +18: ligue o modo +18 nos ajustes das extensões para instalar" }
        val tmp = File(context.cacheDir, "${ext.pkg}.apk")
        try {
            network.client.newCall(GET(ext.apkUrl)).awaitSuccess().use { res ->
                val body = res.body
                val total = body.contentLength()
                var done = 0L
                tmp.sink().buffer().use { out ->
                    val src = body.source()
                    val buf = okio.Buffer()
                    while (true) {
                        val n = src.read(buf, 64 * 1024)
                        if (n == -1L) break
                        out.write(buf, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            val info = archiveInfo(tmp) ?: error("O arquivo baixado não é um APK válido")
            check(isExtension(info)) { "${ext.pkg} não é uma extensão Tachiyomi" }
            check(info.packageName == ext.pkg) { "Pacote inesperado: ${info.packageName}" }
            val sigs = signatures(info)
            check(sigs.isNotEmpty()) { "A extensão não está assinada" }
            if (ext.signingKey != null) check(ext.signingKey in sigs) { "Assinatura diferente da do repositório — instalação recusada" }

            lock.withLock {
                val adult = ext.adult || isAdultPackage(info, ext.name)
                check(adultMode || !adult) { "Extensão +18: ligue o modo +18 nos ajustes das extensões para instalar" }
                deleteFiles(ext.pkg)
                val target = File(if (adult) adultDir else dir, "${ext.pkg}.ext")
                tmp.copyTo(target, overwrite = true)
                // Android 14+ exige que o código carregado seja somente leitura
                target.setReadOnly()
                val loaded = loadFile(target, adult)
                rememberAdultSources(loaded)
                _installed.value = (_installed.value.filter { it.pkg != ext.pkg } + loaded).sortedBy { it.name.lowercase() }
                loaded.error?.let { error("Instalada, mas não carregou: $it") }
            }
        } finally {
            tmp.delete()
        }
    }

    fun uninstall(pkg: String) {
        deleteFiles(pkg)
        _installed.value = _installed.value.filter { it.pkg != pkg }
    }

    /** Apaga todas as extensões +18 instaladas (carregadas ou não). */
    fun uninstallAllAdult() {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                adultDir.listFiles().orEmpty().forEach { it.setWritable(true); it.delete() }
                _installed.value = _installed.value.filter { !it.adult }
            }
        }
    }

    private fun deleteFiles(pkg: String) {
        listOf(dir, adultDir).forEach { d -> File(d, "$pkg.ext").takeIf { it.exists() }?.apply { setWritable(true) }?.delete() }
    }

    // ---------------- carregamento ----------------

    private fun extFiles(d: File) = d.listFiles { f -> f.isFile && f.extension == "ext" }.orEmpty().toList()

    private suspend fun loadInstalled() = lock.withLock {
        separateAdult()
        val files = extFiles(dir).map { it to false } + (if (adultMode) extFiles(adultDir).map { it to true } else emptyList())
        val loaded = withContext(Dispatchers.IO) { files.map { (f, adult) -> async { loadFile(f, adult) } }.awaitAll() }
        loaded.forEach(::rememberAdultSources)
        _installed.value = loaded.sortedBy { it.name.lowercase() }
    }

    /** Move para `exts-adult` as extensões +18 instaladas antes da separação (só lê o manifesto, não carrega código). */
    private fun separateAdult() {
        if (prefs.getBoolean("adult_separated", false)) return
        for (f in extFiles(dir)) {
            val info = runCatching { archiveInfo(f) }.getOrNull()
            val adult = if (info == null) isAdult("", f.nameWithoutExtension) else isAdultPackage(info, null)
            if (adult) {
                val target = File(adultDir, f.name)
                if (!f.renameTo(target)) Log.w(TAG, "Não consegui mover ${f.name} para exts-adult")
            }
        }
        prefs.edit().putBoolean("adult_separated", true).apply()
    }

    private suspend fun loadAdult() = lock.withLock {
        val loaded = extFiles(adultDir).map { loadFile(it, true) }
        loaded.forEach(::rememberAdultSources)
        _installed.value = (_installed.value.filter { !it.adult } + loaded).sortedBy { it.name.lowercase() }
    }

    private suspend fun unloadAdult() = lock.withLock {
        _installed.value = _installed.value.filter { !it.adult }
    }

    private fun archiveInfo(file: File): PackageInfo? {
        if (file.canWrite()) file.setReadOnly()
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_CONFIGURATIONS or PackageManager.GET_SIGNATURES or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
        return context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)?.also { pkg ->
            pkg.applicationInfo?.let {
                if (it.sourceDir == null) it.sourceDir = file.absolutePath
                if (it.publicSourceDir == null) it.publicSourceDir = file.absolutePath
            }
        }
    }

    private fun loadFile(file: File, adult: Boolean): InstalledExtension {
        val fallbackPkg = file.nameWithoutExtension
        val info = try {
            archiveInfo(file)
        } catch (e: Exception) {
            null
        } ?: return InstalledExtension(fallbackPkg, fallbackPkg, 0, "?", "", false, emptyList(), "APK inválido", adult)

        val app = info.applicationInfo
        val meta = app?.metaData
        val name = (meta?.getString("tachiyomix.name") ?: app?.let { context.packageManager.getApplicationLabel(it).toString() } ?: info.packageName)
            .removePrefix("Tachiyomi: ")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        val versionName = info.versionName.orEmpty()
        val nsfw = meta?.let { if (it.containsKey("tachiyomix.contentWarning")) it.getInt("tachiyomix.contentWarning") == 2 else it.getInt("tachiyomi.extension.nsfw") == 1 } ?: false

        fun failed(reason: String) = InstalledExtension(info.packageName, name, versionCode, versionName, "", nsfw, emptyList(), reason, adult)

        if (app == null || meta == null) return failed("APK sem metadados")
        val lib = meta.getFloat("tachiyomix.extensionLib").takeUnless { it == 0f }?.toString()?.toDouble()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
        if (lib == null || lib !in SUPPORTED_LIBS) return failed("Versão da biblioteca $lib não suportada")

        val classes = meta.getString("tachiyomi.extension.class")?.takeIf { it.isNotBlank() } ?: return failed("Classe da fonte não informada")
        return try {
            val loader = DelegateLastPathClassLoader(app.sourceDir, null, context.classLoader)
            val sources = classes.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .map { if (it.startsWith(".")) info.packageName + it else it }
                .flatMap { cls ->
                    when (val obj = Class.forName(cls, false, loader).getDeclaredConstructor().newInstance()) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> error("Tipo de classe desconhecido: ${obj.javaClass.name}")
                    }
                }
            val langs = sources.map { it.lang }.toSet()
            InstalledExtension(
                info.packageName, name, versionCode, versionName, if (langs.size == 1) langs.first() else "all", nsfw, sources, null, adult,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao carregar ${info.packageName}", e)
            val root = generateSequence(e) { it.cause }.last()
            failed(listOfNotNull(root.javaClass.simpleName, root.message).joinToString(": "))
        }
    }

    /** Extensão adulta pelo manifesto: aviso de conteúdo NSFW (formato novo) ou nome/pacote. */
    private fun isAdultPackage(info: PackageInfo, fallbackName: String?): Boolean {
        val meta = info.applicationInfo?.metaData
        val strict = meta?.containsKey("tachiyomix.contentWarning") == true && meta.getInt("tachiyomix.contentWarning") == 2
        val name = meta?.getString("tachiyomix.name")
            ?: info.applicationInfo?.let { context.packageManager.getApplicationLabel(it).toString() }
            ?: fallbackName.orEmpty()
        return strict || isAdult(name, info.packageName)
    }

    private fun isExtension(info: PackageInfo) = info.reqFeatures.orEmpty().any { it.name == "tachiyomi.extension" }

    private fun signatures(info: PackageInfo): List<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val s = info.signingInfo ?: return emptyList()
            if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    companion object {
        private const val TAG = "Extensions"
        private val SUPPORTED_LIBS = listOf(1.4, 1.5, 1.6)

        private fun langOrder(lang: String) = when (lang) {
            "pt-BR", "pt" -> 0
            "all", "other", "" -> 1
            "en" -> 2
            else -> 3
        }

        private val PT = Locale.forLanguageTag("pt-BR")

        private val ADULT = Regex(
            "hentai|sexo|\\bsex|porn|xxx|nsfw|erotic|er[oó]tico|\\becchi|doujin|lewd|\\br-?18|\\+18|18\\+|adult|nhentai|hitomi|hipercool|tsumino|pururin|luscious|imhentai|asmhentai|multporn|8muses|simply ?hentai|yaoi|yuri ?hentai",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Extensão só de conteúdo adulto pelo nome/pacote (fica separada, na aba +18, e só funciona com o modo +18 ligado).
         * Soma-se ao aviso "NSFW" do formato novo do repositório; o selo "nsfw" do formato antigo não basta, porque marca
         * também fontes comuns com algum título adulto (Mugiwaras, Manga Livre, MangaDex…).
         */
        fun isAdult(name: String, pkg: String): Boolean = ADULT.containsMatchIn(name) || ADULT.containsMatchIn(pkg.substringAfterLast('.'))

        fun langName(lang: String): String = when (lang) {
            "all" -> "Vários idiomas"
            "other", "" -> "Outros"
            "pt-BR" -> "Português (Brasil)"
            else -> Locale.forLanguageTag(lang).getDisplayName(PT).replaceFirstChar { it.titlecase(PT) }.ifBlank { lang }
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * Procura as classes primeiro no sistema, depois no APK da extensão e só então no app — assim cada extensão usa as
 * próprias cópias das bibliotecas que embute, e as classes da API (eu.kanade.tachiyomi.*) vêm do app.
 * Adaptado do Mihon (Apache 2.0).
 */
private class DelegateLastPathClassLoader(dexPath: String, librarySearchPath: String?, parent: ClassLoader) :
    PathClassLoader(dexPath, librarySearchPath, parent) {

    private val boot: ClassLoader? = Any::class.java.classLoader

    override fun loadClass(name: String?, resolve: Boolean): Class<*> {
        findLoadedClass(name)?.let { return it }
        if (boot != null) {
            try {
                return boot.loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
        }
        val fromSelf = try {
            return findClass(name)
        } catch (e: ClassNotFoundException) {
            e
        }
        return try {
            parent.loadClass(name)
        } catch (_: ClassNotFoundException) {
            throw fromSelf
        }
    }

    override fun getResource(name: String?): URL? = boot?.getResource(name) ?: findResource(name) ?: parent?.getResource(name)

    override fun getResources(name: String?): Enumeration<URL> {
        val list = buildList {
            boot?.getResources(name)?.let { addAll(it.toList()) }
            findResources(name)?.let { addAll(it.toList()) }
            parent?.getResources(name)?.let { addAll(it.toList()) }
        }
        return Collections.enumeration(list)
    }
}
