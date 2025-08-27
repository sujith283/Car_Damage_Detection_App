package com.CarDamageDetection.myapplication

// ✅ THESE are your imports (correct already)
import android.graphics.*
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

// ✅ BELOW this is where your MainActivity class starts
class MainActivity : AppCompatActivity() {

    private lateinit var tflite: Interpreter
    private lateinit var viewFinder: PreviewView
    private lateinit var maskOverlay: ImageView
    private lateinit var classLabels: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val imageSize = 256
    private val numClasses = 9
    private val labels = arrayOf(
        "damaged bumper", "damaged door", "damaged headlight",
        "damaged hood", "damaged sidemirror", "damaged window",
        "damaged windshield", "dent", "major scratch"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        maskOverlay = findViewById(R.id.maskOverlay)
        classLabels = findViewById(R.id.classLabels)

        tflite = Interpreter(loadModelFile("unet_damage_segmentation.tflite"))
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(imageSize, imageSize))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, ImageAnalysis.Analyzer { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        val input = bitmap.toInputBuffer()
                        val output = Array(1) { Array(imageSize) { Array(imageSize) { FloatArray(numClasses) } } }
                        tflite.run(input, output)
                        val maskBitmap = output[0].toColoredMask()
                        val detectedLabels = output[0].getDetectedClasses()

                        runOnUiThread {
                            maskOverlay.setImageBitmap(maskBitmap)
                            classLabels.text = detectedLabels.joinToString(", ")
                        }

                        imageProxy.close()
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadModelFile(filename: String): MappedByteBuffer {
        val fileDescriptor = assets.openFd(filename)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.startOffset, fileDescriptor.declaredLength)
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
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

    private fun Bitmap.toInputBuffer(): ByteBuffer {
        val resized = Bitmap.createScaledBitmap(this, imageSize, imageSize, true)
        val buffer = ByteBuffer.allocateDirect(1 * imageSize * imageSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(imageSize * imageSize)
        resized.getPixels(intValues, 0, imageSize, 0, 0, imageSize, imageSize)
        for (pixel in intValues) {
            buffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f)) // R
            buffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))  // G
            buffer.putFloat(((pixel and 0xFF) / 255.0f))        // B
        }
        return buffer
    }

    private fun Array<Array<FloatArray>>.toColoredMask(): Bitmap {
        val bmp = Bitmap.createBitmap(imageSize, imageSize, Bitmap.Config.ARGB_8888)
        val colors = arrayOf(
            Color.argb(100, 255, 0, 0),     // RED
            Color.argb(100, 0, 0, 255),     // BLUE
            Color.argb(100, 255, 255, 0),   // YELLOW
            Color.argb(100, 0, 255, 0),     // GREEN
            Color.argb(100, 0, 255, 255),   // CYAN
            Color.argb(100, 255, 0, 255),   // MAGENTA
            Color.argb(100, 211, 211, 211), // LIGHT GRAY
            Color.argb(100, 105, 105, 105), // DARK GRAY
            Color.argb(100, 0, 0, 0)        // BLACK
        )
        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                val cls = this[y][x].indices.maxByOrNull { this[y][x][it] } ?: 0
                bmp.setPixel(x, y, colors[cls])
            }
        }
        return bmp
    }

    private fun Array<Array<FloatArray>>.getDetectedClasses(): List<String> {
        val presence = BooleanArray(numClasses)
        for (y in 0 until imageSize) {
            for (x in 0 until imageSize) {
                val cls = this[y][x].indices.maxByOrNull { this[y][x][it] } ?: 0
                presence[cls] = true
            }
        }
        return labels.filterIndexed { index, _ -> presence[index] }
    }
}
