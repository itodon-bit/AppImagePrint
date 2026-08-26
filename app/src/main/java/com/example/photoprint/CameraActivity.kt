package com.example.photoprint

import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
    }

    private lateinit var previewView: PreviewView
    private lateinit var targetOverlayView: TargetOverlayView
    private lateinit var btnCapture: Button
    private lateinit var btnCancel: Button
    private lateinit var btnResetFrame: Button

    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null

    // ズーム倍率を計算する基準となる、枠の標準サイズ(初期状態)の幅
    private var referenceGuideWidth: Float? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.previewView)
        targetOverlayView = findViewById(R.id.targetOverlayView)
        btnCapture = findViewById(R.id.btnCapture)
        btnCancel = findViewById(R.id.btnCancel)
        btnResetFrame = findViewById(R.id.btnResetFrame)

        btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        btnCapture.setOnClickListener { takePhoto() }
        btnResetFrame.setOnClickListener {
            targetOverlayView.resetToDefault()
            camera?.cameraControl?.setZoomRatio(1f)
        }

        // 枠のサイズが変わるたびに、それに応じてカメラのズームを調整する
        targetOverlayView.onRectChanged = { rect -> updateZoomForRect(rect) }

        startCameraPreview()
    }

    private fun startCameraPreview() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

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
