package com.sdmu.guanlitong.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sdmu.guanlitong.data.WidgetType
import com.sdmu.guanlitong.ui.theme.GuanLiTongTheme

/** 系统长按桌面卡片「编辑 / 设置」时进入这里。 */
@OptIn(ExperimentalLayoutApi::class)
class DesktopWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        setResult(RESULT_CANCELED, resultIntent())
        val store = DesktopWidgetStore(this)
        val original = store.load(appWidgetId)
        val initialSize = DesktopSize.fromKey(intent.getStringExtra(EXTRA_DESKTOP_SIZE) ?: original.size.key)
        setContent {
            GuanLiTongTheme {
                var selectedType by remember { mutableStateOf(original.type) }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("编辑小组件") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                                }
                            },
                        )
                    },
                    bottomBar = {
                        Surface(tonalElevation = 3.dp) {
                            Button(
                                onClick = {
                                    store.save(appWidgetId, initialSize, selectedType)
                                    refreshDesktopWidgets(this@DesktopWidgetConfigActivity)
                                    setResult(RESULT_OK, resultIntent())
                                    finish()
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                                    .height(50.dp),
                            ) { Text("保存并更新小组件") }
                        }
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "${initialSize.label} · ${initialSize.description}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "选择卡片内容，预览会实时更新。桌面只提供小、中、大三种尺寸。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            WidgetType.entries.forEach { type ->
                                val selected = type == selectedType
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth(if (initialSize == DesktopSize.Small) 0.47f else 1f)
                                        .clickable { selectedType = type },
                                ) {
                                    Column(
                                        modifier = Modifier.padding(3.dp).border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            shape = RoundedCornerShape(26.dp),
                                        ).padding(3.dp),
                                    ) {
                                        DesktopWidgetPreview(
                                            context = this@DesktopWidgetConfigActivity,
                                            appWidgetId = appWidgetId,
                                            size = initialSize,
                                            type = type,
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = type.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (selected) {
                                            Icon(Icons.Outlined.Check, contentDescription = "已选择",
                                                modifier = Modifier.padding(start = 4.dp).size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    private fun resultIntent() = android.content.Intent().putExtra(EXTRA_APPWIDGET_ID, appWidgetId)

    companion object {
        private const val EXTRA_APPWIDGET_ID = AppWidgetManager.EXTRA_APPWIDGET_ID
    }
}
