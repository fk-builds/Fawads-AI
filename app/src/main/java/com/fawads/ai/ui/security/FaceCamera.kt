package com.fawads.ai.ui.security

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.fawads.ai.ai.FaceMatcher
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import java.util.concurrent.Executors

/**
 * Live camera face scanner built on CameraX + ML Kit.
 * Emits a normalised face descriptor on every frame that contains a face.
 */
class FaceCamera(
    context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onDescriptor: (FloatArray?) -> Unit
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

    fun start() {
        val future = ProcessCameraProvider.getInstance(previewView.context)
        future.addListener({
            cameraProvider = future.get()
            bind()
        }, ContextCompat.getMainExecutor(previewView.context))
    }

    private fun bind() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { imageProxy ->
            val mediaImage = imageProxy.image ?: run { imageProxy.close(); return@setAnalyzer }
            val input = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )
            // Close the frame only after ML Kit finishes, so the buffer stays valid.
            FaceMatcher.detect(input) { face ->
                val desc = face?.let { FaceMatcher.descriptor(it) }
                imageProxy.close()
                onDescriptor(desc)
            }
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
        } catch (e: Exception) {
            Log.e("FaceCamera", "bind failed", e)
            onDescriptor(null)
        }
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    fun release() {
        stop()
        analysisExecutor.shutdown()
    }
}
