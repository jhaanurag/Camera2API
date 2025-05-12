package com.example.hgcv2 // Ensure this matches your actual package name

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.TonemapCurve // Import for TonemapCurve
import android.media.Image // Keep for ImageReader
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.hgcv2.ui.theme.HGCv2Theme // Make sure your theme import is correct
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream // Ensure this import is present
import java.util.*
import kotlin.math.sqrt // Import for sqrt

class MainActivity : ComponentActivity() {

    // --- Constants ---
    private val TAG = "MainActivity"
    private val FOLDER_NAME = "HGCv2App"
    private val FIXED_TONEMAP_POINTS = 64 // ** Force 16 points **

    // --- Camera State ---
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var imageReader: ImageReader? = null
    private lateinit var cameraCharacteristics: CameraCharacteristics

    // --- Tonemapping ---
    private var isCustomTonemapSupportedForCapture = false
    private var fixed16ptBezierTonemapCurve: TonemapCurve? = null // Curve specifically for capture

    // --- Threading ---
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null

    // --- UI ---
    private lateinit var textureView: TextureView

    // --- Permission Handling ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d(TAG, "Camera permission granted.")
                openCamera()
            } else {
                Log.e(TAG, "Camera permission denied")
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    // --- Activity Lifecycle ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        startBackgroundThread()

        setContent {
            HGCv2Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CameraScreen(Modifier.padding(innerPadding))
                }
            }
        }
        checkCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        startBackgroundThread()
        if (::textureView.isInitialized && textureView.isAvailable && cameraDevice == null) {
            Log.d(TAG, "onResume: TextureView available and camera closed, opening camera.")
            openCamera()
        }
    }

    override fun onPause() {
        Log.d(TAG, "onPause")
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // --- Permission ---
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Camera permission already granted.")
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Showing camera permission rationale.")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                Log.d(TAG, "Requesting camera permission.")
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // --- Camera Setup ---
    @SuppressLint("MissingPermission")
    private fun openCamera() {
        Log.d(TAG, "Attempting to open camera.")
        if (backgroundHandler == null) {
            Log.e(TAG, "Background handler not ready in openCamera."); startBackgroundThread(); if (backgroundHandler == null) { Log.e(TAG, "Failed to start bg handler."); return }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "openCamera called without permission."); checkCameraPermission(); return
        }

        val cameraIds = try { cameraManager.cameraIdList } catch (e: CameraAccessException) { Log.e(TAG, "Failed to get cam ID list.", e); return }
        if (cameraIds.isEmpty()) { Log.e(TAG, "No cameras found."); Toast.makeText(this, "No cameras", Toast.LENGTH_SHORT).show(); return }
        val cameraId = cameraIds[0]

        try {
            Log.d(TAG, "Opening camera: $cameraId")
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)

            // --- Prepare FIXED 16-POINT Bezier Tonemap Curve (only for capture) ---
            prepareFixedPointTonemapCurveForCapture(cameraCharacteristics)

            val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            if (map == null) { Log.e(TAG, "Cannot get stream config map."); Toast.makeText(this, "Failed cam config", Toast.LENGTH_SHORT).show(); return }
            val jpegSizes = map.getOutputSizes(ImageFormat.JPEG)
            val captureSize = jpegSizes?.maxByOrNull { it.height.toLong() * it.width.toLong() } ?: Size(1920, 1080)
            Log.d(TAG, "Selected Capture Size: $captureSize")

            imageReader?.close()
            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 1)
                .apply { setOnImageAvailableListener(onImageAvailableListener, backgroundHandler) }

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: Exception) { // Catch broader exceptions during open
            Log.e(TAG, "Error opening camera $cameraId", e); Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()
        }
    }

    // Prepare the Bezier curve using a fixed number of points (FIXED_TONEMAP_POINTS)
    private fun prepareFixedPointTonemapCurveForCapture(characteristics: CameraCharacteristics) {
        val availableTonemapModes = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)
        isCustomTonemapSupportedForCapture = availableTonemapModes?.contains(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE) == true

        if (isCustomTonemapSupportedForCapture) {
            Log.d(TAG, "TONEMAP_MODE_CONTRAST_CURVE is supported for capture.")
            val maxPointsDevice = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: FIXED_TONEMAP_POINTS
            if (maxPointsDevice < FIXED_TONEMAP_POINTS) {
                Log.w(TAG, "Device reports max tonemap points ($maxPointsDevice) < forced points ($FIXED_TONEMAP_POINTS). May still cause issues.")
            }

            val channelData = TonemapGenerator.createFixedPointBezierTonemapChannelData(characteristics, FIXED_TONEMAP_POINTS)
            if (channelData != null) {
                try {
                    fixed16ptBezierTonemapCurve = TonemapCurve(
                        channelData, channelData.copyOf(), channelData.copyOf()
                    )
                    Log.d(TAG, "Custom FIXED ${FIXED_TONEMAP_POINTS}-POINT Bezier TonemapCurve for CAPTURE generated successfully.")
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Failed to create TonemapCurve object with ${FIXED_TONEMAP_POINTS} points. Likely exceeds device limits.", e)
                    isCustomTonemapSupportedForCapture = false
                    fixed16ptBezierTonemapCurve = null
                }
            } else {
                Log.w(TAG, "Failed to generate fixed ${FIXED_TONEMAP_POINTS}-point Bezier tonemap channel data.")
                isCustomTonemapSupportedForCapture = false
                fixed16ptBezierTonemapCurve = null
            }
        } else {
            Log.w(TAG, "TONEMAP_MODE_CONTRAST_CURVE is not supported for capture.")
            fixed16ptBezierTonemapCurve = null
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) { Log.d(TAG, "Cam opened."); cameraDevice = camera; createCameraPreviewSession() }
        override fun onDisconnected(camera: CameraDevice) { Log.w(TAG, "Cam disconnected."); camera.close(); cameraDevice = null }
        override fun onError(camera: CameraDevice, error: Int) {
            val errorMsg = when(error) { /* ... error messages ... */
                CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Fatal device error"
                CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Camera disabled"
                CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Camera in use"
                CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Fatal service error"
                CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                else -> "Unknown error $error"
            }
            Log.e(TAG, "Cam error: $errorMsg"); camera.close(); cameraDevice = null
            runOnUiThread { Toast.makeText(this@MainActivity, "Camera error: $errorMsg", Toast.LENGTH_LONG).show() }
        }
    }

    private fun createCameraPreviewSession() {
        Log.d(TAG, "Creating preview session.")
        val currentCameraDevice = cameraDevice ?: run { Log.e(TAG, "Preview: cam null"); return }
        val currentImageReaderSurface = imageReader?.surface ?: run { Log.e(TAG, "Preview: reader null"); return }
        if (!::textureView.isInitialized) { Log.e(TAG, "Preview: textureView null"); return }
        val surfaceTexture = textureView.surfaceTexture ?: run { Log.w(TAG, "Preview: surfTexture null"); return }

        try {
            surfaceTexture.setDefaultBufferSize(1920, 1080) // TODO: Use supported preview size
            val previewSurface = Surface(surfaceTexture)

            previewRequestBuilder = currentCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            // --- NO Custom Tonemap Curve for Preview ---
            Log.d(TAG, "Using default tonemapping for PREVIEW request.")

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            previewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

            val surfaces = listOf(previewSurface, currentImageReaderSurface)
            currentCameraDevice.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "Preview session configured.")
                        if (cameraDevice == null) { Log.w(TAG, "Preview: Cam closed"); session.close(); return }
                        captureSession = session
                        try {
                            session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                            Log.d(TAG, "Repeating preview started (default tonemap).")
                        } catch (e: Exception) { Log.e(TAG, "Preview: Failed start repeating", e) }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) { Log.e(TAG, "Preview: Failed configure"); }
                }, backgroundHandler
            )
        } catch (e: Exception) { Log.e(TAG, "Preview: Error creating session", e) }
    }

    private fun takePicture() {
        Log.d(TAG, "takePicture called.")
        val currentCameraDevice = cameraDevice ?: run { Log.e(TAG,"Capture: Cam null"); Toast.makeText(this,"Cam not ready",Toast.LENGTH_SHORT).show(); return }
        val currentCaptureSession = captureSession ?: run { Log.e(TAG,"Capture: Sess null"); Toast.makeText(this,"Sess not ready",Toast.LENGTH_SHORT).show(); return }
        val currentImageReaderSurface = imageReader?.surface ?: run { Log.e(TAG,"Capture: Reader null"); Toast.makeText(this,"Reader not ready",Toast.LENGTH_SHORT).show(); return }
        if (!::cameraCharacteristics.isInitialized) { Log.e(TAG,"Capture: Chars null"); Toast.makeText(this,"Chars not ready",Toast.LENGTH_SHORT).show(); return }

        try {
            val captureBuilder = currentCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(currentImageReaderSurface)

                // --- Apply FIXED 16-POINT Bezier Tonemap Curve to Still Capture ---
                if (isCustomTonemapSupportedForCapture && fixed16ptBezierTonemapCurve != null) {
                    try {
                        set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                        set(CaptureRequest.TONEMAP_CURVE, fixed16ptBezierTonemapCurve!!) // Use FIXED curve
                        Log.d(TAG, "Applied custom FIXED ${FIXED_TONEMAP_POINTS}-POINT Bezier tonemap curve to CAPTURE request.")
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Capture: Failed to set fixed ${FIXED_TONEMAP_POINTS}-point TONEMAP parameters.", e)
                    }
                } else {
                    Log.d(TAG, "Fixed ${FIXED_TONEMAP_POINTS}-point Bezier tonemap not applied to capture (not supported or curve gen failed).")
                }
                // --- End Tonemap ---

                val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { display?.rotation ?: Surface.ROTATION_0 } else { @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation }
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(cameraCharacteristics, displayRotation))
                set(CaptureRequest.CONTROL_AF_MODE, previewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE) ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, previewRequestBuilder.get(CaptureRequest.CONTROL_AE_MODE) ?: CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, previewRequestBuilder.get(CaptureRequest.CONTROL_AWB_MODE) ?: CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { Log.d(TAG, "Capture completed.") }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    val reason = when(failure.reason) { /* ... error messages ... */
                        CaptureFailure.REASON_ERROR -> "Hardware Error"
                        CaptureFailure.REASON_FLUSHED -> "Flushed"
                        else -> "Unknown (${failure.reason})"
                    }
                    Log.e(TAG, "Capture failed. Reason: $reason")
                    runOnUiThread { Toast.makeText(applicationContext, "Capture failed: $reason", Toast.LENGTH_LONG).show() }
                }
            }
            Log.d(TAG, "Initiating capture.")
            currentCaptureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: Exception) { Log.e(TAG, "Capture: Error taking picture", e) }
    }

    // --- Threading, Resource Cleanup, Listeners, Orientation, UI (mostly unchanged) ---
    private fun startBackgroundThread() { if (backgroundThread == null) { backgroundThread = HandlerThread("CameraBackground").also { it.start() }; backgroundHandler = Handler(backgroundThread!!.looper); Log.d(TAG, "Bg thread started.") } }
    private fun stopBackgroundThread() { backgroundThread?.quitSafely(); try { backgroundThread?.join(500); backgroundThread = null; backgroundHandler = null; Log.d(TAG, "Bg thread stopped.") } catch (e: InterruptedException) { Log.e(TAG, "Error stopping bg thread", e); Thread.currentThread().interrupt() } }
    private fun closeCamera() { Log.d(TAG, "Closing camera."); try { captureSession?.close(); captureSession = null; cameraDevice?.close(); cameraDevice = null; imageReader?.close(); imageReader = null; Log.d(TAG, "Cam closed.") } catch (e: Exception) { Log.e(TAG, "Error closing cam", e) } }
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener { override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { Log.d(TAG, "ST available ($w x $h)."); if(cameraDevice == null) { openCamera() } else if (captureSession == null) { createCameraPreviewSession()} } override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {} override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { return true } override fun onSurfaceTextureUpdated(s: SurfaceTexture) {} }
    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader -> var image: Image? = null; try { image = reader.acquireLatestImage(); if (image != null) { val buffer = image.planes[0].buffer; val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); image.close(); image = null; saveImageToStorage(bytes) } } catch (e: Exception) { Log.e(TAG, "Error proc image", e) } finally { image?.close() } }

    // --- CORRECTED Image Saving Logic ---
    private fun saveImageToStorage(bytes: ByteArray) {
        val fileName = "HGCv2_IMG_${System.currentTimeMillis()}.jpg"
        // Declare fos outside the try block so it's accessible for the 'use' block
        var fos: OutputStream? = null
        var savedUriString: String? = "Unknown location"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + FOLDER_NAME)
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                // Assign the opened stream to fos
                fos = imageUri?.let { resolver.openOutputStream(it) }
                if (fos == null) { // Check if opening the stream failed
                    throw java.io.IOException("Failed to create MediaStore entry or open OutputStream.")
                }
                savedUriString = imageUri.toString()
            } else {
                // Older versions: Requires WRITE_EXTERNAL_STORAGE permission
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val imageDir = File(picturesDir, FOLDER_NAME)
                if (!imageDir.exists() && !imageDir.mkdirs()) {
                    Log.e(TAG, "Failed to create directory for saving image: ${imageDir.absolutePath}")
                    throw java.io.IOException("Failed to create directory")
                }
                val imageFile = File(imageDir, fileName)
                // Assign the opened stream to fos
                fos = FileOutputStream(imageFile)
                savedUriString = imageFile.absolutePath
            }

            // Use the 'use' extension function on the non-null fos.
            // It will automatically close the stream afterwards, even if errors occur.
            fos?.use { outputStream -> // fos should not be null here if exceptions weren't thrown
                outputStream.write(bytes)
            } // fos is automatically closed here

            Log.d(TAG, "Image saved successfully: $savedUriString")
            runOnUiThread {
                Toast.makeText(this, "Image saved: $fileName", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) { // Catch specific exceptions like IOException if needed
            Log.e(TAG, "Error saving image", e)
            runOnUiThread {
                Toast.makeText(this, "Error saving image: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        // NO finally block needed to close fos, as .use() handles it.
    }
    // --- End Corrected Image Saving ---

    private fun getJpegOrientation(characteristics: CameraCharacteristics, displayRotation: Int): Int { val deviceOrientation = when (displayRotation) { Surface.ROTATION_0 -> 0; Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270 else -> 0 }; val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0; val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK; val jpegOrientation = if (facing == CameraCharacteristics.LENS_FACING_FRONT) (sensorOrientation - deviceOrientation + 360) % 360 else (sensorOrientation + deviceOrientation + 360) % 360; return jpegOrientation }
    @Composable fun CameraScreen(modifier: Modifier = Modifier) { Column(modifier = modifier.fillMaxSize()) { AndroidView( factory = { ctx -> textureView = TextureView(ctx).apply { this.surfaceTextureListener = this@MainActivity.surfaceTextureListener }; textureView }, modifier = Modifier.weight(1f).fillMaxWidth() ); Button( onClick = { takePicture() }, modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally).fillMaxWidth(0.8f) ) { Text("Take Picture") } } }


    // --- Internal Tonemapping Curve Generator (FIXED POINT COUNT) ---
    private object TonemapGenerator {
        private const val TAG_HELPER = "TonemapGenerator"

        private fun createFixedPointEquidistantPinValues(numPoints: Int): FloatArray? {
            if (numPoints < 2) { Log.e(TAG_HELPER, "Requested point count ($numPoints) < 2."); return null }
            val pinValues = FloatArray(numPoints)
            val step = (TonemapCurve.LEVEL_WHITE - TonemapCurve.LEVEL_BLACK) / (numPoints - 1).toFloat()
            for (i in 0 until numPoints) { pinValues[i] = TonemapCurve.LEVEL_BLACK + i * step }
            pinValues[0] = TonemapCurve.LEVEL_BLACK; pinValues[numPoints - 1] = TonemapCurve.LEVEL_WHITE
            return pinValues
        }

        private fun calculateBezierPoutValues(pinValues: FloatArray): FloatArray {
            val poutValues = FloatArray(pinValues.size)
            for (i in pinValues.indices) {
                val pin = pinValues[i].toDouble(); var pout = 2.0 * sqrt(pin) - pin
                pout = pout.coerceIn(TonemapCurve.LEVEL_BLACK.toDouble(), TonemapCurve.LEVEL_WHITE.toDouble())
                poutValues[i] = pout.toFloat()
            }
            return poutValues
        }

        fun createFixedPointBezierTonemapChannelData(characteristics: CameraCharacteristics, fixedPointCount: Int): FloatArray? {
            val maxPointsDevice = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: fixedPointCount
            if (maxPointsDevice < fixedPointCount) { Log.w(TAG_HELPER,"Device max points ($maxPointsDevice) < requested fixed points ($fixedPointCount).") }

            val pinPoints = createFixedPointEquidistantPinValues(fixedPointCount)
            if (pinPoints == null || pinPoints.isEmpty()) { Log.e(TAG_HELPER, "Could not generate Pin points for fixed $fixedPointCount-point Bezier."); return null }

            val poutPoints = calculateBezierPoutValues(pinPoints)
            if (pinPoints.size != poutPoints.size) { Log.e(TAG_HELPER, "Pin/Pout size mismatch for fixed $fixedPointCount-point curve."); return null }

            val numPoints = pinPoints.size
            val channelData = FloatArray(numPoints * TonemapCurve.POINT_SIZE)
            for (i in 0 until numPoints) {
                channelData[i * TonemapCurve.POINT_SIZE] = pinPoints[i]
                channelData[i * TonemapCurve.POINT_SIZE + 1] = poutPoints[i]
            }
            Log.d(TAG_HELPER, "Generated fixed ${fixedPointCount}-point Bezier tonemap channel data.")
            return channelData
        }
    } // End of TonemapGenerator

} // End of MainActivity class