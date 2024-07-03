package com.example.currencyconvert

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.currencyconvert.camera.FocusOverlayView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OcrActivity : AppCompatActivity() {
    private lateinit var textureView: PreviewView
    private lateinit var ocrResult: TextView
    private lateinit var spinnerCurrencyFrom: Spinner
    private lateinit var spinnerCurrencyTo: Spinner
    private lateinit var btnPauseResume: Button
    private lateinit var btnSwap: Button
    private var isPaused = false
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var focusOverlayView: FocusOverlayView
    private var imageAnalysis: ImageAnalysis? = null
    private var ocrNumber: Double = 0.0

    private var selectedCurrencyFrom: String = "USD"
    private var selectedCurrencyTo: String = "USD"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ocr)

        textureView = findViewById(R.id.textureView)
        ocrResult = findViewById(R.id.ocrResult)
        spinnerCurrencyFrom = findViewById(R.id.spinnerCurrencyFrom)
        spinnerCurrencyTo = findViewById(R.id.spinnerCurrencyTo)
        focusOverlayView = findViewById(R.id.focusOverlay)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnSwap = findViewById(R.id.btnSwap)

        val currencies = arrayOf("USD", "EUR", "JPY", "GBP")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, currencies)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCurrencyFrom.adapter = adapter
        spinnerCurrencyTo.adapter = adapter

        spinnerCurrencyFrom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                selectedCurrencyFrom = parent.getItemAtPosition(position).toString()
                fetchExchangeRates(selectedCurrencyFrom, selectedCurrencyTo)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        spinnerCurrencyTo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                selectedCurrencyTo = parent.getItemAtPosition(position).toString()
                fetchExchangeRates(selectedCurrencyFrom, selectedCurrencyTo)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        btnPauseResume.setOnClickListener {
            if (isPaused) {
                resumeCamera()
            } else {
                pauseCamera()
            }
        }

        btnSwap.setOnClickListener {
            swapCurrencies()
        }


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        } else {
            startCameraX()
        }
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(textureView.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!isPaused) {
                            processImageProxy(imageProxy)
                        } else {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                Log.d("OcrActivity", "Camera is bound to lifecycle.")
            } catch (exc: Exception) {
                Log.e("OcrActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun pauseCamera() {
        isPaused = true
        btnPauseResume.text = "Resume"
    }

    private fun resumeCamera() {
        isPaused = false
        btnPauseResume.text = "Pause"
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val bitmap = imageProxy.toBitmap()

                    val focusArea = Rect(
                        (bitmap.width * 0.1f).toInt(), // พิกัดซ้าย 10% จากขอบซ้าย
                        ((bitmap.height - (bitmap.height / 4f)) * 2/3f).toInt(),
                        (bitmap.width * 0.9f).toInt(), // พิกัดขวา 10% จากขอบขวา
                        (((bitmap.height - (bitmap.height / 4f)) * 2/3f) + (bitmap.height / 4f) + (bitmap.height * 0.1f)).toInt() // พิกัดล่าง ลงมาข้างล่างเพิ่มอีก 10%
                    )

                    val filteredText = filterTextInFocusArea(visionText, focusArea)
                    val numbersOnly = filterNumbers(filteredText)
                    Log.d("OcrActivity", "Numbers from OCR: $numbersOnly")

                    runOnUiThread {
                        if (!isPaused) {
                            ocrNumber = numbersOnly.toDoubleOrNull() ?: 0.0
                            fetchExchangeRates(selectedCurrencyFrom, selectedCurrencyTo) // คำนวณค่าเมื่อได้ตัวเลขจาก OCR
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("OcrActivity", "Text recognition failed", e)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }



    private fun filterTextInFocusArea(
        visionText: com.google.mlkit.vision.text.Text,
        focusArea: Rect
    ): String {
        val stringBuilder = StringBuilder()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val boundingBox = line.boundingBox
                if (boundingBox != null && isInsideFocusArea(focusArea, boundingBox)) {
                    stringBuilder.append(line.text).append("\n")
                }
            }
        }

        return stringBuilder.toString()
    }




    private fun isInsideFocusArea(focusArea: Rect, boundingBox: Rect): Boolean {
        val centerX = (boundingBox.left + boundingBox.right) / 2
        val centerY = (boundingBox.top + boundingBox.bottom) / 2
        return focusArea.contains(centerX, centerY)
    }

    private fun filterNumbers(text: String): String {
        return text.replace(Regex("[^0-9]"), "")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraX()
        } else {
            Log.e("OcrActivity", "Camera permission denied")
        }
    }

    private fun fetchExchangeRates(currencyFrom: String, currencyTo: String) {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.exchangerate-api.com/v4/latest/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(OkHttpClient.Builder().build())
            .build()

        val service = retrofit.create(ExchangeRateApi::class.java)
        val call = service.getRates(currencyFrom)
        call.enqueue(object : Callback<CurrencyResponse> {
            override fun onResponse(
                call: Call<CurrencyResponse>,
                response: Response<CurrencyResponse>
            ) {
                if (response.isSuccessful) {
                    val rates = response.body()?.rates
                    rates?.let {
                        val rate = it[currencyTo] ?: 0.0
                        val convertedAmount = ocrNumber * rate // Calculate the converted amount as double

                        Log.d("OcrActivity", "Exchange rate: 1 $currencyFrom = $rate $currencyTo")
                        Log.d("OcrActivity", "Converted amount: $ocrNumber $currencyFrom = $convertedAmount $currencyTo")

                        runOnUiThread {
                            ocrResult.text = "$ocrNumber $currencyFrom = ${String.format("%.2f", convertedAmount)} $currencyTo"
                        }
                    }
                } else {
                    Log.e("OcrActivity", "Failed to fetch exchange rates")
                }
            }

            override fun onFailure(call: Call<CurrencyResponse>, t: Throwable) {
                Log.e("OcrActivity", "Error: ${t.message}")
            }
        })
    }


    private fun swapCurrencies() {
        val tempCurrencyFrom = selectedCurrencyFrom
        selectedCurrencyFrom = selectedCurrencyTo
        selectedCurrencyTo = tempCurrencyFrom

        // อัปเดต Spinner ให้แสดงค่าที่ถูกสลับ
        spinnerCurrencyFrom.setSelection((spinnerCurrencyFrom.adapter as ArrayAdapter<String>).getPosition(selectedCurrencyFrom))
        spinnerCurrencyTo.setSelection((spinnerCurrencyTo.adapter as ArrayAdapter<String>).getPosition(selectedCurrencyTo))

        fetchExchangeRates(selectedCurrencyFrom, selectedCurrencyTo) // อัปเดตอัตราแลกเปลี่ยน
    }



    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer // Y
        val uBuffer = planes[1].buffer // U
        val vBuffer = planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun cropToBoundingBox(bitmap: Bitmap, boundingBox: Rect): Bitmap {
        return Bitmap.createBitmap(
            bitmap,
            boundingBox.left,
            boundingBox.top,
            boundingBox.width(),
            boundingBox.height()
        )
    }
}
