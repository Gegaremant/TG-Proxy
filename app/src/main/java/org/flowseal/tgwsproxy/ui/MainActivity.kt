package org.flowseal.tgwsproxy.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.flowseal.tgwsproxy.R
import org.flowseal.tgwsproxy.proxy.Stats
import org.flowseal.tgwsproxy.service.ProxyConfig
import org.flowseal.tgwsproxy.service.ProxyService
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import android.content.res.Configuration

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

class MainActivity : ComponentActivity() {

    private var proxyService: ProxyService? = null
    private var bound = mutableStateOf(false)

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
            var language by remember { mutableStateOf(config.language) }

            // Wrap context with new locale
            val localizedContext = remember(language) { updateLocale(this, language) }

            TgWsProxyTheme(themeMode = themeMode) {
                CompositionLocalProvider(LocalContext provides localizedContext) {
                    MainScreen(
                        context = localizedContext,
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
                        language = language,
                        onLanguageChanged = { newLang ->
                            config.language = newLang
                            language = newLang
                        }
                    )
                }
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
    var showFirstLaunchPrompt by remember { mutableStateOf(config.isFirstLaunch) }
    
    val scope = rememberCoroutineScope()

    LaunchedEffect(bound.value) {
        while (true) {
            val service = getService()
            isRunning = service?.isProxyRunning == true
            if (isRunning) {
                val s = service?.stats
                if (s != null) {
                    statsText = s.summary()
                }
                logs = service?.getRecentLogs() ?: emptyList()
            } else {
                statsText = ""
            }
            delay(1000)
        }
    }

    if (showFirstLaunchPrompt) {
        AlertDialog(
            onDismissRequest = { 
                showFirstLaunchPrompt = false
                config.isFirstLaunch = false
            },
            title = { Text(stringResource(R.string.app_name)) },
            text = { Text(stringResource(R.string.start_on_boot_prompt)) },
            confirmButton = {
                TextButton(onClick = {
                    config.autostart = true
                    showFirstLaunchPrompt = false
                    config.isFirstLaunch = false
                }) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    config.autostart = false
                    showFirstLaunchPrompt = false
                    config.isFirstLaunch = false
                }) {
                    Text(stringResource(R.string.no))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
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
                                // Auto-config
                                val bestSni = withContext(Dispatchers.IO) {
                                    var best = config.fakeTlsDomain.ifBlank { "sberbank.ru" }
                                    var bestTime = Long.MAX_VALUE
                                    for (sni in DEFAULT_SNI_LIST.shuffled().take(10)) {
                                        try {
                                            val start = System.currentTimeMillis()
                                            val socket = Socket()
                                            socket.connect(InetSocketAddress(sni, 443), 1500)
                                            socket.close()
                                            val time = System.currentTimeMillis() - start
                                            if (time < bestTime) {
                                                bestTime = time
                                                best = sni
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    best
                                }
                                config.fakeTlsDomain = bestSni
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
                        Text(if (isStarting) stringResource(R.string.auto_config_running) else stringResource(R.string.start_proxy))
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
                }
            }

            if (isRunning) {
                val clipboardManager = LocalClipboardManager.current
                val generateProxyLink = {
                    val base = "tg://proxy?server=${config.host}&port=${config.port}&secret="
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
                    Text("Open in Telegram")
                }
            }

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
                        Text(stringResource(R.string.logs) + if (logsExpanded) " (Collapse)" else " (Expand)", style = MaterialTheme.typography.titleSmall)
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
            Text(
                text = stringResource(R.string.github_repo),
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Gegaremant/TG-Proxy"))
                    context.startActivity(intent)
                }
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
    
    val scope = rememberCoroutineScope()
    var isAutoConfiguring by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleMedium)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val rndPort = (10000..60000).random()
                    config.port = rndPort
                    config.fakeTlsDomain = "sberbank.ru"
                    port = rndPort.toString()
                    fakeTlsDomain = "sberbank.ru"
                    onRestart()
                }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.preset_home))
                }
                Button(onClick = {
                    val rndPort = (10000..60000).random()
                    config.port = rndPort
                    config.fakeTlsDomain = "ya.ru"
                    port = rndPort.toString()
                    fakeTlsDomain = "ya.ru"
                    onRestart()
                }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.preset_subway))
                }
            }

            Button(onClick = {
                if (isAutoConfiguring) return@Button
                isAutoConfiguring = true
                scope.launch {
                    val bestSni = withContext(Dispatchers.IO) {
                        var best = "sberbank.ru"
                        var bestTime = Long.MAX_VALUE
                        for (sni in DEFAULT_SNI_LIST.shuffled().take(10)) {
                            try {
                                val start = System.currentTimeMillis()
                                val socket = Socket()
                                socket.connect(InetSocketAddress(sni, 443), 2000)
                                socket.close()
                                val time = System.currentTimeMillis() - start
                                if (time < bestTime) {
                                    bestTime = time
                                    best = sni
                                }
                            } catch (_: Exception) {}
                        }
                        best
                    }
                    fakeTlsDomain = bestSni
                    isAutoConfiguring = false
                    Toast.makeText(context, context.getString(R.string.auto_config_done), Toast.LENGTH_SHORT).show()
                }
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (isAutoConfiguring) stringResource(R.string.auto_config_running) else stringResource(R.string.auto_config))
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
            }

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
                    config.useCfProxy = useCfProxy
                    config.cfProxyDomain = cfProxyDomain
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
