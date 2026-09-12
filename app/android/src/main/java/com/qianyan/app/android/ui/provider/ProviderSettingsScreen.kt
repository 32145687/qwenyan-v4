package com.qianyan.app.android.ui.provider

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qianyan.provider.ProviderType

/**
 * Provider 设置 Screen（P12.5-M01）。
 *
 * 用户在此选择 AI Provider（Mock / DeepSeek / MiMo）并配置 API Key；真实 Key 由
 * ViewModel 委托 [com.qianyan.app.android.QianyanApplication] 经 Android Keystore 安全存储。
 * 本 Screen 只消费 [ProviderSettingsViewModel] 的 UI 状态，不直接访问 Repository / Agent / LLMGateway。
 */
@Composable
fun ProviderSettingsScreen(
    viewModel: ProviderSettingsViewModel,
    onBack: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("← 返回") }
        Spacer(Modifier.height(8.dp))

        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(24.dp))

        SectionTitle("AI Provider")
        Spacer(Modifier.height(8.dp))

        ProviderType.entries.forEach { provider ->
            ProviderOptionRow(
                provider = provider,
                selected = ui.selectedProvider == provider,
                onClick = { viewModel.selectProvider(provider) },
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("API Key（Android Keystore 加密保存）")
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = ui.apiKeyInput,
            onValueChange = viewModel::onApiKeyChanged,
            enabled = ui.selectedProvider != ProviderType.MOCK && !ui.saving && !ui.testing,
            placeholder = { Text(if (ui.hasKey) "已配置，可覆盖" else "输入 API Key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = viewModel::saveKey,
                enabled = ui.selectedProvider != ProviderType.MOCK && !ui.saving && !ui.testing,
            ) {
                if (ui.saving) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                else Text("保存密钥")
            }
            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = ui.selectedProvider != ProviderType.MOCK && !ui.saving && !ui.testing,
            ) {
                if (ui.testing) CircularProgressIndicator(Modifier.width(16.dp).height(16.dp))
                else Text("测试连接")
            }
            if (ui.hasKey) {
                TextButton(
                    onClick = viewModel::clearKey,
                    enabled = !ui.saving && !ui.testing,
                ) { Text("清除") }
            }
        }

        ui.message?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = when (ui.messageLevel) {
                    MessageLevel.SUCCESS -> MaterialTheme.colorScheme.tertiary
                    MessageLevel.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProviderOptionRow(
    provider: ProviderType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(
            text = when (provider) {
                ProviderType.MOCK -> "Mock（本地模拟，无需 Key）"
                ProviderType.DEEPSEEK -> "DeepSeek"
                ProviderType.MIMO -> "MiMo"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}