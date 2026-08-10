package com.nexus.tim.bridge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nexus.tim.bridge.TimApp
import com.nexus.tim.bridge.config.BridgeConfig
import com.nexus.tim.bridge.service.TimForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BridgeTheme {
                BridgeScreen(
                    onStartService = {
                        startForegroundService(Intent(this, TimForegroundService::class.java))
                    },
                    onStopService = {
                        stopService(Intent(this, TimForegroundService::class.java))
                    },
                )
            }
        }
        if (intent?.getBooleanExtra("auto_start", false) == true) {
            startForegroundService(Intent(this, TimForegroundService::class.java))
        }
    }
}

private val BridgeColors = darkColorScheme(
    primary = Color(0xFF3DDC97),
    onPrimary = Color(0xFF003822),
    secondary = Color(0xFF7EC8E3),
    background = Color(0xFF0F1419),
    surface = Color(0xFF1A2332),
    onSurface = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFFA8B3C4),
    outline = Color(0xFF3A4658),
)

@Composable
private fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BridgeColors, content = content)
}

@Composable
private fun BridgeScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
) {
    val app = TimApp.instance
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var cfg by remember { mutableStateOf(app.currentConfig()) }

    var apiAuthOn by remember { mutableStateOf(cfg.apiAuthEnabled) }
    var apiToken by remember { mutableStateOf(cfg.apiToken) }
    var showApiToken by remember { mutableStateOf(false) }

    var redisOn by remember { mutableStateOf(cfg.redisEnabled) }
    var host by remember { mutableStateOf(cfg.redisHost) }
    var port by remember { mutableStateOf(cfg.redisPort.toString()) }
    var redisPassword by remember { mutableStateOf(cfg.redisPassword) }
    var showRedisPassword by remember { mutableStateOf(false) }
    var streamKey by remember { mutableStateOf(cfg.redisStreamKey) }

    var webhookOn by remember { mutableStateOf(cfg.webhookEnabled) }
    var webhook by remember { mutableStateOf(cfg.webhookUrl) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            tick++
        }
    }

    val state = app.bridgeState
    val redisErr = app.outbound.redisLastError
    val redisOk = app.outbound.redisLastOkAtMs

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "TIM Bridge",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Scroll down for API token · Redis password · Webhook",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StatusCard(
                hook = if (state.hookConnected) "connected" else "disconnected",
                loggedIn = if (state.loggedIn) "yes" else "no",
                me = state.me.userId.ifEmpty { "—" },
                version = state.timVersion ?: "—",
                recvHook = if (state.recvHook) "yes" else "no",
                authHint = when {
                    !cfg.apiAuthEnabled -> "API auth: off"
                    cfg.apiAuthReady -> "API auth: on"
                    else -> "API auth: on but token empty (will 401)"
                },
                redisHint = when {
                    !cfg.redisReady -> "Redis: off / not configured"
                    redisErr != null -> "Redis error: $redisErr"
                    redisOk > 0L -> "Redis OK · last push ${((System.currentTimeMillis() - redisOk) / 1000)}s ago"
                    else -> "Redis: ready, waiting for messages"
                },
                tick = tick,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onStartService, modifier = Modifier.weight(1f)) {
                    Text("Start service")
                }
                OutlinedButton(onClick = onStopService, modifier = Modifier.weight(1f)) {
                    Text("Stop service")
                }
            }

            ConfigCard(title = "1) API auth (token)") {
                SwitchRow("Enable API auth", apiAuthOn) { apiAuthOn = it }
                PasswordField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = "API Token",
                    visible = showApiToken,
                    onToggleVisible = { showApiToken = !showApiToken },
                )
                Hint("Except /v1/health, send Authorization: Bearer <token>")
            }

            ConfigCard(title = "2) Redis push") {
                SwitchRow("Enable Redis", redisOn) { redisOn = it }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Redis host") },
                    placeholder = { Text("192.168.1.10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(
                    value = redisPassword,
                    onValueChange = { redisPassword = it },
                    label = "Redis password (optional)",
                    visible = showRedisPassword,
                    onToggleVisible = { showRedisPassword = !showRedisPassword },
                )
                OutlinedTextField(
                    value = streamKey,
                    onValueChange = { streamKey = it },
                    label = { Text("Stream key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ConfigCard(title = "3) Alert webhook") {
                SwitchRow("Enable webhook", webhookOn) { webhookOn = it }
                OutlinedTextField(
                    value = webhook,
                    onValueChange = { webhook = it },
                    label = { Text("Webhook URL") },
                    placeholder = { Text("http://192.168.1.10:9000/alert") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Hook disconnect / version mismatch / Redis fail / send fail")
            }

            Button(
                onClick = {
                    val saved = app.saveConfig(
                        BridgeConfig(
                            redisHost = host,
                            redisPort = port.toIntOrNull() ?: BridgeConfig.DEFAULT_REDIS_PORT,
                            redisPassword = redisPassword,
                            redisStreamKey = streamKey,
                            redisEnabled = redisOn,
                            webhookUrl = webhook,
                            webhookEnabled = webhookOn,
                            apiAuthEnabled = apiAuthOn,
                            apiToken = apiToken,
                        ),
                    )
                    cfg = saved
                    apiAuthOn = saved.apiAuthEnabled
                    apiToken = saved.apiToken
                    redisOn = saved.redisEnabled
                    host = saved.redisHost
                    port = saved.redisPort.toString()
                    redisPassword = saved.redisPassword
                    streamKey = saved.redisStreamKey
                    webhookOn = saved.webhookEnabled
                    webhook = saved.webhookUrl
                    scope.launch { snackbar.showSnackbar("Config saved") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save all settings")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ConfigCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
            content()
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            IconButton(onClick = onToggleVisible) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusCard(
    hook: String,
    loggedIn: String,
    me: String,
    version: String,
    recvHook: String,
    authHint: String,
    redisHint: String,
    tick: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Hook: $hook", fontWeight = FontWeight.Medium)
            Text("Logged in: $loggedIn")
            Text("Me: $me")
            Text("TIM: $version")
            Text("Recv hook: $recvHook")
            Text(text = authHint, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            key(tick) {
                Text(text = redisHint, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = "HTTP :8788 · IPC 127.0.0.1:18788 · /v1/events memory-only",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
