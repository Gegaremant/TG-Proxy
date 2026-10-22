package org.flowseal.tgwsproxy.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.proxy.CfDomains
import org.flowseal.tgwsproxy.proxy.Stats
import org.flowseal.tgwsproxy.service.ProxyConfig
import org.flowseal.tgwsproxy.service.ProxyService
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.os.Process

val DEFAULT_SNI_LIST = listOf(
    "stats.vk-portal.net", "sun6-21.userapi.com", "r3.mail.ru", "api.vk.com", "m.vk.com",
    "akamai.com", "okcdn.ru", "gazprombank.ru", "www.unicreditbank.ru", "cdn.gpb.ru", "max.ru",
    "yandex.ru", "gosuslugi.ru", "kremlin.ru", "vk.com", "ok.ru", "mail.ru", "dzen.ru",
    "ozon.ru", "wildberries.ru", "sberbank.ru", "alfabank.ru", "tbank.ru", "nspk.ru",
    "mts.ru", "megafon.ru", "beeline.ru", "tele2.ru", "avito.ru", "rutube.ru", "kinopoisk.ru",
    "2gis.ru", "magnit.ru", "dev.max.ru", "mc.yandex.ru", "minjust.gov.ru", "img.avito.st",
    "m.ok.ru", "vk.ru", "m.vk.ru", "music.vk.com", "music.ok.ru", "music.m.vk.com",
    "ya.ru", "25111.ms.vk.com", "api.mycdn.me", "i.mycdn.me", "mycdn.me", "persiq.vk.com",
    "connect.ok.ru", "extimp.userapi.com", "penis.userapi.com", "tywidv1.userapi.com",
    "fptn.vpn.mradx.net", "sosok.vk.com", "fptn.vpn.vk.com", "static.vk.com",
    "cdn1.ozonusercontent.com", "ir.ozone.ru", "xyz.ozone.ru", "cdnvideo.v.ozone.ru",
    "vr-1.ozone.ru", "ntp.ix.ru", "mos.ru", "web.max.ru", "742231.ms.ok.ru", "www.mos.ru",
    "goya.rutube.ru", "counter.yadro.ru", "eh.vk.com"
)

fun updateLocale(context: Context, language: String): Context {
    if (language == "auto") return context
    val locale = Locale(language)
    Locale.setDefault(locale)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    return context.createConfigurationContext(config)
}

/**
 * Probe a sample of SNI domains from [DEFAULT_SNI_LIST] and return the fastest
 * one (used as the fake-TLS domain). Shared by the main-screen "Auto Config"
 * button and the optional auto-check performed on Start.
 */
