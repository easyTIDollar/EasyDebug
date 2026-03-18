package com.jussicodes.easydebug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface

// ==========================================
// 1. 预设主题颜色定义
// ==========================================
val PresetColors = listOf(
    Color(0xFF6750A4), // 默认紫
    Color(0xFF006A60), // 护眼绿
    Color(0xFF00639B), // 科技蓝
    Color(0xFFB3261E), // 热情红
    Color(0xFF984061)  // 猛男粉
)

// ==========================================
// 2. 页面路由枚举
// ==========================================
enum class Screen { Main, Settings }

// ==========================================
// 3. MainActivity
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}

// ==========================================
// 4. 根组件 (管理主题和路由)
// ==========================================
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    var useDynamicColor by remember { mutableStateOf(prefs.getBoolean("dynamic_color", true)) }
    var selectedColorIndex by remember { mutableStateOf(prefs.getInt("theme_color", 0)) }
    val darkTheme = isSystemInDarkTheme()

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        else -> {
            val primary = PresetColors.getOrElse(selectedColorIndex) { PresetColors[0] }
            if (darkTheme) darkColorScheme(primary = primary) else lightColorScheme(primary = primary)
        }
    }

    var currentScreen by remember { mutableStateOf(Screen.Main) }

    MaterialTheme(colorScheme = colorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = currentScreen, label = "screen_transition") { screen ->
                when (screen) {
                    Screen.Main -> WirelessDebuggingScreen(onNavigateToSettings = { currentScreen = Screen.Settings })
                    Screen.Settings -> SettingsScreen(
                        onNavigateBack = { currentScreen = Screen.Main },
                        useDynamicColor = useDynamicColor,
                        onDynamicColorChange = {
                            useDynamicColor = it
                            prefs.edit().putBoolean("dynamic_color", it).apply()
                        },
                        selectedColorIndex = selectedColorIndex,
                        onColorSelect = {
                            selectedColorIndex = it
                            prefs.edit().putInt("theme_color", it).apply()
                        }
                    )
                }
            }
        }
    }
}

// ==========================================
// 5. 实时获取 WiFi 状态的自定义 Hook
// ==========================================
@Composable
fun rememberWifiState(context: Context): State<Boolean> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val state = remember { mutableStateOf(wifiManager.isWifiEnabled) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                        state.value = true
                    } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                        state.value = false
                    }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        }
    }
    return state
}

