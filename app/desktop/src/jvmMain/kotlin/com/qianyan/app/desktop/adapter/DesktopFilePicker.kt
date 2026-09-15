package com.qianyan.app.desktop.adapter

import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * PC 文件选择（Desktop Adapter）：替代 Android SAF。
 * Swing JFileChooser 在 Compose Desktop（同为 AWT/Swing 窗口体系）中稳定可用。
 */
object DesktopFilePicker {

    /** 选择 TXT 文件；取消返回 null。 */
    fun chooseTxt(): File? {
        var result: File? = null
        runOnEdt {
            val chooser = JFileChooser().apply {
                dialogTitle = "选择要导入的 TXT 小说文件"
                fileFilter = FileNameExtensionFilter("TXT 文本文件 (*.txt)", "txt")
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
        }
        return result
    }

    /** 选择保存位置（导出正文）；取消返回 null。 */
    fun chooseSave(defaultName: String): File? {
        var result: File? = null
        runOnEdt {
            val chooser = JFileChooser().apply {
                dialogTitle = "导出章节正文"
                selectedFile = File(defaultName)
                fileFilter = FileNameExtensionFilter("TXT 文本文件 (*.txt)", "txt")
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
        }
        return result
    }

    private inline fun runOnEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block()
        else SwingUtilities.invokeAndWait { block() }
    }
}