suspend fun runSniAutoConfig(config: ProxyConfig): String = withContext(Dispatchers.IO) {
    val candidates = DEFAULT_SNI_LIST.shuffled().take(10)
    // Probe in parallel: sequentially it took up to 1.5s per dead domain.
    val results = coroutineScope {
        candidates.map { sni ->
            async {
                try {
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    socket.connect(InetSocketAddress(sni, 443), 1500)
                    socket.close()
                    val time = System.currentTimeMillis() - start
                    sni to time
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }
    val fastest = results.minByOrNull { it.second }
    fastest?.first ?: config.fakeTlsDomain.ifBlank { "sberbank.ru" }
}

/**
 * Full auto configuration:
 *  - pick the fastest fake-TLS SNI domain (latency probe),
 *  - check which CF-proxy domains actually answer and drop the dead ones.
 *
 * Roughly half of the public CF domains are unreachable at any moment, and a
 * dead one costs a full connect timeout on every new client connection. The
 * desktop app does the same check in its settings ("Автонастройка").
 */
suspend fun runAutoConfig(
    config: ProxyConfig,
    onProgress: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    onProgress("SNI: probing...")
    val bestSni = runSniAutoConfig(config)
    config.fakeTlsDomain = bestSni

    val working = CfDomains.filterWorkingDomains { done, total, domain, ok ->
        onProgress("$done/$total $domain: ${if (ok) "ok" else "fail"}")
    }
    if (working.isNotEmpty()) {
        CfDomains.applyWorkingDomains(working)
    }
    "SNI=$bestSni, CF=${working.size}/${CfDomains.domains.size}"
}

class MainActivity : ComponentActivity() {

    private var proxyService: ProxyService? = null
    private var bound = mutableStateOf(false)

    /**
     * Apply the saved locale at the *base* level so every context created
     * from this activity (Compose, Toast, startActivity) shares it.
     * The old approach — swapping LocalContext with a createConfigurationContext
     * wrapper — produced a non-Activity context after a language switch, which
     * crashed as soon as anything called startActivity() (e.g. the GitHub button).
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = try {
            ProxyConfig(newBase).language
        } catch (_: Exception) {
            "auto"
        }
        super.attachBaseContext(updateLocale(newBase, lang))
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            proxyService = (binder as ProxyService.LocalBinder).getService()
            bound.value = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService = null
            bound.value = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val config = remember { ProxyConfig(this) }
            var themeMode by remember { mutableStateOf(config.themeMode) }

            TgWsProxyTheme(themeMode = themeMode) {
                MainScreen(
                    context = this,
                    bound = bound,
                    getService = { proxyService },
                    onStart = { startProxyService() },
                    onStop = { stopProxyService() },
                    onRestart = { restartProxyService() },
                    config = config,
                    themeMode = themeMode,
                    onThemeModeChanged = { newMode ->
                        config.themeMode = newMode
                        themeMode = newMode
                    },
                    language = config.language,
                    onLanguageChanged = { newLang ->
                        if (newLang != config.language) {
                            config.language = newLang
                            // Apply the new locale by recreating the activity:
                            // attachBaseContext re-reads it from prefs.
                            recreate()
                        }
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, ProxyService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound.value) {
            unbindService(connection)
            bound.value = false
        }
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyService::class.java).apply {
            action = ProxyService.ACTION_STOP
        }
        startService(intent)
    }

    private fun restartProxyService() {
        proxyService?.restartProxy()
    }
}

// --- Theme ---

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF3390EC),
    onPrimary = Color.White,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF707579),
    outline = Color(0xFFDADCE0),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF5EA8F0),
    onPrimary = Color.White,
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFA0A4A8),
    outline = Color(0xFF444444),
)

val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun TgWsProxyTheme(themeMode: String = "system", content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalIsDarkTheme provides isDark) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

// --- Main Screen ---

/** Один пункт чек-листа готовности: что проверяем, статус и кнопка «Включить». */
data class ReadinessItem(
    val title: String,
    val done: Boolean,
    val action: () -> Unit
)

/**
 * Проверка готовности к стабильной работе. Вызывается на каждом запуске
 * приложения и далее по кругу, пока пользователь не включит всё перечисленное:
 * энергосбережение, сеть без ограничений, автозапуск, автоподключение,
 * работа в фоне, показ уведомлений.
 */
@SuppressLint("NewApi")
fun computeReadinessItems(
    context: Context,
    config: ProxyConfig,
    requestNotifications: () -> Unit
): List<ReadinessItem> {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun openSettings(action: String) {
        try {
            context.startActivity(Intent(action).apply {
                data = Uri.parse("package:${context.packageName}")
            })
        } catch (_: Exception) {}
    }

    // Энергосбережение: приложение должно быть в списке исключений оптимизации.
    val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)

    // Сеть без ограничений: экономия трафика либо выключена системно,
    // либо для приложения есть исключение.
    val dataSaverOk = try {
        cm.getRestrictBackgroundStatus() != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: Throwable) {
        // Метод есть не на всех версиях Android — не блокируем запуск.
        true
    }

    // Автозапуск и автоподключение — наши собственные переключатели.
    val autostartOk = config.autostart
    val autoConnectOk = config.autoConnect

    // Работа в фоне: ограничение фона (Battery → Unrestricted), API 28+.
    val backgroundOk = Build.VERSION.SDK_INT < 28 || !am.isBackgroundRestricted()

    // Уведомления нужны постоянной службы, чтобы её не убивала система.
    val notificationsOk = nm.areNotificationsEnabled()

    return listOf(
        ReadinessItem(context.getString(R.string.readiness_battery), batteryOk) {
            try {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(i)
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        },
        ReadinessItem(context.getString(R.string.readiness_data_saver), dataSaverOk) {
            // ACTION_DATA_SAVER_SETTINGS в SDK нет — открываем экран "Без
            // ограничений" с пакетом приложения, иначе настройки сети.
            try {
                val i = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(i)
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                } catch (_: Exception) {}
            }
        },
        ReadinessItem(context.getString(R.string.readiness_autostart), autostartOk) {
            config.autostart = true
        },
        ReadinessItem(context.getString(R.string.readiness_autoconnect), autoConnectOk) {
            config.autoConnect = true
        },
        ReadinessItem(context.getString(R.string.readiness_background), backgroundOk) {
            openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        },
        ReadinessItem(context.getString(R.string.readiness_notifications), notificationsOk) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestNotifications()
            } else {
                try {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    })
                } catch (_: Exception) {}
            }
        }
    )
}

