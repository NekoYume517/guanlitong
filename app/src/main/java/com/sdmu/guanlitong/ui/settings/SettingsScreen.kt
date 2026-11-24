package com.sdmu.guanlitong.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.SchoolService
import com.sdmu.guanlitong.data.ServiceCatalog
import com.sdmu.guanlitong.data.SettingsStore
import com.sdmu.guanlitong.ui.components.ServiceBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configurable = ServiceCatalog.services.filter { it.configurable }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("服务地址") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "这些服务由你自己的服务器提供。抢课助手就是 sdmu-website-2 的 Flask 后端，默认地址 http://10.0.2.2:5000 只适用于模拟器访问本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            configurable.forEach { service ->
                UrlEditorCard(service = service, settings = settings)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UrlEditorCard(service: SchoolService, settings: SettingsStore) {
    val savedUrls by settings.urls.collectAsState()
    val saved = savedUrls[service.id] ?: service.defaultUrl
    var text by remember(service.id, saved) { mutableStateOf(saved) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ServiceBadge(service = service, size = 38.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = service.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = service.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("服务地址") },
                placeholder = { Text("http://192.168.1.10:5000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        settings.resetUrl(service.id)
                        text = service.defaultUrl
                    },
                ) { Text("恢复默认") }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { settings.setUrl(service.id, text) }) {
                    Text("保存")
                }
            }
        }
    }
}

