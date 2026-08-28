package com.example.photoprint

/**
 * アプリ内の複数箇所(MainActivity・CameraActivity)で共通して使う定数類。
 * 対象コードの書式を変更したい場合は、ここを直せば両方に反映される。
 */
object AppConstants {
    // 対象コードの構造: 数字2桁 + 英字1文字(任意) + ハイフン + 英字1文字(任意) + 数字8桁
    val TARGET_CODE_REGEX = Regex("""\d{2}[A-Za-z]?-[A-Za-z]?\d{8}""")
}
