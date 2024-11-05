package com.example.textrecognitionapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {
    private var camera: Camera? = null
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var resultTextView: TextView
    private lateinit var webView: WebView
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastProcessingTimeMs = 0L
    private val processingIntervalMs = 500L // 每500ms处理一次

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultTextView = findViewById(R.id.recognitionResultView)
        setupWebView()
        setupCamera()
    }

    private fun setupCamera() {
        surfaceHolder = findViewById<SurfaceView>(R.id.cameraSurfaceView).holder
        surfaceHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (checkCameraPermission()) {
                    initializeCamera()
                } else {
                    requestCameraPermission()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                camera?.let { cam ->
                    try {
                        cam.stopPreview()
                        cam.setPreviewDisplay(holder)
                        cam.startPreview()
                    } catch (e: Exception) {
                        Log.e("Camera", "Error restarting preview", e)
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                releaseCamera()
            }
        })
    }

    private fun initializeCamera() {
        try {
            camera = Camera.open(0)
            camera?.let { cam ->
                val parameters = cam.parameters
                
                // 设置对焦模式
                if (parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                } else if (parameters.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                }

                // 设置预览尺寸
                val sizes = parameters.supportedPreviewSizes
                val optimalSize = sizes.firstOrNull { 
                    it.width >= 1280 && it.height >= 720 
                } ?: sizes.maxByOrNull { 
                    it.width * it.height 
                } ?: sizes[0]
                parameters.setPreviewSize(optimalSize.width, optimalSize.height)

                // 设置预览格式
                parameters.previewFormat = ImageFormat.NV21

                cam.parameters = parameters
                cam.setPreviewDisplay(surfaceHolder)
                cam.startPreview()

                // 添加定期自动对焦
                val autoFocusHandler = Handler()
                val autoFocusRunnable = object : Runnable {
                    override fun run() {
                        try {
                            camera?.autoFocus { success, _ ->
                                Log.d("Camera", "Auto focus result: $success")
                                autoFocusHandler.postDelayed(this, 2000)
                            }
                        } catch (e: Exception) {
                            Log.e("Camera", "Auto focus failed", e)
                        }
                    }
                }
                autoFocusHandler.postDelayed(autoFocusRunnable, 1000)

                // 设置预览回调
                setupPreviewCallback()
            }
        } catch (e: Exception) {
            Log.e("Camera", "Error initializing camera", e)
            e.printStackTrace()
        }
    }

    private fun setupPreviewCallback() {
        camera?.setPreviewCallback { data, camera ->
            val currentTimeMs = System.currentTimeMillis()
            if (currentTimeMs - lastProcessingTimeMs < processingIntervalMs) {
                return@setPreviewCallback
            }
            lastProcessingTimeMs = currentTimeMs

            try {
                val size = camera.parameters.previewSize
                Log.d("Camera", "Processing frame: ${size.width}x${size.height}")

                // 转换图像格式
                val image = YuvImage(data, ImageFormat.NV21, size.width, size.height, null)
                val out = ByteArrayOutputStream()
                val rect = android.graphics.Rect(0, 0, size.width, size.height)
                image.compressToJpeg(rect, 100, out)
                val imageBytes = out.toByteArray()
                Log.d("Camera", "Compressed image size: ${imageBytes.size} bytes")

                // 使用 BitmapFactory 解码图像
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                if (bitmap != null) {
                    // 旋转图像
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(90f)
                    val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                    )

                    // 使用 Bitmap 创建 InputImage
                    val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
                    Log.d("TextRecognition", "Created input image from bitmap")

                    // 使用 ML Kit 进行文本识别
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            // 过滤并处理文本
                            val recognizedText = visionText.text.filter { char ->
                                char.isLetterOrDigit() || char.isWhitespace() || 
                                char in setOf('.', ',', '!', '?', '@', '#', '$', '%', '&', '*', '(', ')', '-', '+', '=', '[', ']', '{', '}', '/', '<', '>')
                            }.trim()

                            if (recognizedText.isNotEmpty()) {
                                handleRecognizedText(recognizedText)
                            }

                            // 清理位图
                            rotatedBitmap.recycle()
                            bitmap.recycle()
                        }
                        .addOnFailureListener { e ->
                            Log.e("TextRecognition", "Text recognition failed", e)
                            // 清理位图
                            rotatedBitmap.recycle()
                            bitmap.recycle()
                        }
                }

            } catch (e: Exception) {
                Log.e("TextRecognition", "Error processing image", e)
                e.printStackTrace()
            }
        }
    }

    private fun releaseCamera() {
        try {
            camera?.stopPreview()
            camera?.release()
            camera = null
        } catch (e: Exception) {
            Log.e("Camera", "Error releasing camera", e)
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        textRecognizer.close()
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            layoutAlgorithm = android.webkit.WebSettings.LayoutAlgorithm.SINGLE_COLUMN
            setNeedInitialFocus(false)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 注入 CSS 来隐藏滚动条
                webView.evaluateJavascript(
                    """
                    (function() {
                        var style = document.createElement('style');
                        style.type = 'text/css';
                        style.innerHTML = '::-webkit-scrollbar { display: none; }';
                        document.head.appendChild(style);
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        webView.loadUrl("http://10.44.254.39:8090")
    }

    private fun handleRecognizedText(recognizedText: String) {
        if (recognizedText.isNotEmpty()) {
            runOnUiThread {
                resultTextView.text = recognizedText
                
                // 在网页中执行搜索
                webView.evaluateJavascript(
                    """
                    (function() {
                        var inputs = document.getElementsByTagName('input');
                        for (var i = 0; i < inputs.length; i++) {
                            var input = inputs[i];
                            if (input.type === 'search' || input.type === 'text') {
                                input.value = '$recognizedText';
                                input.dispatchEvent(new Event('input', { bubbles: true }));
                                input.dispatchEvent(new Event('change', { bubbles: true }));
                                var form = input.form;
                                if (form) {
                                    form.submit();
                                    console.log('Form submitted with: $recognizedText');
                                    break;
                                }
                            }
                        }
                    })();
                    """.trimIndent()
                ) { result ->
                    Log.d("WebView", "Search result: $result")
                }
                
                // 2秒后清除显示的文本
                resultTextView.postDelayed({
                    resultTextView.text = ""
                }, 2000)
            }
        }
    }

    companion object {
        private const val CAMERA_PERMISSION_REQUEST_CODE = 100
    }
} 