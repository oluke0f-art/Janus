package app.scrcpya2a.session

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Repassa controles de jogo (gamepads) ligados ao RECEPTOR para o outro aparelho, criando
 * um gamepad HID virtual (UHID) lá, como faz o scrcpy. Descritor e layout do relatório
 * portados de app/src/hid/hid_gamepad.c do scrcpy 5.0.1 (GPL-3.0).
 *
 * Cada relatório HID carrega o ESTADO COMPLETO do controle. Por isso:
 *  - botões são enviados na hora (cada aperto/soltura vira um relatório imediato);
 *  - eixos (sticks, gatilhos, direcional) são limitados a ~125 relatórios por segundo, sempre com um
 *    envio final garantido com o último estado (nada fica "preso");
 *  - relatórios idênticos ao anterior não são reenviados.
 * Sem esse controle, um stick gerava centenas de relatórios por segundo e entupia o canal de
 * controle, atrasando ou perdendo entradas.
 *
 * Usado só pela thread principal (eventos de entrada da Activity).
 */
class GamepadForwarder {

    private class Slot(val hidId: Int) {
        var buttons = 0 // 16 bits transmitidos como estão
        var dpadKeys = 0 // bits locais (DPAD_*), vindos de teclas
        var dpadHat = 0 // bits locais (DPAD_*), vindos dos eixos HAT
        var lx = CENTER
        var ly = CENTER
        var rx = CENTER
        var ry = CENTER
        var ltAxis = 0
        var rtAxis = 0
        var ltKey = 0 // gatilho vindo de tecla (alguns controles enviam só tecla)
        var rtKey = 0
        var lastReport: ByteArray? = null
        var lastSentAt = 0L
        var flushPending = false
    }

    private val slots = HashMap<Int, Slot>() // id do InputDevice → slot
    private val handler = Handler(Looper.getMainLooper())

    fun reset() {
        handler.removeCallbacksAndMessages(null)
        slots.clear()
    }

    fun onKey(ev: KeyEvent, ctl: ControlChannel): Boolean {
        if (!isGamepad(ev.source)) return false
        val bit = buttonBit(ev.keyCode)
        val dpad = dpadBit(ev.keyCode)
        val trigger = ev.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || ev.keyCode == KeyEvent.KEYCODE_BUTTON_R2
        if (bit == 0 && dpad == 0 && !trigger) return false // tecla que não é do gamepad: deixa o sistema tratar
        val slot = slotFor(ev.deviceId, ev.device, ctl) ?: return true
        if (ev.action != KeyEvent.ACTION_DOWN && ev.action != KeyEvent.ACTION_UP) return true
        val down = ev.action == KeyEvent.ACTION_DOWN
        if (down && ev.repeatCount > 0) return true
        when {
            bit != 0 -> slot.buttons = if (down) slot.buttons or bit else slot.buttons and bit.inv()
            dpad != 0 -> slot.dpadKeys = if (down) slot.dpadKeys or dpad else slot.dpadKeys and dpad.inv()
            ev.keyCode == KeyEvent.KEYCODE_BUTTON_L2 -> slot.ltKey = if (down) TRIGGER_MAX else 0
            else -> slot.rtKey = if (down) TRIGGER_MAX else 0
        }
        submit(slot, ctl, immediate = true)
        return true
    }

