package com.example.photoprint

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.print.PrintHelper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var photoContainer: View
    private lateinit var cropOverlayView: CropOverlayView
    private lateinit var btnTakePhoto: Button
    private lateinit var btnPrint: Button
    private lateinit var btnOcr: Button
    private lateinit var btnPrintText: Button
    private lateinit var tvHint: TextView
    private lateinit var etRecognizedText: EditText

    // 日本語対応のテキスト認識器(端末上でOCRを実行する)
    private val textRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    // 撮影した写真の一時保存先File
    private var photoFile: File? = null

    // 撮影時のターゲット枠の位置・サイズ(プレビュー画面に対する割合)。枠情報が無い場合はnullのまま
    private var frameLeftRatio: Float? = null
    private var frameTopRatio: Float? = null
    private var frameRightRatio: Float? = null
    private var frameBottomRatio: Float? = null

    // 撮影後に読み込んだ元のBitmap(トリミングの元データ)
    private var originalBitmap: Bitmap? = null

    // 自前のカメラ画面(ターゲット枠付き)を起動し、撮影結果(ファイルパス・枠の位置情報)を受け取るランチャー
    private val cameraActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val path = result.data?.getStringExtra(CameraActivity.EXTRA_PHOTO_PATH)
                if (path != null) {
                    photoFile = File(path)

                    // 枠の情報が含まれていれば保持し、無ければnullにして写真全体を対象にする
                    val data = result.data
                    frameLeftRatio = if (data?.hasExtra(CameraActivity.EXTRA_FRAME_LEFT_RATIO) == true)
                        data.getFloatExtra(CameraActivity.EXTRA_FRAME_LEFT_RATIO, 0f) else null
                    frameTopRatio = if (data?.hasExtra(CameraActivity.EXTRA_FRAME_TOP_RATIO) == true)
                        data.getFloatExtra(CameraActivity.EXTRA_FRAME_TOP_RATIO, 0f) else null
                    frameRightRatio = if (data?.hasExtra(CameraActivity.EXTRA_FRAME_RIGHT_RATIO) == true)
                        data.getFloatExtra(CameraActivity.EXTRA_FRAME_RIGHT_RATIO, 1f) else null
                    frameBottomRatio = if (data?.hasExtra(CameraActivity.EXTRA_FRAME_BOTTOM_RATIO) == true)
                        data.getFloatExtra(CameraActivity.EXTRA_FRAME_BOTTOM_RATIO, 1f) else null

                    onPhotoCaptured()
                } else {
                    Toast.makeText(this, "撮影データを取得できませんでした", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "撮影がキャンセルされました", Toast.LENGTH_SHORT).show()
            }
        }

    // カメラ権限のリクエスト
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, "カメラ権限が必要です", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        photoContainer = findViewById(R.id.photoContainer)
        cropOverlayView = findViewById(R.id.cropOverlayView)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnPrint = findViewById(R.id.btnPrint)
        btnOcr = findViewById(R.id.btnOcr)
        btnPrintText = findViewById(R.id.btnPrintText)
        tvHint = findViewById(R.id.tvHint)
        etRecognizedText = findViewById(R.id.etRecognizedText)

        btnTakePhoto.setOnClickListener { checkPermissionAndLaunchCamera() }
        btnPrint.setOnClickListener { printSelectedArea() }
        btnOcr.setOnClickListener { onOcrButtonClicked() }
        btnPrintText.setOnClickListener { printRecognizedText() }

        // 編集欄をタップして編集を始めたら、写真プレビューを隠してその分編集欄を大きく表示する。
        // ボタン類は編集欄より上にあるため、最大化中も押せる状態のまま残る。
        etRecognizedText.setOnFocusChangeListener { _, hasFocus ->
            setTextEditingExpanded(hasFocus)
        }

        // 写真を撮らず、キーボードで直接入力した場合でも印刷できるよう、
        // 文字が入っているかどうかで印刷ボタンの有効/無効を自動的に切り替える
        etRecognizedText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                btnPrintText.isEnabled = !s.isNullOrBlank()
            }
        })
        btnPrintText.isEnabled = etRecognizedText.text?.isNotBlank() == true
    }

    /** 編集欄を最大化する/元に戻す。trueで写真プレビューを隠して編集欄に画面を譲る */
    private fun setTextEditingExpanded(expanded: Boolean) {
        photoContainer.visibility = if (expanded) View.GONE else View.VISIBLE

        val params = etRecognizedText.layoutParams as LinearLayout.LayoutParams
        if (expanded) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = (160 * resources.displayMetrics.density).toInt()
            params.weight = 0f
        }
        etRecognizedText.layoutParams = params
    }

    private fun checkPermissionAndLaunchCamera() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            launchCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        cameraActivityLauncher.launch(Intent(this, CameraActivity::class.java))
    }

    private fun onPhotoCaptured() {
        val file = photoFile ?: return

        // 大きな画像でメモリを圧迫しないよう、必要に応じて縮小して読み込む
        val bitmap = decodeSampledBitmap(file, 3072, 3072)
        originalBitmap = bitmap

        imageView.setImageBitmap(bitmap)
        // ImageViewのレイアウト確定後にimageMatrixが正しく計算されるようpostする
        imageView.post {
            cropOverlayView.reset()
        }
        btnPrint.isEnabled = true
        btnOcr.isEnabled = true
        btnPrintText.isEnabled = false
        etRecognizedText.setText("")
        tvHint.text = "枠内の文字を自動で読み取っています…"

        // 範囲選択の手間を省くため、撮影直後に「ターゲット枠内」を自動でOCRする
        // (枠の情報が無い場合は写真全体が対象になる)
        // (あとから範囲を選んで「範囲を選んで再認識」を押せば、任意の部分で再認識も可能)
        runOcr(cropToFrameIfAvailable(bitmap))
    }

    /** 撮影時のターゲット枠の位置情報があれば、その範囲だけを写真から切り出す。無ければ写真全体を返す */
    private fun cropToFrameIfAvailable(bitmap: Bitmap): Bitmap {
        val left = frameLeftRatio
        val top = frameTopRatio
        val right = frameRightRatio
        val bottom = frameBottomRatio
        if (left == null || top == null || right == null || bottom == null) return bitmap

        val l = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val t = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val r = (right * bitmap.width).toInt().coerceIn(l + 1, bitmap.width)
        val b = (bottom * bitmap.height).toInt().coerceIn(t + 1, bitmap.height)

        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
    }

    /** 画像を必要サイズまで縮小して読み込む(OutOfMemory対策)。EXIFの回転情報も適用する */
    private fun decodeSampledBitmap(file: File, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)

        var inSampleSize = 1
        val (height, width) = options.outHeight to options.outWidth
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }

        val finalOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, finalOptions)
            ?: throw IllegalStateException("画像の読み込みに失敗しました")

        return rotateBitmapToMatchExif(decoded, file)
    }

    /**
     * カメラアプリはピクセルデータを横向きのまま保存し、正しい向きをEXIFのOrientationタグにだけ
     * 記録することが多い。そのため撮影時に見た向きと表示が一致するよう、ここで実ピクセルを回転させる。
     */
    private fun rotateBitmapToMatchExif(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationDegrees == 0f) return bitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** CropOverlayViewの選択範囲(View座標)をBitmap上の座標に変換してトリミングする */
    private fun cropSelectedArea(): Bitmap? {
        val bitmap = originalBitmap ?: return null
        val viewRect = cropOverlayView.getSelectionRect() ?: return bitmap // 未選択なら全体を対象にする

        // ImageViewのimageMatrixは「Bitmap座標 -> View座標」の変換なので、逆行列で戻す
        val matrix = Matrix()
        imageView.imageMatrix.invert(matrix)

        val bitmapRectF = RectF(viewRect)
        matrix.mapRect(bitmapRectF)

        val left = max(0, bitmapRectF.left.toInt())
        val top = max(0, bitmapRectF.top.toInt())
        val right = min(bitmap.width, bitmapRectF.right.toInt())
        val bottom = min(bitmap.height, bitmapRectF.bottom.toInt())

        if (right <= left || bottom <= top) {
            Toast.makeText(this, "選択範囲が不正です", Toast.LENGTH_SHORT).show()
            return null
        }

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun printSelectedArea() {
        val cropped = cropSelectedArea() ?: return

        // androidx.print の PrintHelper を使うと、システムの印刷ダイアログが開き
        // Wi-Fi/無線接続されたプリンタ(Mopria対応機種やメーカー純正Print Service経由)を選択できる
        val printHelper = PrintHelper(this).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }

        try {
            printHelper.printBitmap("selected_photo_" + System.currentTimeMillis(), cropped)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "印刷を開始できませんでした。プリンタの印刷サービス(例: Mopria Print Service)が" +
                    "インストール・有効化されているか確認してください。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * OCRの認識精度を上げるための前処理。
     * 1) 選択範囲が小さい場合は文字の解像度を確保するため拡大する
     * 2) グレースケール化 + コントラスト強調で文字と背景の境界をくっきりさせる
     */
    private fun preprocessForOcr(bitmap: Bitmap): Bitmap {
        val minHeightForOcr = 600
        val scale = if (bitmap.height < minHeightForOcr) {
            minHeightForOcr.toFloat() / bitmap.height
        } else 1f

        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val result = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        // コントラストを強めつつ、彩度を落としてグレースケールに近づける
        val contrast = 1.5f
        val brightness = -40f
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val grayscaleMatrix = ColorMatrix().apply { setSaturation(0f) }
        grayscaleMatrix.postConcat(contrastMatrix)

        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(grayscaleMatrix) }
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        return result
    }

    /**
     * 「範囲を選んで再認識」ボタンの処理。
     * 編集欄にフォーカスがあって写真が隠れている状態の場合は、まず写真を表示するだけに留め、
     * 範囲を選び直せるようにする。写真が見えている状態であれば、そのまま選択範囲で認識を実行する。
     */
    private fun onOcrButtonClicked() {
        if (photoContainer.visibility != View.VISIBLE) {
            etRecognizedText.clearFocus()
            setTextEditingExpanded(false)
            hideKeyboard()
            tvHint.text = "写真の上で範囲を選んでから、もう一度「範囲を選んで再認識」を押してください"
        } else {
            recognizeTextInSelection()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etRecognizedText.windowToken, 0)
    }

    /** 選択範囲を切り出し、その画像に対してOCR(文字認識)を実行する。範囲未選択なら写真全体が対象になる */
    private fun recognizeTextInSelection() {
        val cropped = cropSelectedArea() ?: return
        runOcr(cropped)
    }

    /** 指定したBitmapに対してOCR(文字認識)を実行する共通処理 */
    private fun runOcr(bitmap: Bitmap) {
        val processed = preprocessForOcr(bitmap)
        val inputImage = InputImage.fromBitmap(processed, 0)

        btnOcr.isEnabled = false
        tvHint.text = "文字を認識しています…"

        textRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                btnOcr.isEnabled = true
                val recognized = result.text
                if (recognized.isBlank()) {
                    tvHint.text = "文字を認識できませんでした。範囲を調整して再度お試しください"
                    btnPrintText.isEnabled = false
                } else {
                    etRecognizedText.setText(recognized)
                    btnPrintText.isEnabled = true
                    tvHint.text = "認識結果を確認・修正してから印刷してください" +
                        "(うまく読めない場合は範囲を選んで「選択範囲を文字認識」で再認識できます)"
                }
            }
            .addOnFailureListener { e ->
                btnOcr.isEnabled = true
                tvHint.text = "文字認識に失敗しました"
                Toast.makeText(
                    this,
                    "文字認識でエラーが発生しました: ${e.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    /** 認識(または編集)されたテキストを1枚の紙のレイアウトに描画し、印刷する */
    private fun printRecognizedText() {
        val text = etRecognizedText.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "印刷するテキストがありません", Toast.LENGTH_SHORT).show()
            return
        }

        val textBitmap = createTextBitmap(text)
        val printHelper = PrintHelper(this).apply {
            scaleMode = PrintHelper.SCALE_MODE_FIT
        }

        try {
            printHelper.printBitmap("recognized_text_" + System.currentTimeMillis(), textBitmap)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "印刷を開始できませんでした。プリンタの印刷サービスが有効になっているか確認してください。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** テキストをA4相当の白紙レイアウトに描画したBitmapを生成する */
    private fun createTextBitmap(text: String): Bitmap {
        val pageWidth = 1240 // 約A4サイズ相当(150dpi換算)の幅
        val padding = 60

        val textPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 32f
            isAntiAlias = true
        }

        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, pageWidth - padding * 2)
            .setLineSpacing(0f, 1.3f)
            .build()

        val pageHeight = max(layout.height + padding * 2, 800)
        val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(padding.toFloat(), padding.toFloat())
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