/** Чек-лист готовности: галочки по каждому пункту + кнопка для недостающих. */
@Composable
fun ReadinessCard(items: List<ReadinessItem>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.readiness_title),
                style = MaterialTheme.typography.titleSmall
            )
            items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (item.done) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        null,
                        tint = if (item.done) Color(0xFF43A047) else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!item.done) {
                        TextButton(
                            onClick = item.action,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.readiness_enable),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    context: Context,
    bound: MutableState<Boolean>,
    getService: () -> ProxyService?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    config: ProxyConfig,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    language: String,
    onLanguageChanged: (String) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    var statsText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var autoConnectTriggered by remember { mutableStateOf(false) }
    var readinessItems by remember { mutableStateOf(listOf<ReadinessItem>()) }
    var showReadinessPrompt by remember { mutableStateOf(false) }
    var readinessPromptSeen by remember { mutableStateOf(false) }

    val requestNotificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    
    val scope = rememberCoroutineScope()
    var savedToast by remember { mutableStateOf<String?>(null) }
    savedToast?.let {
        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        savedToast = null
    }

    val saveLogsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = logs.joinToString("\n")
        if (text.isEmpty()) {
            savedToast = context.getString(R.string.logs_empty)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                        true
                    } ?: false
                }
                savedToast = if (saved) {
                    context.getString(R.string.logs_saved, uri.lastPathSegment ?: "")
                } else {
                    context.getString(R.string.logs_save_failed)
                }
            } catch (_: Exception) {
                savedToast = context.getString(R.string.logs_save_failed)
            }
        }
    }

    LaunchedEffect(bound.value) {
        while (true) {
            val service = getService()
            isRunning = service?.isProxyRunning == true
            // Автоподключение: при открытии приложения прокси стартует сам
            // (по умолчанию выключено, включается на первом запуске).
            if (config.autoConnect && !autoConnectTriggered && bound.value && service != null) {
                autoConnectTriggered = true
                if (!isRunning) {
                    onStart()
                }
            }
            if (isRunning) {
                val s = service?.stats
                if (s != null) {
                    statsText = s.summary()
                }
                logs = service?.getRecentLogs() ?: emptyList()
            } else {
                statsText = ""
                // Проверка готовности: на каждый запуск и далее по кругу,
                // пока пользователь не включит всё перечисленное.
                readinessItems = computeReadinessItems(context, config, {
                    requestNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
                if (!readinessPromptSeen) {
                    readinessPromptSeen = true
                    if (readinessItems.any { !it.done }) {
                        showReadinessPrompt = true
                    }
                }
            }
            delay(1000)
        }
    }

    if (showReadinessPrompt) {
        val missing = readinessItems.filter { !it.done }
        if (missing.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showReadinessPrompt = false },
                title = { Text(stringResource(R.string.readiness_missing_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.readiness_missing_text),
                            style = MaterialTheme.typography.bodySmall
                        )
                        missing.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Cancel,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(item.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = item.action) {
                                    Text(stringResource(R.string.readiness_enable))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showReadinessPrompt = false }) {
                        Text(stringResource(R.string.readiness_later))
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "TG FREE with 🖤 From KatYa && Gegaremant",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 13.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = {
                        val newMode = if (themeMode == "dark") "light" else "dark"
                        onThemeModeChanged(newMode)
                    }) {
                        Icon(
                            if (themeMode == "dark") Icons.Default.LightMode else Icons.Default.DarkMode,
                            "Toggle theme"
                        )
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusCard(isRunning, statsText, config)

            var isStarting by remember { mutableStateOf(false) }
            var isAutoConfiguring by remember { mutableStateOf(false) }
            var autoConfigStatus by remember { mutableStateOf("") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isRunning) {
                    Button(
                        onClick = {
                            if (isStarting) return@Button
                            isStarting = true
                            scope.launch {
                                // Optional auto connection check (settings toggle):
                                // probe SNI domains and pick the fastest one before start.
                                if (config.autoCheck) {
                                    config.fakeTlsDomain = runSniAutoConfig(config)
                                }
                                onStart()
                                isStarting = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isStarting && config.autoCheck -> stringResource(R.string.auto_config_running)
                                else -> stringResource(R.string.start_proxy)
                            }
                        )
                    }
                } else {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.stop_proxy))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onRestart,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.reconnect))
                    }
                }
            }

            // Пока прокси не запущен — показываем только чек-лист готовности.
            if (!isRunning) {
                ReadinessCard(readinessItems)
            }

            // Кнопки автонастройки и ссылки — только при запущенном прокси
            // (пока соединения нет, их показывать не нужно).
            if (isRunning) {
            // --- Автонастройка: доступна во всех режимах (работает и при
            // запущенном прокси — переключает эндпоинты на лету).
            OutlinedButton(
                onClick = {
                    if (isAutoConfiguring) return@OutlinedButton
                    isAutoConfiguring = true
                    autoConfigStatus = context.getString(R.string.auto_config_running)
                    scope.launch {
                        val result = runAutoConfig(config) { status ->
                            autoConfigStatus = status
                        }
                        autoConfigStatus = ""
                        isAutoConfiguring = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.auto_config_done) + ": " + result,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isAutoConfiguring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.auto_config_running))
                } else {
                    Text(stringResource(R.string.auto_config))
                }
            }

            if (autoConfigStatus.isNotEmpty()) {
                Text(
                    autoConfigStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- Ссылка на прокси: доступна во всех режимах.
            val clipboardManager = LocalClipboardManager.current
            val generateProxyLink = {
                val serverIp = "127.0.0.1"
                // The real listening port (the configured one can be busy and
                // the server then takes the next free one).
                val actualPort = getService()?.listenPort ?: config.port
                val base = "tg://proxy?server=$serverIp&port=$actualPort&secret="
                val secretPart = if (config.fakeTlsDomain.isNotEmpty()) {
                    "ee" + config.secret + config.fakeTlsDomain.toByteArray().joinToString("") { "%02x".format(it) }
                } else {
                    "dd" + config.secret
                }
                base + secretPart
            }

            OutlinedButton(
                onClick = {
                    val url = generateProxyLink()
                    clipboardManager.setText(AnnotatedString(url))
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.copy_link))
            }

            OutlinedButton(
                onClick = {
                    try {
                        val uri = android.net.Uri.parse(generateProxyLink())
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Telegram not installed", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.open_in_telegram))
            }
            } // if (isRunning)

            if (showSettings) {

                SettingsPanel(context, config, onRestart, { showSettings = false }, themeMode, onThemeModeChanged, language, onLanguageChanged)
            }

            if (config.showLogs && logs.isNotEmpty()) {
                var logsExpanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { logsExpanded = !logsExpanded },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.logs) + if (logsExpanded) " (Collapse)" else " (Expand)", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                saveLogsLauncher.launch("tg_proxy_logs_${System.currentTimeMillis()}.txt")
                            }) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.save_logs))
                            }
                        }
                        if (logsExpanded) {
                            Spacer(Modifier.height(8.dp))
                            for (line in logs.takeLast(50)) {
                                Text(
                                    line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // GitHub Link
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_github),
                contentDescription = stringResource(R.string.github_repo),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(30.dp)
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Gegaremant/TG-Proxy"))
                        context.startActivity(intent)
                    }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_version),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun StatusCard(isRunning: Boolean, statsText: String, config: ProxyConfig) {
    val isDark = LocalIsDarkTheme.current
    val runningBg = if (isDark) Color(0xFF1B3A1B) else Color(0xFFE8F5E9)
    val stoppedBg = if (isDark) Color(0xFF3A2E1B) else Color(0xFFFFF3E0)
    
    val statusLabel = if (isRunning) stringResource(R.string.status_running) else stringResource(R.string.status_stopped)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) runningBg else stoppedBg
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            if (isRunning) Color(0xFF4CAF50) else Color(0xFFFF9800),
                            RoundedCornerShape(6.dp)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${config.host}:${config.port}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (statsText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        statsText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    context: Context,
    config: ProxyConfig,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    language: String,
    onLanguageChanged: (String) -> Unit
) {
    var secret by remember { mutableStateOf(config.secret) }
    var fakeTlsDomain by remember { mutableStateOf(config.fakeTlsDomain) }
    var port by remember { mutableStateOf(config.port.toString()) }
    var showLogs by remember { mutableStateOf(config.showLogs) }
    var autoCheck by remember { mutableStateOf(config.autoCheck) }
    var autoConnect by remember { mutableStateOf(config.autoConnect) }
    var autostart by remember { mutableStateOf(config.autostart) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)

            // Auto connection check: probe SNI domains automatically when the
            // Start button is pressed (the standalone Auto Config button lives
            // on the main screen now).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.auto_check))
                Switch(
                    checked = autoCheck,
                    onCheckedChange = { autoCheck = it }
                )
            }

            // Auto-connect: start the proxy when the app is opened.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.auto_connect))
                Switch(
                    checked = autoConnect,
                    onCheckedChange = { autoConnect = it }
                )
            }

            // Start the proxy on device boot.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.autostart))
                Switch(
                    checked = autostart,
                    onCheckedChange = { autostart = it }
                )
            }

            Text(stringResource(R.string.language), style = MaterialTheme.typography.bodyMedium)
            val langOptions = listOf("auto" to "Auto", "en" to "EN", "ru" to "RU")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                langOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = language == value,
                        onClick = { onLanguageChanged(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, langOptions.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            val themeOptions = listOf("system" to "Sys", "light" to "Light", "dark" to "Dark")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                themeOptions.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = themeMode == value,
                        onClick = { onThemeModeChanged(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, themeOptions.size)
                    ) {
                        Text(label)
                    }
                }
            }

            var useCfProxy by remember { mutableStateOf(config.useCfProxy) }
            var cfProxyDomain by remember { mutableStateOf(config.cfProxyDomain) }
            var cfWorkerDomain by remember { mutableStateOf(config.cfWorkerDomain) }
            var dnsServers by remember { mutableStateOf(config.dnsServers) }
            var upstreamProxy by remember { mutableStateOf(config.upstreamProxy) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Включить CF-прокси")
                Switch(
                    checked = useCfProxy,
                    onCheckedChange = { useCfProxy = it }
                )
            }

            if (useCfProxy) {
                OutlinedTextField(
                    value = cfProxyDomain,
                    onValueChange = { cfProxyDomain = it },
                    label = { Text("Свой домен (например: proxy.com)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = cfWorkerDomain,
                    onValueChange = { cfWorkerDomain = it },
                    label = { Text("CF Worker домен (например: mwapp-is-super.workers.dev)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = dnsServers,
                onValueChange = { dnsServers = it },
                label = { Text("DNS для CF-доменов (через запятую)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = upstreamProxy,
                onValueChange = { upstreamProxy = it },
                label = { Text("Upstream SOCKS5 (Hiddify: 127.0.0.1:2334)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.secret)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = fakeTlsDomain,
                onValueChange = { fakeTlsDomain = it },
                label = { Text(stringResource(R.string.fake_tls_domain)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text(stringResource(R.string.port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.show_logs))
                Switch(
                    checked = showLogs,
                    onCheckedChange = { 
                        showLogs = it
                        config.showLogs = it
                    }
                )
            }

            Button(
                onClick = {
                    config.port = port.toIntOrNull() ?: 10800
                    config.secret = secret
                    config.fakeTlsDomain = fakeTlsDomain
                    config.autoCheck = autoCheck
                    config.autoConnect = autoConnect
                    config.autostart = autostart
                    config.useCfProxy = useCfProxy
                    config.cfProxyDomain = cfProxyDomain
                    config.cfWorkerDomain = cfWorkerDomain
                    config.dnsServers = dnsServers
                    config.upstreamProxy = upstreamProxy
                    onRestart()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.save))
            }
        }
    }
}
