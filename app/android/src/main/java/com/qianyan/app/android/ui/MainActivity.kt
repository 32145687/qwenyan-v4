package com.qianyan.app.android.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qianyan.app.android.QianyanApplication
import com.qianyan.app.android.ui.analysis.AnalysisScreen
import com.qianyan.app.android.ui.analysis.AnalysisViewModel
import com.qianyan.app.android.ui.novel.NovelListScreen
import com.qianyan.app.android.ui.novel.NovelListViewModel
import com.qianyan.app.android.ui.theme.QianyanTheme
import com.qianyan.model.core.Novel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 当前页面（P7.6 简单 UI 状态切换，不引入 Navigation 架构）。 */
private sealed interface Screen {
    data object NovelList : Screen
    data class Analysis(val novel: Novel) : Screen
}

/**
 * 主入口 Activity（P7.4 + P7.5）：UI Host + SAF TXT 文件选择。
 *
 * 分层（P7.5）：
 *  - 本 Activity 是 Composition Root 的延伸，只负责平台层：通过 SAF（ActivityResultContracts.OpenDocument）
 *    让用户选文件，并把 Uri 读取为「字节 + 展示名」（平台无关），交给 [NovelListViewModel.importTxt]。
 *  - 不直接触碰 Repository / 业务逻辑；Uri 只在平台层消费，不泄漏到 ViewModel / Use Case。
 *  - SAF 选择不需要任何运行时存储权限，Manifest 无需改动。
 *
 * Composition Root 是 [QianyanApplication]（持有 ApplicationContainer）；本 Activity 从中取出
 * `container.novels` / `container.txts` 装配 [NovelListViewModel] 并交给 Compose。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: NovelListViewModel by lazy {
        val container = (application as QianyanApplication).container
        ViewModelProvider(
            this,
            NovelListViewModel.factory(container.novels, container.txts),
        )[NovelListViewModel::class.java]
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { onFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            QianyanTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.NovelList) }
                BackHandler(enabled = screen is Screen.Analysis) {
                    screen = Screen.NovelList
                }
                when (val current = screen) {
                    is Screen.NovelList -> NovelListScreen(
                        viewModel = viewModel,
                        onImportClick = { openDocumentLauncher.launch(arrayOf("text/*")) },
                        onNovelClick = { novel -> screen = Screen.Analysis(novel) },
                    )
                    is Screen.Analysis -> {
                        // 按 novelId 作为 key，每个小说独占一个 AnalysisViewModel（避免跨小说复用旧状态）。
                        val analysisViewModel: AnalysisViewModel = viewModel(
                            key = "analysis-${current.novel.novelId.value}",
                            factory = AnalysisViewModel.factory(
                                analysis = container.analysis,
                                vocabularies = container.vocabularies,
                                txts = container.txts,
                                novel = current.novel,
                            ),
                        )
                        AnalysisScreen(
                            novelTitle = current.novel.title,
                            viewModel = analysisViewModel,
                            onBack = { screen = Screen.NovelList },
                        )
                    }
                }
            }
        }
    }

    /** 应用级容器（QianyanApplication 组合根），仅读取 Application UseCase。 */
    private val container: com.qianyan.application.di.ApplicationContainer
        get() = (application as QianyanApplication).container

    /** SAF 选择回调：在 IO 线程读取所选文件，随后交给 ViewModel 导入。 */
    private fun onFileSelected(uri: Uri) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { readFile(uri) } }
                .onSuccess { (name, bytes) -> viewModel.importTxt(bytes, name) }
                .onFailure { viewModel.showImportError("无法读取所选文件") }
        }
    }

    /** 读取文件字节与展示名（平台层职责；Uri 仅本层使用）。 */
    private fun readFile(uri: Uri): Pair<String, ByteArray> {
        val name = contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: ""
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("openInputStream 返回 null")
        return name to bytes
    }
}
