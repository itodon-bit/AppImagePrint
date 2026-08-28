package com.example.photoprint

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File

/**
 * ターゲット枠(狙いを定めるガイド)付きの自前カメラ画面。
 * システム標準のカメラアプリでは自作の枠を重ねられないため、
 * CameraXでライブプレビューを表示し、その上にガイドを重ねている。
 *
 * 撮影が完了すると、保存先ファイルの絶対パスを EXTRA_PHOTO_PATH に入れて
 * RESULT_OK で呼び出し元(MainActivity)に返す。
 */
class CameraActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_PATH = "photo_path"
        // 撮影時のターゲット枠の位置・サイズを、プレビュー画面に対する割合(0.0〜1.0)で渡すためのキー
        const val EXTRA_FRAME_LEFT_RATIO = "frame_left_ratio"
        const val EXTRA_FRAME_TOP_RATIO = "frame_top_ratio"
        const val EXTRA_FRAME_RIGHT_RATIO = "frame_right_ratio"
        const val EXTRA_FRAME_BOTTOM_RATIO = "frame_bottom_ratio"
    }

    private lateinit var previewView: PreviewView
    private lateinit var targetOverlayView: TargetOverlayView
    private lateinit var liveResultText: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnCancel: Button
    private lateinit var btnToggleFrame: Button

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var isFrameVisible = true

    // 撮影前のプレビュー中に、枠内の文字をリアルタイムで認識するための仕組み
    private val liveTextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val targetCodeRegex = Regex("""\d{2}[A-Za-z\d]*-\d+""")
    private var isAnalyzing = false
    // 対象コードを検出して自動撮影した後、連続で何度も撮影しないようにするためのフラグ
    private var hasAutoCaptured = false

    // ズーム倍率を計算する基準となる、枠の標準サイズ(初期状態)の幅
    private var referenceGuideWidth: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        targetOverlayView = findViewById(R.id.targetOverlayView)
        liveResultText = findViewById(R.id.liveResultText)
        btnCapture = findViewById(R.id.btnCapture)
        btnCancel = findViewById(R.id.btnCancel)
        btnToggleFrame = findViewById(R.id.btnToggleFrame)

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnCapture.setOnClickListener { takePhoto() }
        btnToggleFrame.setOnClickListener {
            isFrameVisible = !isFrameVisible
            // GONEではなくINVISIBLEにすることで、レイアウトの再計算を発生させずに
            // 枠だけを一時的に隠し、カメラの見た目を確認できるようにする
            targetOverlayView.visibility = if (isFrameVisible) View.VISIBLE else View.INVISIBLE
            liveResultText.visibility = if (isFrameVisible) View.VISIBLE else View.INVISIBLE
            btnToggleFrame.text = if (isFrameVisible) "枠を隠す" else "枠を表示"
        }

        // 枠のサイズが変わるたびに、それに応じてカメラのズームを調整する
        targetOverlayView.onRectChanged = { rect -> updateZoomForRect(rect) }

        startCameraPreview()
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

            // 撮影前のプレビュー中に、枠内の文字をリアルタイムで認識するための解析ユースケース
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                        analyzeFrameForLiveText(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalysis
                )
                // レイアウト確定後の枠の初期サイズを、ズーム計算の基準として記録しておく
                targetOverlayView.post {
                    referenceGuideWidth = targetOverlayView.getGuideRect()?.width()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "カメラを起動できませんでした: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 枠の幅が基準サイズよりどれだけ小さくなったかに応じて、カメラのズーム倍率を調整する。
     * 枠を小さくする(=もっと寄って撮りたい)ほど、ズーム倍率が上がる。
     */
    private fun updateZoomForRect(rect: RectF) {
        val referenceWidth = referenceGuideWidth ?: return
        if (rect.width() <= 0f) return

        val rawZoomRatio = referenceWidth / rect.width()
        val zoomState = camera?.cameraInfo?.zoomState?.value
        val minZoomRatio = zoomState?.minZoomRatio ?: 1f
        val maxZoomRatio = zoomState?.maxZoomRatio ?: 1f
        val clampedZoomRatio = rawZoomRatio.coerceIn(minZoomRatio, maxZoomRatio)

        camera?.cameraControl?.setZoomRatio(clampedZoomRatio)
    }

    /**
     * カメラのプレビューフレームを解析し、ターゲット枠に対応する範囲内の文字だけをリアルタイムで認識する。
     * 正規表現に合うコードが見つかった場合は、その場で画面に表示する。
     * OCR処理には時間がかかるため、前回の解析が終わるまでは新しいフレームをスキップする(間引き処理)。
     */
    @ExperimentalGetImage
    private fun analyzeFrameForLiveText(imageProxy: androidx.camera.core.ImageProxy) {
        if (isAnalyzing || !isFrameVisible) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isAnalyzing = true
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // 回転後の実際の画像サイズ(縦横が入れ替わる場合を考慮)
        val imageWidth = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.height else mediaImage.width
        val imageHeight = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.width else mediaImage.height
        val roi = mapGuideRectToImageRect(imageWidth, imageHeight)

        liveTextRecognizer.process(inputImage)
            .addOnSuccessListener { result ->
                // 枠(ROI)に重なっている行だけを対象にする。枠の情報が取れない場合は全体を対象にする
                val relevantLines = if (roi != null) {
                    result.textBlocks.asSequence()
                        .flatMap { it.lines.asSequence() }
                        .filter { line ->
                            val box = line.boundingBox
                            box != null && roi.contains(box.centerX(), box.centerY())
                        }
                        .toList()
                } else {
                    result.textBlocks.flatMap { it.lines }
                }

                val matchedLine = relevantLines.firstOrNull { targetCodeRegex.containsMatchIn(it.text) }

                when {
                    matchedLine != null && !hasAutoCaptured -> {
                        // 対象コードを検出。その部分だけを狙って自動的にシャッターを切る
                        val box = matchedLine.boundingBox
                        if (box != null) {
                            hasAutoCaptured = true
                            liveResultText.text = "検出: ${matchedLine.text} → 自動撮影します"
                            liveResultText.setTextColor(Color.parseColor("#00E676"))
                            takePhoto(paddedRatioRect(box, imageWidth, imageHeight))
                        } else {
                            updateLiveResultDisplay(matchedLine.text)
                        }
                    }
                    matchedLine != null -> {
                        // 既に自動撮影を開始済みなので、表示だけ更新して撮影は行わない
                        liveResultText.text = "検出: ${matchedLine.text}"
                        liveResultText.setTextColor(Color.parseColor("#00E676"))
                    }
                    else -> {
                        updateLiveResultDisplay(relevantLines.joinToString("\n") { it.text })
                    }
                }
            }
            .addOnFailureListener {
                // ライブプレビューでの失敗は無視する(次のフレームでまた試すため)
            }
            .addOnCompleteListener {
                isAnalyzing = false
                imageProxy.close()
            }
    }

    /**
     * 検出した文字の周辺に少し余白を持たせつつ、画像全体に対する割合(0.0〜1.0)の矩形に変換する。
     * 文字ぴったりだと余白が無さすぎて見づらくなるため、上下左右に少し余裕を持たせている。
     */
    private fun paddedRatioRect(box: Rect, imageWidth: Int, imageHeight: Int): RectF {
        val paddingX = box.width() * 0.25f
        val paddingY = box.height() * 0.6f
        val left = (box.left - paddingX).coerceAtLeast(0f)
        val top = (box.top - paddingY).coerceAtLeast(0f)
        val right = (box.right + paddingX).coerceAtMost(imageWidth.toFloat())
        val bottom = (box.bottom + paddingY).coerceAtMost(imageHeight.toFloat())
        return RectF(left / imageWidth, top / imageHeight, right / imageWidth, bottom / imageHeight)
    }

    /** 枠(View座標)を、解析用画像のピクセル座標に変換する */
    private fun mapGuideRectToImageRect(imageWidth: Int, imageHeight: Int): Rect? {
        val rect = targetOverlayView.getGuideRect() ?: return null
        if (targetOverlayView.width <= 0 || targetOverlayView.height <= 0) return null

        val leftRatio = rect.left / targetOverlayView.width
        val topRatio = rect.top / targetOverlayView.height
        val rightRatio = rect.right / targetOverlayView.width
        val bottomRatio = rect.bottom / targetOverlayView.height

        val l = (leftRatio * imageWidth).toInt().coerceIn(0, imageWidth - 1)
        val t = (topRatio * imageHeight).toInt().coerceIn(0, imageHeight - 1)
        val r = (rightRatio * imageWidth).toInt().coerceIn(l + 1, imageWidth)
        val b = (bottomRatio * imageHeight).toInt().coerceIn(t + 1, imageHeight)
        return Rect(l, t, r, b)
    }

    /** ライブ認識結果を画面に反映する。正規表現に合うコードが見つかった場合は目立つ色で表示する */
    private fun updateLiveResultDisplay(text: String) {
        val match = targetCodeRegex.find(text)
        when {
            match != null -> {
                liveResultText.text = "検出: ${match.value}"
                liveResultText.setTextColor(Color.parseColor("#00E676"))
            }
            text.isNotBlank() -> {
                liveResultText.text = text.take(30)
                liveResultText.setTextColor(Color.WHITE)
            }
            else -> {
                liveResultText.text = "枠内の文字を認識中…"
                liveResultText.setTextColor(Color.LTGRAY)
            }
        }
    }

    /**
     * 撮影を実行する。
     * @param autoDetectedRatioRect 自動検出によって撮影する場合、検出した文字の範囲(画像全体に対する割合)。
     *   nullの場合(手動で撮影ボタンを押した場合)は、表示中のターゲット枠の範囲を使う。
     */
    private fun takePhoto(autoDetectedRatioRect: RectF? = null) {
        val capture = imageCapture ?: return

        val imagesDir = File(getExternalFilesDir(null), "images").apply { mkdirs() }
        val file = File(imagesDir, "captured_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        btnCapture.isEnabled = false

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val resultIntent = Intent().putExtra(EXTRA_PHOTO_PATH, file.absolutePath)

                    if (autoDetectedRatioRect != null) {
                        // 自動検出された文字の範囲だけを狙って渡す
                        resultIntent.putExtra(EXTRA_FRAME_LEFT_RATIO, autoDetectedRatioRect.left)
                        resultIntent.putExtra(EXTRA_FRAME_TOP_RATIO, autoDetectedRatioRect.top)
                        resultIntent.putExtra(EXTRA_FRAME_RIGHT_RATIO, autoDetectedRatioRect.right)
                        resultIntent.putExtra(EXTRA_FRAME_BOTTOM_RATIO, autoDetectedRatioRect.bottom)
                    } else {
                        // 撮影時のターゲット枠の位置・サイズを、プレビュー画面に対する割合で一緒に渡す。
                        // MainActivity側でこの割合を使い、撮影された写真のうち枠内の部分だけを切り出せるようにする。
                        // ただし、枠を隠した状態で撮影した場合は「カメラの見た目そのまま」を撮りたいという
                        // 意図とみなし、枠の情報を渡さない(=写真全体がOCRの対象になる)。
                        val rect = targetOverlayView.getGuideRect()
                        if (isFrameVisible && rect != null &&
                            targetOverlayView.width > 0 && targetOverlayView.height > 0
                        ) {
                            resultIntent.putExtra(EXTRA_FRAME_LEFT_RATIO, rect.left / targetOverlayView.width)
                            resultIntent.putExtra(EXTRA_FRAME_TOP_RATIO, rect.top / targetOverlayView.height)
                            resultIntent.putExtra(EXTRA_FRAME_RIGHT_RATIO, rect.right / targetOverlayView.width)
                            resultIntent.putExtra(EXTRA_FRAME_BOTTOM_RATIO, rect.bottom / targetOverlayView.height)
                        }
                    }

                    setResult(RESULT_OK, resultIntent)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    // 撮影に失敗した場合は、自動撮影をやり直せるようにフラグを戻す
                    hasAutoCaptured = false
                    Toast.makeText(
                        this@CameraActivity,
                        "撮影に失敗しました: ${exception.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}
