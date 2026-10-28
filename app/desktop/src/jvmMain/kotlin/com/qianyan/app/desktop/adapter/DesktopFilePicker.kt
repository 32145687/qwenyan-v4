package com.qianyan.app.desktop.adapter

import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
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

    /** 导出 TXT：选择保存位置；取消 / 放弃覆盖返回 null。 */
    fun saveTxt(defaultName: String): File? {
        var result: File? = null
        runOnEdt {
            val chooser = JFileChooser().apply {
                dialogTitle = "导出章节为 TXT"
                fileFilter = FileNameExtensionFilter("TXT 文本文件 (*.txt)", "txt")
                selectedFile = File(defaultName)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val chosen = chooser.selectedFile
                val target = if (chosen.name.lowercase().endsWith(".txt")) {
                    chosen
                } else {
                    File(chosen.parentFile, chosen.name + ".txt")
                }
                if (!target.exists() || confirmOverwrite(target)) result = target
            }
        }
        return result
    }

    private fun confirmOverwrite(file: File): Boolean =
        JOptionPane.showConfirmDialog(
            null,
            "「${file.name}」已存在，要覆盖吗？",
            "覆盖文件",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        ) == JOptionPane.YES_OPTION

    /** 选择既有备份文件（JSON）；取消返回 null。 */
    fun chooseBackup(): File? {
        var result: File? = null
        runOnEdt {
            val chooser = JFileChooser().apply {
                dialogTitle = "选择千言备份文件"
                fileFilter = FileNameExtensionFilter("千言备份 (*.json)", "json")
                isMultiSelectionEnabled = false
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) result = chooser.selectedFile
        }
        return result
    }

    /** 导出备份（JSON）；取消 / 放弃覆盖返回 null。 */
    fun saveBackup(defaultName: String): File? {
        var result: File? = null
        runOnEdt {
            val chooser = JFileChooser().apply {
                dialogTitle = "导出千言备份"
                fileFilter = FileNameExtensionFilter("千言备份 (*.json)", "json")
                selectedFile = File(defaultName)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val chosen = chooser.selectedFile
                val target = if (chosen.name.lowercase().endsWith(".json")) {
                    chosen
                } else {
                    File(chosen.parentFile, chosen.name + ".json")
                }
                if (!target.exists() || confirmOverwrite(target)) result = target
            }
        }
        return result
    }

    private inline fun runOnEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block()
        else SwingUtilities.invokeAndWait { block() }
    }
}