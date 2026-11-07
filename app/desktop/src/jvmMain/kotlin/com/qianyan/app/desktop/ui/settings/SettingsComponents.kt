package com.qianyan.app.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qianyan.app.desktop.ui.HLine
import com.qianyan.app.desktop.ui.SectionLabel
import com.qianyan.app.desktop.ui.theme.QianyanShapes
import com.qianyan.app.desktop.ui.theme.QianyanTheme

/**
 * 设置页的 Apple 式分组组件：小节标签 + 圆角卡片 + 发丝分隔行。
 * 只做视觉容器，不含业务语义。
 */

@Composable
internal fun SettingsSection(
    title: String,
    desc: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = QianyanTheme.palette
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        SectionLabel(title)
        if (desc != null) {
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = palette.text3,
                modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
        }
        SettingsCard(content)
    }
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    val palette = QianyanTheme.palette
    val shape = RoundedCornerShape(QianyanShapes.r3)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(palette.raised)
            .border(1.dp, palette.line, shape),
    ) {
        content()
    }
}

@Composable
internal fun SettingsRow(
    title: String,
    desc: String? = null,
    divider: Boolean = false,
    control: @Composable () -> Unit,
) {
    val palette = QianyanTheme.palette
    if (divider) HLine(Modifier.padding(start = 16.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = palette.text)
            if (desc != null) {
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall, color = palette.text3)
            }
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}

@Composable
internal fun SettingsInfoRow(label: String, value: String, divider: Boolean = false) {
    val palette = QianyanTheme.palette
    if (divider) HLine(Modifier.padding(start = 16.dp))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text2,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = palette.text,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
internal fun SettingsNote(text: String, divider: Boolean = false) {
    val palette = QianyanTheme.palette
    if (divider) HLine(Modifier.padding(start = 16.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = palette.text3,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Apple 式开关：40×24 轨道 + 20 圆点，开启为强调蓝。 */
@Composable
internal fun SettingsSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = QianyanTheme.palette
    Box(
        Modifier.width(40.dp).height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) palette.accent else palette.lineStrong)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.padding(horizontal = 2.dp).size(20.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** Apple 式分段控件：浅灰轨道 + 白色选中块。 */
@Composable
internal fun SettingsSegmented(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val palette = QianyanTheme.palette
    Row(
        Modifier.clip(RoundedCornerShape(9.dp)).background(palette.soft).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val on = index == selectedIndex
            Text(
                option,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                color = if (on) palette.text else palette.text2,
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (on) palette.raised else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, palette.line, RoundedCornerShape(7.dp)) else Modifier)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 侧栏分组标题（比内容区 eyebrow 更安静）。 */
@Composable
internal fun SettingsGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        color = QianyanTheme.palette.text3,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp),
    )
}