    fun onMotion(ev: MotionEvent, ctl: ControlChannel): Boolean {
        if ((ev.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return false
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        val slot = slotFor(ev.deviceId, ev.device, ctl) ?: return true

        slot.lx = stick(ev.getAxisValue(MotionEvent.AXIS_X))
        slot.ly = stick(ev.getAxisValue(MotionEvent.AXIS_Y))
        slot.rx = stick(ev.getAxisValue(MotionEvent.AXIS_Z))
        slot.ry = stick(ev.getAxisValue(MotionEvent.AXIS_RZ))
        slot.ltAxis = trigger(max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)))
        slot.rtAxis = trigger(max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)))

        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var hat = 0
        if (hx < -0.5f) hat = hat or DPAD_LEFT else if (hx > 0.5f) hat = hat or DPAD_RIGHT
        if (hy < -0.5f) hat = hat or DPAD_UP else if (hy > 0.5f) hat = hat or DPAD_DOWN
        slot.dpadHat = hat

        submit(slot, ctl, immediate = false)
        return true
    }

    private fun slotFor(deviceId: Int, device: InputDevice?, ctl: ControlChannel): Slot? {
        slots[deviceId]?.let { return it }
        val used = slots.values.map { it.hidId }.toSet()
        val hidId = (FIRST_HID_ID until FIRST_HID_ID + MAX_GAMEPADS).firstOrNull { it !in used } ?: return null
        val slot = Slot(hidId)
        slots[deviceId] = slot
        ctl.send(
            ControlMessages.uhidCreate(
                hidId,
                device?.vendorId ?: 0,
                device?.productId ?: 0,
                device?.name ?: "Janus Gamepad",
                REPORT_DESCRIPTOR,
            ),
        )
        flush(slot, ctl) // estado neutro inicial
        return slot
    }

    /** Envia o estado agora ([immediate]) ou, no máximo, a cada [MIN_INTERVAL_MS] com envio final garantido. */
    private fun submit(slot: Slot, ctl: ControlChannel, immediate: Boolean) {
        val wait = MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - slot.lastSentAt)
        if (immediate || wait <= 0) {
            flush(slot, ctl)
        } else if (!slot.flushPending) {
            slot.flushPending = true
            handler.postDelayed({
                slot.flushPending = false
                flush(slot, ctl)
            }, wait)
        }
    }

    private fun flush(slot: Slot, ctl: ControlChannel) {
        val report = buildReport(slot)
        if (report.contentEquals(slot.lastReport)) return // nada mudou
        slot.lastReport = report
        slot.lastSentAt = SystemClock.uptimeMillis()
        ctl.send(ControlMessages.uhidInput(slot.hidId, report))
    }

    private fun buildReport(slot: Slot): ByteArray {
        val b = ByteArray(REPORT_SIZE)
        fun put16(offset: Int, v: Int) {
            b[offset] = (v and 0xFF).toByte()
            b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        put16(0, slot.lx)
        put16(2, slot.ly)
        put16(4, slot.rx)
        put16(6, slot.ry)
        put16(8, max(slot.ltAxis, slot.ltKey))
        put16(10, max(slot.rtAxis, slot.rtKey))
        put16(12, slot.buttons and 0xFFFF)
        b[14] = hatValue(slot.dpadKeys or slot.dpadHat).toByte()
        return b
    }

    private fun hatValue(d: Int): Int = when {
        d and DPAD_UP != 0 -> if (d and DPAD_LEFT != 0) 8 else if (d and DPAD_RIGHT != 0) 2 else 1
        d and DPAD_DOWN != 0 -> if (d and DPAD_LEFT != 0) 6 else if (d and DPAD_RIGHT != 0) 4 else 5
        d and DPAD_LEFT != 0 -> 7
        d and DPAD_RIGHT != 0 -> 3
        else -> 0
    }

    /** Zona morta com reescala, e passos de 1/127 para ignorar ruído do analógico. */
    private fun stick(v: Float): Int {
        val a = abs(v)
        if (a < DEAD_ZONE) return CENTER
        val scaled = ((a - DEAD_ZONE) / (1f - DEAD_ZONE)).coerceIn(0f, 1f)
        val quantized = (scaled * 127f).roundToInt() / 127f
        val signed = if (v < 0f) -quantized else quantized
        return (signed * 32767f).toInt() + CENTER // [-32767, 32767] → [1, 65535]
    }

    private fun trigger(v: Float): Int {
        if (v < TRIGGER_DEAD_ZONE) return 0
        val quantized = (v.coerceIn(0f, 1f) * 64f).roundToInt() / 64f
        return (quantized * TRIGGER_MAX).toInt()
    }

    private fun buttonBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0x0001 // sul
        KeyEvent.KEYCODE_BUTTON_B -> 0x0002 // leste
        KeyEvent.KEYCODE_BUTTON_X -> 0x0008 // oeste
        KeyEvent.KEYCODE_BUTTON_Y -> 0x0010 // norte
        KeyEvent.KEYCODE_BUTTON_SELECT -> 0x0400
        KeyEvent.KEYCODE_BUTTON_MODE -> 0x1000
        KeyEvent.KEYCODE_BUTTON_START -> 0x0800
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 0x2000
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 0x4000
        KeyEvent.KEYCODE_BUTTON_L1 -> 0x0040
        KeyEvent.KEYCODE_BUTTON_R1 -> 0x0080
        else -> 0
    }

    private fun dpadBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DPAD_RIGHT
        else -> 0
    }

    companion object {
        private const val FIRST_HID_ID = 3 // 1 e 2 são do teclado/mouse HID do scrcpy
        private const val MAX_GAMEPADS = 8
        private const val REPORT_SIZE = 15
        private const val CENTER = 0x8000
        private const val TRIGGER_MAX = 32767
        private const val DEAD_ZONE = 0.08f
        private const val TRIGGER_DEAD_ZONE = 0.03f
        private const val MIN_INTERVAL_MS = 8L // ~125 relatórios/s para eixos
        private const val DPAD_UP = 1
        private const val DPAD_DOWN = 2
        private const val DPAD_LEFT = 4
        private const val DPAD_RIGHT = 8

        fun isGamepad(source: Int): Boolean =
            (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        /** Descritor HID do gamepad (hid_gamepad.c do scrcpy). */
        private val REPORT_DESCRIPTOR = intArrayOf(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0xA1, 0x00,
            0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
            0x05, 0x01, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
            0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x95, 0x10, 0x75, 0x01, 0x81, 0x02,
            0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
            0xC0, 0xC0,
        ).let { ints -> ByteArray(ints.size) { ints[it].toByte() } }
    }
}
