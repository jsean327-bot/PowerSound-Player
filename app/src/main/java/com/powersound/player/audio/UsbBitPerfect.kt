package com.powersound.player.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.media3.common.C
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * True bit-perfect output to a USB DAC (Android 14 and later): Android normally mixes every app at one fixed rate
 * (usually 48 kHz, 16 or 24-bit) and resamples the rest. With Perfect Bit Perfect on and a USB DAC plugged in,
 * PowerSound asks Android for the DAC alone, at the song's own rate and bit depth ([AudioManager.setPreferredMixerAttributes]),
 * and writes the samples to it untouched ([DirectUsbSink]). The DAC then shows the song's rate (44.1 / 96 / 192 kHz…).
 *
 * While it plays, other apps' sounds (notifications…) are muted by Android, and the volume is the DAC's own.
 */
object UsbBitPerfect {

    /** How the sound goes to the DAC: Android's encoding, Media3's, and the bits per sample written. */
    data class Plan(
        val deviceId: Int,
        val rate: Int,
        val channels: Int,
        /** AudioFormat.ENCODING_PCM_16BIT / _24BIT_PACKED / _32BIT / _FLOAT */
        val encoding: Int,
        /** C.ENCODING_PCM_16BIT / _24BIT / _32BIT / _FLOAT, for the sink's format */
        val media3Encoding: Int,
        /** 16, 24, 32, or 0 for 32-bit float */
        val bits: Int,
        internal val mixer: Any?
    ) {
        val label: String
            get() = (if (bits == 0) "32-bit float" else "$bits-bit") + " / " +
                (if (rate % 1000 == 0) "${rate / 1000} kHz" else "%.1f kHz".format(rate / 1000.0))
    }

    /** What the USB DAC is getting now (Settings → Audio → Bit Perfect, Audio Info); null when not in use. */
    val status = MutableStateFlow<String?>(null)

    @Volatile
    var available = Build.VERSION.SDK_INT >= 34
        private set

    private var app: Context? = null
    private var appliedDevice: AudioDeviceInfo? = null
    private var appliedMixer: Any? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun audio(): AudioManager? = app?.getSystemService(AudioManager::class.java)

    /** The plugged-in USB DAC (null: none). */
    fun device(): AudioDeviceInfo? {
        val am = audio() ?: return null
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    fun device(id: Int): AudioDeviceInfo? =
        audio()?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.id == id }

    /** The players' audio attributes (the mixer is chosen for these). */
    val attributes: android.media.AudioAttributes = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /**
     * How to send [rate] Hz, [channels] channels to the USB DAC untouched, or null (no DAC, Android 13 or older,
     * or the DAC doesn't take this rate: the normal path is used and [status] says why).
     */
    fun plan(rate: Int, channels: Int): Plan? {
        if (Build.VERSION.SDK_INT < 34) return null
        val dev = device() ?: run { status.value = null; return null }
        return runCatching { plan34(dev, rate, channels) }.getOrElse { e ->
            status.value = "USB DAC: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    @SuppressLint("NewApi")
    private fun plan34(dev: AudioDeviceInfo, rate: Int, channels: Int): Plan? {
        val am = audio() ?: return null
        val all = am.getSupportedMixerAttributes(dev).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        val fits = all.filter { it.format.sampleRate == rate && it.format.channelCount == channels }
        // the most bits the DAC takes: a 16-bit song in 24 bits is still the same samples (zeros below)
        val order = listOf(AudioFormat.ENCODING_PCM_24BIT_PACKED, AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        val pick = order.firstNotNullOfOrNull { e -> fits.firstOrNull { it.format.encoding == e } }
        if (pick == null) {
            val rates = all.map { it.format.sampleRate }.filter { it > 0 }.distinct().sorted()
            status.value = if (all.isEmpty()) "USB DAC: Android offers no bit-perfect output for it"
                else "USB DAC doesn't take ${khz(rate)} (it takes ${rates.joinToString(", ") { khz(it) }}): normal output"
            return null
        }
        val (m3, bits) = when (pick.format.encoding) {
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> C.ENCODING_PCM_24BIT to 24
            AudioFormat.ENCODING_PCM_32BIT -> C.ENCODING_PCM_32BIT to 32
            AudioFormat.ENCODING_PCM_FLOAT -> C.ENCODING_PCM_FLOAT to 0
            else -> C.ENCODING_PCM_16BIT to 16
        }
        return Plan(dev.id, rate, channels, pick.format.encoding, m3, bits, pick)
    }

    /** Asks Android for the DAC alone with [plan]'s format. False when Android refused (then it mixes as usual). */
    @SuppressLint("NewApi")
    @Synchronized
    fun apply(plan: Plan): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val am = audio() ?: return false
        val dev = device(plan.deviceId) ?: return false
        val mixer = plan.mixer as? AudioMixerAttributes ?: return false
        if (appliedDevice?.id == dev.id && appliedMixer == mixer) return true
        val ok = runCatching { am.setPreferredMixerAttributes(attributes, dev, mixer) }.getOrDefault(false)
        if (ok) {
            appliedDevice = dev
            appliedMixer = mixer
            status.value = "USB DAC bit-perfect: ${plan.label}"
        } else {
            status.value = "USB DAC: Android refused bit-perfect output (${plan.label}), it mixes the sound as usual"
        }
        return ok
    }

    /** Gives the DAC back to Android's mixer (other apps' sounds come back). */
    @SuppressLint("NewApi")
    @Synchronized
    fun clear() {
        val dev = appliedDevice ?: run { status.value = null; return }
        appliedDevice = null
        appliedMixer = null
        status.value = null
        if (Build.VERSION.SDK_INT < 34) return
        runCatching { audio()?.clearPreferredMixerAttributes(attributes, dev) }
    }

    private fun khz(r: Int) = if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(r / 1000.0)
}
