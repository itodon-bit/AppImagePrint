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
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

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
    private lateinit var btnSet: Button

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var isFrameVisible = true
    // 「セット」ボタンが押されるまでは文字認識(ライブ解析)を行わない
    private var isArmed = false

    // 撮影前のプレビュー中に、枠内の文字をリアルタイムで認識するための仕組み
    private val liveTextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val targetCodeRegex = AppConstants.TARGET_CODE_REGEX
    private var isAnalyzing = false
    // 対象コードを検出して自動撮影した後、連続で何度も撮影しないようにするためのフラグ
    private var hasAutoCaptured = false
    // 直近のフレームで対象コードを検出できているかどうか
    private var hasDetectedMatch = false
    // 誤読による1回きりの偶然の一致で撮影しないよう、何フレーム連続で検出できたかを数える
    private var consecutiveMatchCount = 0

    // ズーム倍率を計算する基準となる、枠の標準サイズ(初期状態)の幅
    private var referenceGuideWidth: Float? = null

    // 据え置き撮影(スマホ固定・対象物を入れ替える)を想定し、定期的に枠の中心へピントを合わせ直す
    private val focusHandler = Handler(Looper.getMainLooper())
    private val periodicFocusRunnable = object : Runnable {
        override fun run() {
            focusOnGuideRect()
            focusHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        targetOverlayView = findViewById(R.id.targetOverlayView)
        liveResultText = findViewById(R.id.liveResultText)
        btnCapture = findViewById(R.id.btnCapture)
        btnCancel = findViewById(R.id.btnCancel)
        btnToggleFrame = findViewById(R.id.btnToggleFrame)
        btnSet = findViewById(R.id.btnSet)

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnCapture.setOnClickListener {
            // 手動撮影は検出の有無に関わらず常に実行し、ターゲット枠全体を対象にする
            takePhoto()
        }
        btnToggleFrame.setOnClickListener {
            isFrameVisible = !isFrameVisible
            // GONEではなくINVISIBLEにすることで、レイアウトの再計算を発生させずに
            // 枠だけを一時的に隠し、カメラの見た目を確認できるようにする
            targetOverlayView.visibility = if (isFrameVisible) View.VISIBLE else View.INVISIBLE
            liveResultText.visibility = if (isFrameVisible) View.VISIBLE else View.INVISIBLE
            btnToggleFrame.text = if (isFrameVisible) "枠を隠す" else "枠を表示"
        }
        btnSet.setOnClickListener {
            // 対象物をセットし終えたタイミングでこのボタンを押すことで、ピント合わせと文字認識を開始する
            isArmed = true
            hasAutoCaptured = false
            consecutiveMatchCount = 0
            btnSet.isEnabled = false
            btnSet.text = "認識中…"
            liveResultText.text = "枠内の文字を認識中…"
            liveResultText.setTextColor(Color.LTGRAY)

            // ピント合わせを開始し、以降は定期的に合わせ直す
            focusOnGuideRect()
            focusHandler.removeCallbacks(periodicFocusRunnable)
            focusHandler.postDelayed(periodicFocusRunnable, 1000)
        }

        // 枠のサイズが変わるたびに、それに応じてカメラのズームを調整する。
        // ピントの合わせ直しは「セット」後にのみ行う。
        targetOverlayView.onRectChanged = { rect ->
            updateZoomForRect(rect)
            if (isArmed) {
                focusOnGuideRect()
            }
        }

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
     * ターゲット枠の中心にカメラのピント(オートフォーカス)・露出を合わせる。
     * スマホを固定して対象物を入れ替えるような使い方でも、枠内にちゃんとピントが合うようにするための処理。
     */
    private fun focusOnGuideRect() {
        val cam = camera ?: return
        val rect = targetOverlayView.getGuideRect() ?: return
        if (targetOverlayView.width <= 0 || targetOverlayView.height <= 0) return

        val meteringPoint = previewView.meteringPointFactory.createPoint(rect.centerX(), rect.centerY())
        val action = FocusMeteringAction.Builder(
            meteringPoint,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        )
            // 一定時間経つと自動キャンセルされる設定にしておくことで、
            // 定期的な再実行によって常に枠内へピントを合わせ直せるようにしている
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        try {
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            // 端末によっては対応していない場合があるため、失敗しても無視する
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        focusHandler.removeCallbacks(periodicFocusRunnable)
        // OCRエンジンが確保しているリソースを解放し、メモリを軽くする
        liveTextRecognizer.close()
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
        if (isAnalyzing || !isFrameVisible || !isArmed) {
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
                    matchedLine != null -> {
                        hasDetectedMatch = true
                        consecutiveMatchCount++

                        // 1回の誤読で撮影してしまわないよう、2フレーム連続で検出できてから撮影する
                        val requiredConsecutiveMatches = 2
                        if (!hasAutoCaptured && consecutiveMatchCount >= requiredConsecutiveMatches) {
                            // 対象コードを検出。タイムラグによるズレを避けるため、
                            // 切り出しはピッタリの文字範囲ではなく、ターゲット枠全体を対象にする
                            hasAutoCaptured = true
                            liveResultText.text = "検出: ${matchedLine.text} → 自動撮影します"
                            liveResultText.setTextColor(Color.parseColor("#00E676"))
                            takePhoto()
                        } else if (!hasAutoCaptured) {
                            liveResultText.text = "検出: ${matchedLine.text} (確認中…)"
                            liveResultText.setTextColor(Color.parseColor("#00E676"))
                        } else {
                            // 既に自動撮影を開始済みなので、表示だけ更新して撮影は行わない
                            liveResultText.text = "検出: ${matchedLine.text}"
                            liveResultText.setTextColor(Color.parseColor("#00E676"))
                        }
                    }
                    else -> {
                        hasDetectedMatch = false
                        consecutiveMatchCount = 0
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

    /** 撮影を実行する。ターゲット枠が表示されていれば、その範囲をMainActivity側へ一緒に渡す */
    private fun takePhoto() {
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

                    setResult(RESULT_OK, resultIntent)
                    finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    // 撮影に失敗した場合は、自動撮影・検出状態をやり直せるようにフラグを戻す
                    hasAutoCaptured = false
                    hasDetectedMatch = false
                    consecutiveMatchCount = 0
                    isArmed = false
                    focusHandler.removeCallbacks(periodicFocusRunnable)
                    btnSet.isEnabled = true
                    btnSet.text = "セット"
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
