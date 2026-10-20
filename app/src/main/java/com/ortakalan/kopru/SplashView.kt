package com.ortakalan.kopru

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Açılış animasyonu (~3 sn): dişli dönerek büyür, "3" belirir, kırmızı çizgi çekilir,
 * "ÜÇLER MAKİNA" harf harf yazılır, en altta "Vietra Teknoloji" belirir. Dokununca atlanır.
 */
class SplashView(context: Context, private val onDone: (View) -> Unit) : View(context) {

    companion object {
        private const val TOTAL = 3200L
        private const val TITLE = "ÜÇLER MAKİNA"
        private const val ROLE = "YAYINCI · YAPIMCI · GELİŞTİRİCİ"
        private const val MAKER = "Vietra Teknoloji"
        private val BG = 0xFFF6F7F9.toInt()
        private val BLUE = 0xFF004B94.toInt()
        private val RED = 0xFFED1C24.toInt()
        private val GRAY = 0xFFBDBEC0.toInt()
    }

    private val dens = resources.displayMetrics.density
    private var t = 0f
    private var finished = false

    private val letters = TITLE.toCharArray()
    private var letterX = FloatArray(letters.size)
    private var textSize = 0f
    private var textTotalW = 0f
    private var startX = 0f
    private var baseY = 0f
    private var barTopY = 0f

