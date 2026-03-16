package com.example.myapplication

import kotlin.math.cos
import kotlin.math.sin

class FftAnalyzer(private val size: Int = 1024) {

    private val real = DoubleArray(size)
    private val imag = DoubleArray(size)
    private val window = DoubleArray(size)

    init {
        for (i in 0 until size) {
            window[i] = 0.5 - 0.5 * cos(2.0 * Math.PI * i / (size - 1))
        }
    }

    fun analyze(samples: ShortArray, length: Int): FloatArray {
        val copySize = minOf(length, size)
        for (i in 0 until size) {
            if (i < copySize) {
                real[i] = (samples[i] / 32768.0) * window[i]
            } else {
                real[i] = 0.0
            }
            imag[i] = 0.0
        }

        fft(real, imag)

        val half = size / 2
        val magnitudes = FloatArray(half)
        for (i in 0 until half) {
            val re = real[i]
            val im = imag[i]
            magnitudes[i] = kotlin.math.sqrt(re * re + im * im).toFloat()
        }
        return magnitudes
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val angle = -2.0 * Math.PI / len
            val wLenCos = cos(angle)
            val wLenSin = sin(angle)
            var i = 0
            while (i < n) {
                var wCos = 1.0
                var wSin = 0.0
                for (k in 0 until len / 2) {
                    val uReal = real[i + k]
                    val uImag = imag[i + k]
                    val vReal = real[i + k + len / 2] * wCos - imag[i + k + len / 2] * wSin
                    val vImag = real[i + k + len / 2] * wSin + imag[i + k + len / 2] * wCos

                    real[i + k] = uReal + vReal
                    imag[i + k] = uImag + vImag
                    real[i + k + len / 2] = uReal - vReal
                    imag[i + k + len / 2] = uImag - vImag

                    val nextCos = wCos * wLenCos - wSin * wLenSin
                    val nextSin = wCos * wLenSin + wSin * wLenCos
                    wCos = nextCos
                    wSin = nextSin
                }
                i += len
            }
            len = len shl 1
        }
    }
}

