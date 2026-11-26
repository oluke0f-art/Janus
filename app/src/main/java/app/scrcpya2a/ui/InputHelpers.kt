package app.scrcpya2a.ui

import android.view.MotionEvent
import app.scrcpya2a.session.ControlChannel
import app.scrcpya2a.session.ControlMessages
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Detecta "tocar com 4 dedos, 3 vezes seguidas" (usado para sair da tela cheia).
 * Um toque válido: pelo menos 4 dedos ao mesmo tempo, todos soltos em até [maxTapMs].
 */
class FourFingerTapDetector(
    private val requiredTaps: Int = 3,
    private val windowMs: Long = 2000,
    private val maxTapMs: Long = 600,
) {
    private var gestureStart = 0L
    private var maxPointers = 0
    private var taps = 0
    private var firstTapTime = 0L

    /** @return true quando a sequência completa foi detectada. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStart = ev.eventTime
                maxPointers = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> maxPointers = maxOf(maxPointers, ev.pointerCount)
            MotionEvent.ACTION_UP -> {
                val quick = ev.eventTime - gestureStart <= maxTapMs
                val fourFingers = maxPointers >= 4
                maxPointers = 0
                if (fourFingers && quick) {
                    val now = ev.eventTime
                    if (taps == 0 || now - firstTapTime > windowMs) {
                        taps = 1
                        firstTapTime = now
                    } else {
                        taps++
                    }
                    if (taps >= requiredTaps) {
                        taps = 0
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> maxPointers = 0
        }
        return false
    }
}

/**
 * Modo touchpad: o dedo move um cursor de mouse no outro aparelho (como num notebook).
 *  - 1 dedo arrastando: move o cursor
 *  - toque: clique esquerdo; toque com 2 dedos: clique direito
 *  - 2 dedos arrastando: rolagem
 *  - segurar e arrastar: arrasta com o botão esquerdo pressionado
 */
class TouchpadController {
    private var curX = 0f
    private var curY = 0f
    private var sizeKey = 0L
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    private var moved = false
    private var scrolled = false
    private var scrollTravel = 0f
    private var dragging = false

    /** Chamado a cada movimento do cursor (coordenadas do vídeo), para a interface desenhar o cursor. */
    var onCursor: ((Float, Float) -> Unit)? = null

    /** Coloca o cursor no centro (ao ligar o touchpad). */
    fun centerFor(vw: Int, vh: Int) {
        sizeKey = (vw.toLong() shl 32) or vh.toLong()
        curX = vw / 2f
        curY = vh / 2f
        onCursor?.invoke(curX, curY)
    }

    fun onTouch(ev: MotionEvent, ctl: ControlChannel, vw: Int, vh: Int, viewW: Int) {
        val key = (vw.toLong() shl 32) or vh.toLong()
        if (key != sizeKey) {
            centerFor(vw, vh)
        }
        val scale = vw.toFloat() / viewW * SENSITIVITY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = ev.eventTime
                startX = ev.x
                startY = ev.y
                lastX = ev.x
                lastY = ev.y
                maxPointers = 1
                moved = false
                scrolled = false
                scrollTravel = 0f
                dragging = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                centroid(ev, -1).let { lastX = it.first; lastY = it.second }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                centroid(ev, ev.actionIndex).let { lastX = it.first; lastY = it.second }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount >= 2) {
                    val (cx, cy) = centroid(ev, -1)
                    val dx = cx - lastX
                    val dy = cy - lastY
                    lastX = cx
                    lastY = cy
                    scrollTravel += abs(dx) + abs(dy)
                    if (scrollTravel > SLOP_PX) scrolled = true
                    if (scrolled) {
                        ctl.sendScroll(
                            curX.toInt(), curY.toInt(), vw, vh,
                            hScroll = -dx / SCROLL_PX, vScroll = dy / SCROLL_PX,
                        )
                    }
                } else if (maxPointers == 1) {
                    val dx = ev.x - lastX
                    val dy = ev.y - lastY
                    lastX = ev.x
                    lastY = ev.y
                    if (!moved && !dragging) {
                        if (hypot(ev.x - startX, ev.y - startY) < SLOP_PX) return
                        if (ev.eventTime - downTime >= LONG_PRESS_MS) {
                            dragging = true
                            ctl.sendMouse(
                                ControlMessages.ACTION_DOWN, curX.toInt(), curY.toInt(), vw, vh,
                                ControlMessages.BUTTON_PRIMARY, ControlMessages.BUTTON_PRIMARY,
                            )
                        } else {
                            moved = true
                        }
                    }
                    curX = (curX + dx * scale).coerceIn(0f, vw - 1f)
                    curY = (curY + dy * scale).coerceIn(0f, vh - 1f)
                    onCursor?.invoke(curX, curY)
                    if (dragging) {
                        ctl.sendMouse(
                            ControlMessages.ACTION_MOVE, curX.toInt(), curY.toInt(), vw, vh,
                            0, ControlMessages.BUTTON_PRIMARY,
                        )
                    } else {
                        ctl.sendMouse(ControlMessages.ACTION_HOVER_MOVE, curX.toInt(), curY.toInt(), vw, vh, 0, 0)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    ctl.sendMouse(
                        ControlMessages.ACTION_UP, curX.toInt(), curY.toInt(), vw, vh,
                        ControlMessages.BUTTON_PRIMARY, 0,
                    )
                } else if (!moved && !scrolled) {
                    val button = if (maxPointers >= 2) ControlMessages.BUTTON_SECONDARY else ControlMessages.BUTTON_PRIMARY
                    ctl.sendMouse(ControlMessages.ACTION_DOWN, curX.toInt(), curY.toInt(), vw, vh, button, button)
                    ctl.sendMouse(ControlMessages.ACTION_UP, curX.toInt(), curY.toInt(), vw, vh, button, 0)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    ctl.sendMouse(
                        ControlMessages.ACTION_UP, curX.toInt(), curY.toInt(), vw, vh,
                        ControlMessages.BUTTON_PRIMARY, 0,
                    )
                }
                reset()
            }
        }
    }

    private fun reset() {
        maxPointers = 0
        moved = false
        scrolled = false
        dragging = false
        scrollTravel = 0f
    }

    private fun centroid(ev: MotionEvent, skip: Int): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            if (i == skip) continue
            sx += ev.getX(i)
            sy += ev.getY(i)
            n++
        }
        return if (n == 0) lastX to lastY else (sx / n) to (sy / n)
    }

    companion object {
        private const val SENSITIVITY = 1.6f
        private const val SLOP_PX = 12f
        private const val SCROLL_PX = 28f
        private const val LONG_PRESS_MS = 400L
    }
}
