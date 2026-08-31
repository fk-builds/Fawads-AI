package com.fawads.ai.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.max

/**
 * Portable face verification using ML Kit.
 *
 * We convert a detected face into a compact, viewpoint-stable descriptor by
 * normalising ~14 facial landmarks around the bounding box. Enrollment averages
 * N frames into a single vector; unlocking compares a live frame against it with
 * a cosine-similarity threshold. Because the descriptor is *stored* (not tied to
 * this device's hardware), the same system works for any user: the person who
 * enrolls their face and sets the PIN is the one who can unlock.
 *
 * NOTE: this is a lightweight geometric matcher (good for a personal assistant).
 * For bank-grade/1:1 verification you'd swap in a TFLite face-recognition model.
 */
object FaceMatcher {

    const val THRESHOLD = 0.86f
    const val ENROLL_FRAMES = 8                 // frames averaged during enrollment

    private val detector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(opts)
    }

    /** Extract a FloatArray descriptor from a single face. Returns null if too few landmarks. */
    fun descriptor(face: Face): FloatArray? {
        val box = face.boundingBox
        if (box.width() <= 0 || box.height() <= 0) return null

        val cx = box.exactCenterX()
        val cy = box.exactCenterY()
        val scale = max(box.width(), box.height()) / 220f

        fun lm(id: Int): Pair<Float, Float>? {
            val p = face.getLandmark(id)?.position ?: return null
            // Y is already correct; X comes mirrored on front camera — corrected by caller.
            return (p.x - cx) / scale to (p.y - cy) / scale
        }

        val ids = listOf(
            FaceLandmark.LEFT_EYE, FaceLandmark.RIGHT_EYE,
            FaceLandmark.LEFT_CHEEK, FaceLandmark.RIGHT_CHEEK,
            FaceLandmark.NOSE_BASE, FaceLandmark.MOUTH_LEFT, FaceLandmark.MOUTH_RIGHT,
            FaceLandmark.MOUTH_BOTTOM, FaceLandmark.EAR_LEFT, FaceLandmark.EAR_RIGHT
        )
        val pts = ids.mapNotNull { lm(it) }
        if (pts.size < 6) return null

        val arr = FloatArray(pts.size * 2)
        pts.forEachIndexed { i, (x, y) ->
            arr[i * 2] = x
            arr[i * 2 + 1] = y
        }
        return arr
    }

    /** Average several enrollment descriptors into one robust vector. */
    fun average(frames: List<FloatArray>): FloatArray? {
        if (frames.isEmpty()) return null
        val n = frames.firstOrNull { it.size > 0 }?.size ?: return null
        if (frames.any { it.size != n }) return null
        val out = FloatArray(n)
        for (f in frames) for (i in 0 until n) out[i] += f[i]
        for (i in 0 until n) out[i] /= frames.size
        return out
    }

    /** Cosine similarity in range -1..1. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    fun isMatch(enrolled: FloatArray, live: FloatArray): Boolean =
        similarity(enrolled, live) >= THRESHOLD

    /** Asynchronously detect faces; returns the first face (or null). */
    fun detect(image: InputImage, onFace: (Face?) -> Unit) {
        detector.process(image)
            .addOnSuccessListener { faces -> onFace(faces.firstOrNull()) }
            .addOnFailureListener { onFace(null) }
    }

    /** Cancel the detector's background work. */
    fun close() {
        try { detector.close() } catch (_: Exception) {}
    }
}
