package com.example.videdit

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Colour filters, adjustments and pan/zoom for one clip. Used by the preview and by export. */
@UnstableApi
fun clipEffects(c: Clip, export: Boolean = false): List<Effect> {
    val fx = mutableListOf<Effect>()
    var bright = c.bright + c.high * 0.15f
    var contrast = c.contrast
    var sat = c.sat * 100f + c.vib * 50f
    var red = 1f + c.temp * 0.2f
    var blue = 1f - c.temp * 0.2f
    when (c.filter) {
        1 -> fx.add(RgbFilter.createGrayscaleFilter())
        2 -> { sat += 35f; contrast += 0.2f }
        3 -> { red *= 1.12f; blue *= 0.88f }
        4 -> { red *= 0.88f; blue *= 1.12f }
        5 -> { contrast -= 0.3f; bright += 0.1f }
        6 -> fx.add(RgbFilter.createInvertedFilter())
    }
    if (bright != 0f) fx.add(Brightness(bright.coerceIn(-1f, 1f)))
    if (contrast != 0f) fx.add(Contrast(contrast.coerceIn(-1f, 1f)))
    if (sat != 0f) fx.add(HslAdjustment.Builder().adjustSaturation(sat.coerceIn(-100f, 100f)).build())
    if (red != 1f || blue != 1f) fx.add(RgbAdjustment.Builder().setRedScale(red.coerceIn(0f, 5f)).setBlueScale(blue.coerceIn(0f, 5f)).build())
    if (c.zoomA != 1f || c.zoomB != 1f) {
        val za = c.zoomA; val zb = c.zoomB
        val pax = c.panAx; val pay = c.panAy; val pbx = c.panBx; val pby = c.panBy
        val lenUs = (c.end - c.start) * 1000f / (if (export)
