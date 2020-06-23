package com.example.posedetector.fragments

import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.renderscript.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import com.example.posedetector.R
import com.example.posedetector.utils.Person
import com.example.posedetector.utils.PoseDetector
import com.example.posedetector.utils.bodyJoints
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.experimental.and
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

const val MODEL_WIDTH = 257
const val MODEL_HEIGHT = 257

class CameraFragment : Fragment() {

    private val minConfidence = 0.5
    private val circleRadius = 8.0f

    private var paint = Paint()
    private var displayId: Int = -1
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var surfaceView: SurfaceView? = null
    private var surfaceHolder: SurfaceHolder? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var container: FrameLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var posedetector: PoseDetector

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStart() {
        super.onStart()
        posedetector = PoseDetector(this.requireContext())
    }

    override fun onDestroy() {
        super.onDestroy()
        posedetector.close()
    }

    private lateinit var cameraExecutor: ExecutorService

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionFragment.hasPermission(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                CameraFragmentDirections.actionCameraFragmentToPermissionFragment()
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        container = view as FrameLayout
        surfaceView = container.findViewById(R.id.surface_view)
        viewFinder = container.findViewById(R.id.view_finder)

        surfaceHolder = surfaceView!!.holder
        surfaceView!!.setZOrderOnTop(true)
        surfaceHolder!!.setFormat(PixelFormat.TRANSPARENT)

        cameraExecutor = Executors.newSingleThreadExecutor()

        displayManager.registerDisplayListener(displayListener, null)

        viewFinder.post {
            setupCamera()
        }
    }

    private fun setPaint() {
        paint.color = Color.RED
        paint.textSize = 80.0f
        paint.strokeWidth = 8.0f
    }

    private fun draw(canvas: Canvas, person: Person, bitmap: Bitmap) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        // Draw `bitmap` and `person` in square canvas.
        val screenWidth: Int
        val screenHeight: Int
        val left: Int
        val right: Int
        val top: Int
        val bottom: Int
        if (canvas.height > canvas.width) {
            screenWidth = canvas.width
            screenHeight = canvas.width
            left = 0
            top = (canvas.height - canvas.width) / 2
        } else {
            screenWidth = canvas.height
            screenHeight = canvas.height
            left = (canvas.width - canvas.height) / 2
            top = 0
        }
        right = left + screenWidth
        bottom = top + screenHeight

        setPaint()
//        canvas.drawBitmap(
//            bitmap,
//            Rect(0, 0, bitmap.width, bitmap.height),
//            Rect(left, top, right, bottom),
//            paint
//        )

        val widthRatio = screenWidth.toFloat() / MODEL_WIDTH
        val heightRatio = screenHeight.toFloat() / MODEL_HEIGHT

        // Draw key points over the image.
        for (keyPoint in person.keyPoints) {
            if (keyPoint.score > minConfidence) {
                val position = keyPoint.position
                val adjustedX: Float = position.x.toFloat() * widthRatio + left
                val adjustedY: Float = position.y.toFloat() * heightRatio + top
                canvas.drawCircle(adjustedX, adjustedY, circleRadius, paint)
            }
        }

        for (line in bodyJoints) {
            if (
                (person.keyPoints[line.first.ordinal].score > minConfidence) and
                (person.keyPoints[line.second.ordinal].score > minConfidence)
            ) {
                canvas.drawLine(
                    person.keyPoints[line.first.ordinal].position.x.toFloat() * widthRatio + left,
                    person.keyPoints[line.first.ordinal].position.y.toFloat() * heightRatio + top,
                    person.keyPoints[line.second.ordinal].position.x.toFloat() * widthRatio + left,
                    person.keyPoints[line.second.ordinal].position.y.toFloat() * heightRatio + top,
                    paint
                )
            }
        }

        canvas.drawText(
            "Score: %.2f".format(person.score),
            (15.0f * widthRatio),
            (30.0f * heightRatio + bottom),
            paint
        )
        canvas.drawText(
            "Device: %s".format(posedetector.device),
            (15.0f * widthRatio),
            (50.0f * heightRatio + bottom),
            paint
        )
        canvas.drawText(
            "Time: %.2f ms".format(posedetector.lastInferenceTimeNanos * 1.0f / 1_000_000),
            (15.0f * widthRatio),
            (70.0f * heightRatio + bottom),
            paint
        )