// ==========================================
// 6. 主页面 (WiFi 开关 + 无线调试开关 + IP)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WirelessDebuggingScreen(onNavigateToSettings: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 权限与状态
    var rootGranted by remember { mutableStateOf(true) }
    var isAdbEnabled by remember { mutableStateOf(false) }

    // 监听真实的系统 WiFi 状态
    val isWifiEnabled by rememberWifiState(context)

    // IP和端口状态
    var ipAddress by remember { mutableStateOf("") }
    var adbPort by remember { mutableStateOf("") }

    // 更新 IP 和端口的方法
    fun updateConnectionInfo() {
        coroutineScope.launch(Dispatchers.IO) {
            val ip = getLocalIpAddress()
            var port = runSuCommand("getprop service.adb.tls.port")
            if (port.isBlank() || port.toIntOrNull() == null) {
                port = runSuCommand("getprop adb.tcp.port")
            }
            withContext(Dispatchers.Main) {
                ipAddress = ip
                adbPort = port
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val state = runSuCommand("settings get global adb_wifi_enabled")
            if (state == "PERMISSION_DENIED") rootGranted = false
            else {
                val enabled = state == "1"
                withContext(Dispatchers.Main) {
                    isAdbEnabled = enabled
                    if (enabled) updateConnectionInfo()
                }
            }
        }
    }

    // 当 WiFi 状态改变且 ADB 开启时，重新获取 IP
    LaunchedEffect(isWifiEnabled) {
        if (isAdbEnabled && isWifiEnabled) {
            delay(1500) // 等 WiFi 连上分配 IP
            updateConnectionInfo()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("无线调试开关", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!rootGranted) {
                Text(
                    text = "未获取到 Root 权限，请在 Magisk/KernelSU 中授权！",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            // ================== 第一个卡片：WiFi 开关 ==================
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "WLAN (无线局域网)",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (isWifiEnabled) "当前状态：已开启" else "当前状态：已关闭",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                        )
                    }

                    Switch(
                        checked = isWifiEnabled,
                        onCheckedChange = { checked ->
                            // 使用 Root 强制开关 WiFi
                            coroutineScope.launch(Dispatchers.IO) {
                                val cmd = if (checked) "svc wifi enable" else "svc wifi disable"
                                runSuCommand(cmd)
                            }
                        }
                    )
                }
            }

            // ================== 第二个卡片：无线调试开关 ==================
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "无线调试",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isAdbEnabled) "当前状态：已开启" else "当前状态：已关闭",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                        }

                        Switch(
                            checked = isAdbEnabled,
                            onCheckedChange = { checked ->
                                isAdbEnabled = checked
                                coroutineScope.launch(Dispatchers.IO) {
                                    val value = if (checked) "1" else "0"
                                    runSuCommand("settings put global adb_wifi_enabled $value")

                                    if (checked) {
                                        delay(800)
                                        updateConnectionInfo()
                                    }
                                }
                            }
                        )
                    }

                    // 展开显示的 IP 和端口信息区域
                    AnimatedVisibility(
                        visible = isAdbEnabled,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.1f))
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            Column {
                                Text(
                                    text = "连接地址",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                val displayAddress = if (!isWifiEnabled) {
                                    "请先开启并连接 WiFi"
                                } else if (adbPort.isNotBlank() && adbPort != "0" && adbPort != "PERMISSION_DENIED") {
                                    "$ipAddress:$adbPort"
                                } else {
                                    "正在获取..."
                                }

                                Text(
                                    text = displayAddress,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 7. 设置页面 (支持自动读取版本号)
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    useDynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    selectedColorIndex: Int,
    onColorSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    var showAboutDialog by remember { mutableStateOf(false) }
    val isDynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // 动态获取系统底层真实的版本号 (也就是 build.gradle.kts 里的 versionName)
    val appVersionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知版本"
        } catch (e: Exception) {
            "未知版本"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text("外观", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp))
            ListItem(
                headlineContent = { Text("动态取色 (Material You)") },
                supportingContent = { Text(if (isDynamicColorSupported) "根据系统壁纸自动生成主题色" else "系统版本过低，不支持此功能") },
                leadingContent = { Icon(Icons.Filled.Star, contentDescription = null) },
                trailingContent = { Switch(checked = useDynamicColor, onCheckedChange = onDynamicColorChange, enabled = isDynamicColorSupported) }
            )

            if (!useDynamicColor || !isDynamicColorSupported) {
                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(PresetColors.indices.toList()) { index ->
                        val color = PresetColors[index]
                        val isSelected = selectedColorIndex == index
                        Box(modifier = Modifier.size(48.dp).clip(CircleShape).background(color).clickable { onColorSelect(index) }.border(width = if (isSelected) 3.dp else 0.dp, color = if (isSelected) MaterialTheme.colorScheme.onBackground else Color.Transparent, shape = CircleShape))
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("其他", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp))
            ListItem(
                headlineContent = { Text("关于本软件") },
                supportingContent = { Text("版本信息与开发者") },
                leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                modifier = Modifier.clickable { showAboutDialog = true }
            )
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于 EasyDebug") },
            text = {
                Column {
                    // 这里会自动显示获取到的真实版本号！
                    Text("版本：$appVersionName")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("作者：jussicodes")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("用 Root 权限快速开关无线调试的工具。采用原生 Jetpack Compose 构建。")
                    Spacer(modifier = Modifier.height(8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showAboutDialog = false }) { Text("确定") } }
        )
    }
}

// ==========================================
// 8. 工具函数：Root 命令 & 获取 IP
// ==========================================
fun runSuCommand(command: String): String {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        process.waitFor()
        if (process.exitValue() != 0) return "PERMISSION_DENIED"
        reader.readText().trim()
    } catch (e: Exception) {
        e.printStackTrace()
        "ERROR"
    }
}

fun getLocalIpAddress(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "未连接 WiFi"
        var fallbackIp: String? = null

        for (networkInterface in interfaces) {
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            for (address in networkInterface.inetAddresses) {
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    val ip = address.hostAddress ?: continue
                    if (networkInterface.name.contains("wlan") || networkInterface.name.contains("eth")) {
                        return ip
                    }
                    if (fallbackIp == null) {
                        fallbackIp = ip
                    }
                }
            }
        }
        return fallbackIp ?: "未连接 WiFi"
    } catch (e: Exception) {
        e.printStackTrace()
        return "获取失败"
    }
}