package com.example.photoprint

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
    private lateinit var btnCapture: Button
    private lateinit var btnCancel: Button
    private lateinit var btnToggleFrame: Button

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var isFrameVisible = true

    // ズーム倍率を計算する基準となる、枠の標準サイズ(初期状態)の幅
    private var referenceGuideWidth: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        targetOverlayView = findViewById(R.id.targetOverlayView)
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

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
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