        // Draw!
        surfaceHolder!!.unlockCanvasAndPost(canvas)
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener(Runnable {
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
                    Log.d(TAG, "ImageProxy: $imageProxy")

                    val imageBitmap = imageProxy.toRGBBitmap()

                    val rotateMatrix = Matrix()
                    rotateMatrix.postRotate(90.0f)

                    val rotatedBitmap = Bitmap.createBitmap(
                        imageBitmap, 0, 0, imageProxy.width, imageProxy.height,
                        rotateMatrix, true
                    )

                    imageBitmap.recycle()
                    imageProxy.close()

                    processImage(rotatedBitmap)
                })
            }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(viewFinder.createSurfaceProvider())
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun ImageProxy.toRGBBitmap(): Bitmap {
        val yuvBytes = this.convertToNV21()
        val renderScript = RenderScript.create(requireContext())

        val bitmap = Bitmap.createBitmap(this.width, this.height, Bitmap.Config.ARGB_8888)
        val allocationRgb = Allocation.createFromBitmap(renderScript, bitmap)

        val allocationYuv =
            Allocation.createSized(renderScript, Element.U8(renderScript), yuvBytes.size)
        allocationYuv.copyFrom(yuvBytes)

        val scriptYuvToRGb = ScriptIntrinsicYuvToRGB.create(renderScript, Element.U8_4(renderScript))
        scriptYuvToRGb.setInput(allocationYuv)
        scriptYuvToRGb.forEach(allocationRgb)

        allocationRgb.copyTo(bitmap)

        allocationRgb.destroy()
        allocationYuv.destroy()
        renderScript.destroy()

        return bitmap
    }

    private fun ImageProxy.convertToNV21(): ByteArray {
        val luminanceYBuffer = this.planes[0].buffer
        val chrominanceUBuffer = this.planes[1].buffer
        val chrominanceVBuffer = this.planes[2].buffer
        val colorPixelStride = this.planes[1].pixelStride
        val ySize = luminanceYBuffer.capacity()
        val uSize = chrominanceUBuffer.capacity()
        val dataOffset = this.width * this.height
        val nv21array =
            ByteArray(this.width * this.height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8)

        for (index in 0 until ySize) {
            nv21array[index] = luminanceYBuffer[index] and 255.toByte()
        }

        for (index in 0 until uSize / colorPixelStride) {
            nv21array[dataOffset + 2 * index] = chrominanceVBuffer[index * colorPixelStride]
            nv21array[dataOffset + 2 * index + 1] = chrominanceUBuffer[index * colorPixelStride]
        }

        return nv21array
    }

    private fun ByteArray.toIntArray(): IntArray {
        val byteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        val intArray = IntArray(this.size / 4)
        byteBuffer.asIntBuffer().put(intArray)
        return intArray;
    }

    private fun processImage(bitmap: Bitmap) {
        // Crop bitmap.
        val croppedBitmap = cropBitmap(bitmap)

        // Created scaled version of bitmap for model input.
        val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, MODEL_WIDTH, MODEL_HEIGHT, true)

        // Perform inference.
        val person = posedetector.estimateSinglePose(scaledBitmap)
        val canvas: Canvas = surfaceHolder!!.lockCanvas()

        bitmap.recycle()
        croppedBitmap.recycle()

        draw(canvas, person, scaledBitmap)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cameraExecutor.shutdown()
        displayManager.unregisterDisplayListener(displayListener)
    }

    private fun cropBitmap(bitmap: Bitmap): Bitmap {
        val bitmapRatio = bitmap.height.toFloat() / bitmap.width
        val modelInputRatio = MODEL_HEIGHT.toFloat() / MODEL_WIDTH
        var croppedBitmap = bitmap

        // Acceptable difference between the modelInputRatio and bitmapRatio to skip cropping.
        val maxDifference = 1e-5

        // Checks if the bitmap has similar aspect ratio as the required model input.
        when {
            abs(modelInputRatio - bitmapRatio) < maxDifference -> return croppedBitmap
            modelInputRatio < bitmapRatio -> {
                // New image is taller so we are height constrained.
                val cropHeight = bitmap.height - (bitmap.width.toFloat() / modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    0,
                    (cropHeight / 2).toInt(),
                    bitmap.width,
                    (bitmap.height - cropHeight).toInt()
                )
            }
            else -> {
                val cropWidth = bitmap.width - (bitmap.height.toFloat() * modelInputRatio)
                croppedBitmap = Bitmap.createBitmap(
                    bitmap,
                    (cropWidth / 2).toInt(),
                    0,
                    (bitmap.width - cropWidth).toInt(),
                    bitmap.height
                )
            }
        }
        return croppedBitmap
    }

    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}
