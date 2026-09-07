package com.qianyan.app.android.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qianyan.app.android.QianyanApplication
import com.qianyan.app.android.ui.analysis.AnalysisScreen
import com.qianyan.app.android.ui.analysis.AnalysisViewModel
import com.qianyan.app.android.ui.chapter.ChapterDetailScreen
import com.qianyan.app.android.ui.chapter.ChapterDetailViewModel
import com.qianyan.app.android.ui.chapter.ChapterListScreen
import com.qianyan.app.android.ui.chapter.ChapterViewModel
import com.qianyan.app.android.ui.chapter.ChapterWritingScreen
import com.qianyan.app.android.ui.chapter.ChapterWritingViewModel
import com.qianyan.app.android.ui.novel.NovelDetailScreen
import com.qianyan.app.android.ui.novel.NovelListScreen
import com.qianyan.app.android.ui.novel.NovelListViewModel
import com.qianyan.app.android.ui.theme.QianyanTheme
import com.qianyan.model.ChapterId
import com.qianyan.model.VariantId
import com.qianyan.model.core.Novel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 页面导航（P12.1.6）。沿用项目既有「无 Navigation Compose」的简单状态切换：用一个可回退的
 * [mutableStateListOf] 作为 back stack，逐级 push/pop。路由参数一律用稳定 ID
 * （novelId / variantId / chapterId），不把整 Domain 对象塞进 route。
 *
 * NovelList → NovelDetail → (ChapterList | Analysis)；ChapterList → ChapterDetail。
 */
private sealed interface Screen {
    data object NovelList : Screen
    data class NovelDetail(val novel: Novel) : Screen
    data class Analysis(val novel: Novel) : Screen
    data class ChapterList(val novel: Novel, val variantId: VariantId?) : Screen
    data class ChapterDetail(val novel: Novel, val variantId: VariantId?, val chapterId: ChapterId) : Screen
    data class ChapterWriting(val novel: Novel, val variantId: VariantId?, val chapterId: ChapterId) : Screen
}

/** 主入口 Activity（P7.4 + P7.5 + P12.1.6）：UI Host + SAF TXT 文件选择 + 章节导航。 */
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
                AppHost()
            }
        }
    }

    /** 应用级容器（QianyanApplication 组合根），仅读取 Application Use Case。 */
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

    /** 页面 Host：back stack 驱动，逐级进退。 */
    @Composable
    private fun AppHost() {
        val backstack = remember { mutableStateListOf<Screen>(Screen.NovelList) }
        val current = backstack.lastOrNull() ?: Screen.NovelList
        val push: (Screen) -> Unit = { backstack.add(it) }
        val pop: () -> Unit = { if (backstack.size > 1) backstack.removeAt(backstack.lastIndex) }

        BackHandler(enabled = backstack.size > 1) { pop() }

        when (val screen = current) {
            is Screen.NovelList -> NovelListScreen(
                viewModel = viewModel,
                onImportClick = { openDocumentLauncher.launch(arrayOf("text/*")) },
                onNovelClick = { push(Screen.NovelDetail(it)) },
            )

            is Screen.NovelDetail -> NovelDetailScreen(
                novel = screen.novel,
                onChapters = { push(Screen.ChapterList(screen.novel, null)) },
                onAnalysis = { push(Screen.Analysis(screen.novel)) },
                onBack = pop,
            )

            is Screen.Analysis -> {
                val analysisViewModel: AnalysisViewModel = viewModel(
                    key = "analysis-${screen.novel.novelId.value}",
                    factory = AnalysisViewModel.factory(
                        analysis = container.analysis,
                        vocabularies = container.vocabularies,
                        txts = container.txts,
                        novel = screen.novel,
                    ),
                )
                AnalysisScreen(
                    novelTitle = screen.novel.title,
                    viewModel = analysisViewModel,
                    onBack = pop,
                )
            }

            is Screen.ChapterList -> {
                val chapterViewModel: ChapterViewModel = viewModel(
                    key = "chapters-${screen.novel.novelId.value}-${screen.variantId?.value ?: "orig"}",
                    factory = ChapterViewModel.factory(container.chapters, screen.novel.novelId, screen.variantId),
                )
                ChapterListScreen(
                    viewModel = chapterViewModel,
                    novelTitle = screen.novel.title,
                    onOpen = { c ->
                        push(Screen.ChapterDetail(screen.novel, screen.variantId, c.chapterId))
                    },
                    onBack = pop,
                )
            }

            is Screen.ChapterDetail -> {
                val detailViewModel: ChapterDetailViewModel = viewModel(
                    key = "chapter-${screen.chapterId.value}",
                    factory = ChapterDetailViewModel.factory(
                        container.chapters,
                        screen.novel.novelId,
                        screen.variantId,
                        screen.chapterId,
                    ),
                )
                ChapterDetailScreen(
                    viewModel = detailViewModel,
                    novelTitle = screen.novel.title,
                    onStartWriting = { push(Screen.ChapterWriting(screen.novel, screen.variantId, screen.chapterId)) },
                    onBack = pop,
                )
            }

            is Screen.ChapterWriting -> {
                val writingViewModel: ChapterWritingViewModel = viewModel(
                    key = "chapter-writing-${screen.chapterId.value}",
                    factory = ChapterWritingViewModel.factory(
                        novelId = screen.novel.novelId,
                        variantId = screen.variantId,
                        chapterId = screen.chapterId,
                        gateway = container.workflowFacade,
                    ),
                )
                ChapterWritingScreen(
                    viewModel = writingViewModel,
                    onBack = pop,
                    onContinueToNext = { nextChapterId ->
                        push(Screen.ChapterWriting(screen.novel, screen.variantId, nextChapterId))
                    },
                )
            }
        }
    }
}