    private val gearPath = Path()
    private var gearR = 0f
    private var gearCx = 0f
    private var gearCy = 0f
    private val threeBounds = Rect()

    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GRAY }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLUE }
    private val threePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val barPaint = Paint().apply { color = RED }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BLUE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val rolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A828E.toInt()
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val makerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B3440.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val anim = ValueAnimator.ofFloat(0f, TOTAL.toFloat()).apply {
        duration = TOTAL
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                finish()
            }
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!finished) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.removeAllListeners()
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun finish() {
        if (finished) return
        finished = true
        anim.removeAllListeners()
        anim.cancel()
        animate().alpha(0f).setDuration(260).withEndAction { onDone(this) }.start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) finish()
        return true // altındaki tabloya dokunuş geçmesin
    }

    // ------------------------------------------------------------------ yerleşim

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val minDim = min(w, h).toFloat()
        gearR = min(minDim * 0.17f, h * 0.17f)
        gearCx = w / 2f
        gearCy = if (h > w) h * 0.34f else h * 0.30f
        buildGear(gearR)

        threePaint.textSize = gearR * 1.25f
        threePaint.getTextBounds("3", 0, 1, threeBounds)

        // Başlık: genişliğe göre yazı boyutu; yatay ekranda yüksekliği de aşmasın
        val spacing = 3f * dens
        letterPaint.textSize = 40f * dens
        val w0 = measureTitle(spacing)
        val target = min(w * 0.84f, 560f * dens)
        textSize = min(40f * dens * target / w0, h * 0.09f)
        letterPaint.textSize = textSize
        textTotalW = measureTitle(spacing)
        startX = (w - textTotalW) / 2f

        // Dişlinin altından başlayarak yukarıdan aşağıya yerleşim: çizgi, sonra başlık
        barTopY = gearCy + gearR * 1.06f + 24f * dens
        baseY = barTopY + 6f * dens + 16f * dens + textSize * 0.95f

        rolePaint.textSize = 11f * dens
        makerPaint.textSize = 20f * dens
    }

    /** Harflerin sol konumlarını doldurur ve toplam genişliği döndürür. */
    private fun measureTitle(spacing: Float): Float {
        var x = 0f
        for (i in letters.indices) {
            letterX[i] = x
            x += letterPaint.measureText(letters[i].toString()) + spacing
        }
        return x - spacing
    }

    private fun buildGear(r: Float) {
        gearPath.reset()
        val n = 10
        val step = (2.0 * PI / n).toFloat()
        val root = r * 0.80f
        var first = true
        for (i in 0 until n) {
            val a = i * step
            val angles = floatArrayOf(a - 0.34f * step, a - 0.17f * step, a + 0.17f * step, a + 0.34f * step)
            val radii = floatArrayOf(root, r, r, root)
            for (k in 0 until 4) {
                val x = cos(angles[k]) * radii[k]
                val y = sin(angles[k]) * radii[k]
                if (first) {
                    gearPath.moveTo(x, y)
                    first = false
                } else {
                    gearPath.lineTo(x, y)
                }
            }
            // dişler arasındaki kök yayı
            for (k in 1..2) {
                val aa = a + 0.34f * step + 0.32f * step * k / 3f
                gearPath.lineTo(cos(aa) * root, sin(aa) * root)
            }
        }
        gearPath.close()
    }

    // ------------------------------------------------------------------ animasyon

    private fun seg(start: Float, dur: Float): Float = ((t - start) / dur).coerceIn(0f, 1f)

    private fun outCubic(x: Float): Float {
        val k = 1f - x
        return 1f - k * k * k
    }

    private fun outBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val k = x - 1f
        return 1f + c3 * k * k * k + c1 * k * k
    }

    private fun drawGear(c: Canvas, dx: Float, dy: Float, sc: Float, rot: Float, paint: Paint) {
        c.save()
        c.translate(gearCx + dx, gearCy + dy)
        c.scale(sc, sc)
        c.rotate(rot)
        c.drawPath(gearPath, paint)
        c.restore()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(BG)
        val w = width.toFloat()
        val h = height.toFloat()

        // ---- dişli: dönerek ve büyüyerek gelir, sonra yavaşça dönmeye devam eder
        val gp = seg(0f, 900f)
        if (gp > 0f) {
            val sc = 0.35f + 0.65f * outBack(gp)
            val rot = -220f * (1f - outCubic(seg(0f, 1500f))) + t * 0.018f
            val a = (255 * min(1f, gp * 2.2f)).toInt()
            rimPaint.alpha = a
            gearPaint.alpha = a
            drawGear(c, gearR * 0.035f * sc, gearR * 0.03f * sc, sc, rot, rimPaint) // mavi kenar
            drawGear(c, 0f, 0f, sc, rot, gearPaint)
        }

        // ---- "3"
        val tp = seg(450f, 600f)
        if (tp > 0f) {
            val s = outBack(tp)
            threePaint.alpha = (255 * min(1f, tp * 2f)).toInt()
            c.save()
            c.translate(gearCx, gearCy)
            c.scale(s, s)
            c.drawText("3", -threeBounds.exactCenterX(), -threeBounds.exactCenterY(), threePaint)
            c.restore()
        }

        // ---- kırmızı çizgi soldan sağa çekilir
        val bp = outCubic(seg(500f, 700f))
        if (bp > 0f) {
            c.drawRect(startX, barTopY, startX + textTotalW * bp, barTopY + 6f * dens, barPaint)
        }

        // ---- ÜÇLER MAKİNA harf harf yazılır
        var k = 0
        for (i in letters.indices) {
            if (letters[i] == ' ') continue
            val p = seg(800f + k * 75f, 420f)
            k++
            if (p <= 0f) continue
            letterPaint.alpha = (255 * min(1f, p * 1.8f)).toInt()
            val dy = (1f - outBack(p)) * 26f * dens
            c.drawText(letters[i].toString(), startX + letterX[i], baseY + dy, letterPaint)
        }

        // ---- Vietra Teknoloji
        val vp = seg(2000f, 500f)
        if (vp > 0f) {
            val a = (255 * vp).toInt()
            rolePaint.alpha = a
            makerPaint.alpha = a
            val dy = (1f - outCubic(vp)) * 12f * dens
            c.drawText(ROLE, w / 2f, h - 74f * dens + dy, rolePaint)
            c.drawText(MAKER, w / 2f, h - 44f * dens + dy, makerPaint)
        }
    }
}
