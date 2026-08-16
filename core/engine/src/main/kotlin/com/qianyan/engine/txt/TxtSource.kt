package com.qianyan.engine.txt

/**
 * 平台无关的 TXT 输入抽象：原始字节 + 展示名。
 *
 * 读取文件由平台/运行时（Android/Desktop）在调用方完成，TXT Engine 只接收字节，
 * 不绑定任何平台 File API（P4 约束）。
 */
class TxtSource(
    val bytes: ByteArray,
    val displayName: String = "",
) {
    val size: Long get() = bytes.size.toLong()
}
