package com.qianyan.app.desktop.ui

import com.qianyan.app.desktop.adapter.DesktopFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TXT 导入的统一 UI 入口。
 *
 * 文件选择框 / 文件读取 / 取消 / 失败都在这里收敛：
 * 调用方不再各自 `chooseTxt()` + `readBytes()`，避免读取异常直接抛到 UI 事件线程。
 * 真正的导入仍走既有 [DesktopAppState.launchTxtImport]。
 */
fun DesktopAppState.importTxtFromPicker() {
    if (busy) {
        toast("任务进行中，请稍后再导入")
        return
    }
    scope.launch {
        try {
            val file = DesktopFilePicker.chooseTxt()
            if (file == null) {
                toast("已取消导入")
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            launchTxtImport(file.name, bytes)
        } catch (e: Exception) {
            toast("无法读取 TXT：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
