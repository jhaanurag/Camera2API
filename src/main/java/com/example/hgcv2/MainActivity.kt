package com.example.hgcv2

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.TonemapCurve
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.hgcv2.ui.theme.HGCv2Theme
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private lateinit var textureView: TextureView
    private var imageReader: ImageReader? = null
    private lateinit var cameraCharacteristics: CameraCharacteristics

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                openCamera()
            } else {
                // Handle permission denial
                Log.e("MainActivity", "Camera permission denied")
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Start background thread early, as openCamera might be called from checkCameraPermission.
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

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                openCamera()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                // Show rationale if needed
                requestPermissionLauncher.launch(Manifest.permission.CAMERA) // Or show a dialog first
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openCamera() {
        if (backgroundHandler == null) {
            Log.e("MainActivity", "backgroundHandler is null in openCamera. Restarting thread.")
            // Attempt to recover, though this indicates a potential lifecycle issue if reached.
            startBackgroundThread()
            if (backgroundHandler == null) { // If still null, cannot proceed
                Log.e("MainActivity", "Failed to start backgroundHandler. Cannot open camera.")
                return
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted. The permission flow should handle requesting it.
            Log.w("MainActivity", "openCamera called without permission.")
            return
        }

        val cameraIds = cameraManager.cameraIdList
        if (cameraIds.isEmpty()) {
            Log.e("MainActivity", "No cameras found on device.")
            Toast.makeText(this, "No cameras found", Toast.LENGTH_SHORT).show()
            return
        }
        val cameraId = cameraIds[0] // Use the first available camera

        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
            val captureSize = jpegSizes?.maxByOrNull { it.height.toLong() * it.width.toLong() } ?: Size(1920, 1080)

            imageReader = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("MainActivity", "Error opening camera", e)
        } catch (e: SecurityException) {
            Log.e("MainActivity", "Security exception opening camera (should be caught by permission check)", e)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close()
            cameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
            Log.e("MainActivity", "Camera error: $error")
            // TODO: Potentially inform user of camera error
        }
    }

    // Utility function to create a custom tonemap curve
    private fun createCustomTonemapChannelData(characteristics: CameraCharacteristics): FloatArray? {
        val maxCurvePoints = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: return null
        val curve = FloatArray(maxCurvePoints * TonemapCurve.POINT_SIZE)

        for (i in 0 until maxCurvePoints) {
            val x = i.toFloat() / (maxCurvePoints - 1)
            val y = Math.pow(x.toDouble(), 1.0 / 2.2).toFloat() // Example gamma correction
            curve[i * 2] = x
            curve[i * 2 + 1] = y
        }
        return curve
    }

    private fun createCameraPreviewSession() {
        try {
            if (!::textureView.isInitialized) {
                Log.e("MainActivity", "textureView not initialized in createCameraPreviewSession")
                return
            }
            val surfaceTexture = textureView.surfaceTexture
            if (surfaceTexture == null) {
                Log.w("MainActivity", "surfaceTexture is null in createCameraPreviewSession. View not ready or destroyed?")
                return
            }
            // Ensure cameraDevice is available
            val currentCameraDevice = cameraDevice
            if (currentCameraDevice == null) {
                Log.w("MainActivity", "cameraDevice is null in createCameraPreviewSession.")
                return
            }
            val imageReaderSurface = imageReader?.surface
            if (imageReaderSurface == null) {
                Log.w("MainActivity", "imageReaderSurface is null in createCameraPreviewSession.")
                return
            }

            surfaceTexture.setDefaultBufferSize(1920, 1080) // Adjust as needed, or get from supported sizes
            val previewSurface = Surface(surfaceTexture)

            previewRequestBuilder = currentCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            val channelData = createCustomTonemapChannelData(cameraCharacteristics)
            if (channelData != null) {
                val customTonemapCurve = TonemapCurve(
                    channelData,
                    channelData.copyOf(),
                    channelData.copyOf()
                )
                previewRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                previewRequestBuilder.set(CaptureRequest.TONEMAP_CURVE, customTonemapCurve)
                Log.d("MainActivity", "Applied custom tonemap curve to CaptureRequest.")
            } else {
                Log.w("MainActivity", "Failed to generate custom tonemap channel data. Using default tonemapping.")
                previewRequestBuilder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
            }

            val surfaces = mutableListOf(previewSurface, imageReaderSurface)

            currentCameraDevice.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            previewRequestBuilder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                            )
                            // Ensure captureSession is not null and cameraDevice is still active
                            if (cameraDevice == null || captureSession == null) {
                                Log.w("MainActivity", "Camera or session became null before setting repeating request.")
                                return
                            }
                            captureSession?.setRepeatingRequest(
                                previewRequestBuilder.build(),
                                null,
                                backgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            Log.e("MainActivity", "Error starting preview", e)
                        } catch (e: IllegalStateException) {
                            Log.e("MainActivity", "IllegalStateException starting preview (session closed?)", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("MainActivity", "Failed to configure camera session")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e("MainActivity", "Error creating preview session", e)
        } catch (e: IllegalStateException) {
            Log.e("MainActivity", "IllegalStateException in createCameraPreviewSession (e.g. camera closed)", e)
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) { // Only start if not already running
            backgroundThread = HandlerThread("CameraBackground").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
            Log.d("MainActivity", "Background thread started.")
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join(500) // Wait for a short period
        } catch (e: InterruptedException) {
            Log.e("MainActivity", "Error stopping background thread", e)
        } finally {
            backgroundThread = null
            backgroundHandler = null
            Log.d("MainActivity", "Background thread stopped.")
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread() // Ensure thread is running

        if (::textureView.isInitialized && textureView.isAvailable) {
            // If camera is not already open (e.g., after onPause), open it.
            if (cameraDevice == null) {
                 openCamera()
            }
        }
        // The SurfaceTextureListener (set in AndroidView factory)
        // will also call openCamera() when the surface becomes available.
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            Log.d("MainActivity", "Camera closed.")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error closing camera resources", e)
        }
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d("MainActivity", "SurfaceTexture available. Opening camera.")
            openCamera()
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Can be used to reconfigure preview size if needed
        }
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d("MainActivity", "SurfaceTexture destroyed.")
            // closeCamera() might be called here too, but onPause should handle it.
            // Returning true indicates the SurfaceTexture is no longer valid.
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
        backgroundHandler?.post {
            val image = reader.acquireLatestImage()
            if (image != null) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                saveImageToStorage(bytes)
                image.close()
            } else {
                Log.w("MainActivity", "ImageReader acquired null image.")
            }
        }
    }

    private fun saveImageToStorage(bytes: ByteArray) {
        val fileName = "HGCv2_IMG_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        var imageUriString: String? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "HGCv2App") // Changed folder name
                }
                val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                fos = imageUri?.let { resolver.openOutputStream(it) }
                imageUriString = imageUri?.toString()
            } else {
                // For older APIs, ensure WRITE_EXTERNAL_STORAGE permission is handled.
                // This example assumes it's granted or relies on implicit grant for app-specific dirs if possible.
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + File.separator + "HGCv2App"
                val imageDirFile = File(imagesDir)
                if (!imageDirFile.exists()) {
                    imageDirFile.mkdirs()
                }
                val imageFile = File(imagesDir, fileName)
                fos = FileOutputStream(imageFile)
                imageUriString = imageFile.absolutePath
            }
            fos?.write(bytes)
            Log.d("MainActivity", "Image saved: $imageUriString")
            runOnUiThread {
                Toast.makeText(this, "Image saved: $fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving image", e)
             runOnUiThread {
                Toast.makeText(this, "Error saving image: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            try {
                fos?.close()
            } catch (e: java.io.IOException) {
                Log.e("MainActivity", "Error closing FileOutputStream", e)
            }
        }
    }

    private fun getJpegOrientation(characteristics: CameraCharacteristics, deviceOrientation: Int): Int {
        var myDeviceOrientation = deviceOrientation
        if (myDeviceOrientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0
        }
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

        myDeviceOrientation = (myDeviceOrientation + 45) / 90 * 90

        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            myDeviceOrientation = -myDeviceOrientation
        }
        return (sensorOrientation + myDeviceOrientation + 360) % 360
    }

    private fun takePicture() {
        if (cameraDevice == null || imageReader == null || !::cameraCharacteristics.isInitialized) {
            Log.e("MainActivity", "Camera device, ImageReader, or characteristics not ready for capture.")
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val captureBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(cameraCharacteristics, windowManager.defaultDisplay.rotation))
                // Optional: Add other settings like flash, quality etc.
                // set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH) // Example for auto flash
            }

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    super.onCaptureCompleted(session, request, result)
                    Log.d("MainActivity", "Picture capture completed.")
                    // You might want to restart the preview here if it stops, or unlock focus.
                    // For simplicity, we assume preview continues or is not significantly impacted for a single shot.
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    super.onCaptureFailed(session, request, failure)
                    Log.e("MainActivity", "Picture capture failed: ${failure.reason}")
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Capture failed: ${failure.reason}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            captureSession?.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("MainActivity", "Error taking picture (CameraAccessException)", e)
        } catch (e: IllegalStateException) {
            Log.e("MainActivity", "Error taking picture (IllegalStateException - session might be closed)", e)
        }
    }

    @Composable
    fun CameraScreen(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom // Pushes button to bottom
        ) {
            AndroidView(
                factory = { ctx ->
                    textureView = TextureView(ctx).apply {
                        surfaceTextureListener = this@MainActivity.surfaceTextureListener
                    }
                    textureView
                },
                modifier = Modifier.weight(1f).fillMaxWidth() // TextureView takes available space
            )
            Button(
                onClick = {
                    Log.d("MainActivity", "Take Picture button clicked")
                    takePicture()
                          },
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
            ) {
                Text("Take Picture")
            }
        }
    }
}