package com.example.photoprint

/**
 * アプリ内の複数箇所(MainActivity・CameraActivity)で共通して使う定数類。
 * 対象コードの書式を変更したい場合は、ここを直せば両方に反映される。
 */
object AppConstants {
    // 対象コードの構造。以下のいずれかに一致すればOK
    // 1) 数字2桁 + 英字1文字(任意) + ハイフン + 英字1文字(任意) + 数字8桁
    // 2) 英字1文字 + ハイフン + 数字8桁 (直前に英数字が無い場合のみ。誤って複数文字の末尾を拾わないため)
    val TARGET_CODE_REGEX = Regex(
        """(\d{2}[A-Za-z]?-[A-Za-z]?\d{8})|(?<![A-Za-z0-9])([A-Za-z]-\d{8})"""
    )
}
