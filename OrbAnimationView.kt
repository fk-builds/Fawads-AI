package com.fawads.ai.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Custom multi-layer animated orb that visually reflects Fawad's AI state:
 * idle pulse, listening, speaking (amplitude-reactive), thinking and active.
 */
class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State { IDLE, LISTENING, SPEAKING, THINKING, ACTIVE }

    private var state = State.IDLE
    private var amplitude = 0f
    private var progress = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3400
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
        start()
    }

    fun setState(s: State) {
        state = s
        invalidate()
    }

    fun setAmplitude(a: Float) {
        amplitude = a.coerceIn(0f, 1f)
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) / 2f * 0.88f
        val pulse = 1f + 0.10f * sin(progress * 2f * PI.toFloat())
        val r = base * pulse * (1f + amplitude * 0.07f)
        val angle = progress * 360f

        val (c1, c2) = when (state) {
            State.LISTENING -> 0xFFFF1744 to 0xFFD500F9
            State.SPEAKING -> 0xFFE040FB to 0xFFFF1744
            State.THINKING -> 0xFF40C4FF to 0xFF00B0FF
            State.ACTIVE -> 0xFFFF1744 to 0xFFD500F9
            else -> 0xFFB71C1C to 0xFF880E4F
        }

        // ---------- 1. Radial glow ----------
        glowPaint.shader = RadialGradient(
            cx, cy, r * 1.9f,
            Color.argb(80, Color.red(c1), Color.green(c1), Color.blue(c1)),
            0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.9f, glowPaint)

        // ---------- 2. Core orb ----------
        corePaint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.6f, c1, c2, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, corePaint)

        // ---------- 3. Rotating dashed rings ----------
        val ringColor = if (state == State.IDLE)
            Color.argb(70, 255, 255, 255)
        else
            Color.argb(190, Color.red(c1), Color.green(c1), Color.blue(c1))
        ringPaint.color = ringColor
        ringPaint.strokeWidth = 2f
        for (i in 0 until 3) {
            val rr = r * (1.14f + i * 0.10f)
            val start = angle + i * 120f
            canvas.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, start, 68f, false, ringPaint)
        }

        // ---------- 4. Wave rings (speaking / listening) ----------
        if (state == State.SPEAKING || state == State.LISTENING || state == State.ACTIVE) {
            wavePaint.strokeWidth = 2.5f
            for (w in 0 until 3) {
                val wf = (progress * 3f + w) % 3f
                val wr = r * (1.3f + wf * 0.24f + amplitude * 0.12f)
                val alpha = (255 * (1f - wf / 3f)).toInt().coerceIn(0, 255)
                wavePaint.color = Color.argb(alpha, Color.red(c1), Color.green(c1), Color.blue(c1))
                canvas.drawCircle(cx, cy, wr, wavePaint)
            }
        }

        // ---------- 5. Thinking arc ----------
        if (state == State.THINKING) {
            arcPaint.strokeWidth = 4f
            arcPaint.color = 0xEE40C4FF
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 110f, false, arcPaint)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, angle + 180f, 55f, false, arcPaint)
        }

        // ---------- 6. Particles (speaking / active) ----------
        if (state == State.SPEAKING || state == State.ACTIVE) {
            particlePaint.color = Color.argb(220, Color.red(c1), Color.green(c1), Color.blue(c1))
            for (p in 0 until 12) {
                val pa = (progress * 720f + p * 30f).toRadians()
                val pr = r * (1.35f + 0.12f * sin(progress * 4f * PI.toFloat()) + amplitude * 0.2f)
                val px = cx + cos(pa) * pr
                val py = cy + sin(pa) * pr
                canvas.drawCircle(px, py, 3f, particlePaint)
            }
        }

        // ---------- 7. Inner highlight ----------
        highlightPaint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.35f, r * 0.5f, 0x8CFFFFFF, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, highlightPaint)

        // ---------- 8. Rim ----------
        rimPaint.strokeWidth = 2.5f
        rimPaint.color = Color.argb(140, Color.red(c1), Color.green(c1), Color.blue(c1))
        canvas.drawCircle(cx, cy, r, rimPaint)
    }
}

private val PI = Math.PI.toFloat()

private fun Float.toRadians(): Float {
    return this * PI / 180f
}
