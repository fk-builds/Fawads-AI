package com.fawads.ai.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.fawads.ai.R
import kotlin.math.abs
import kotlin.math.sin

// ------------------------------------------------------------------
// WaveformView — amplitude reactive bar visualiser
// ------------------------------------------------------------------
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 20
    }

    private val barHeights = FloatArray(BAR_COUNT)
    private val wave = FloatArray(BAR_COUNT) { it * 0.25f }
    @Volatile private var amplitude = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null

    init {
        for (i in 0 until BAR_COUNT) barHeights[i] = 0.12f
        startAnimation()
    }

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
    }

    fun startAnimation() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val phase = it.animatedValue as Float
                for (i in 0 until BAR_COUNT) {
                    wave[i] = phase + i * 0.42f
                    val base = 0.10f
                    val ampg = 0.25f + 0.75f * abs(sin(wave[i] + it.animatedValue as Float * 2f))
                    val target = base + amplitude * ampg
                    barHeights[i] += (target - barHeights[i]) * 0.3f
                }
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    fun stopAnimation() {
        animator?.cancel()
        animator = null
        amplitude = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = width / BAR_COUNT.toFloat()
        val barW = gap * 0.5f
        val midH = height / 2f
        for (i in 0 until BAR_COUNT) {
            val h = (barHeights[i] * height).coerceAtLeast(6f)
            val left = i * gap + (gap - barW) / 2f
            val top = midH - h / 2f
            val alpha = (150 + 105 * barHeights[i]).toInt().coerceIn(150, 255)
            paint.color = Color.argb(alpha, 255, 23, 68)
            canvas.drawRoundRect(left, top, left + barW, top + h, barW / 2f, barW / 2f, paint)
        }
    }
}

// ------------------------------------------------------------------
// ChatMessage — a single chat line
// ------------------------------------------------------------------
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// ------------------------------------------------------------------
// ChatAdapter
// ------------------------------------------------------------------
class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.chatText)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) TYPE_USER else TYPE_MYRA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == TYPE_USER) R.layout.item_chat_user else R.layout.item_chat_myra
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.text.text = messages[position].text
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(m: ChatMessage) {
        // De-duplicate consecutive identical Fawad's AI replies
        if (!m.isUser) {
            val last = messages.lastOrNull()
            if (last != null && !last.isUser && last.text == m.text) return
        }
        messages.add(m)
        notifyItemInserted(messages.size - 1)
    }

    fun lastMyraText(): String? = messages.lastOrNull { !it.isUser }?.text

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_MYRA = 1
    }
}
