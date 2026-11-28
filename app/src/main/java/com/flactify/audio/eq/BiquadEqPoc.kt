package com.flactify.audio.eq

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Isolated RBJ biquad EQ experiment. This class is intentionally not referenced by the playback
 * graph; it is a numerical PoC for future review, not a product EQ or a DSP engine.
 */
internal object BiquadEqPoc {
    enum class Type { PEAKING, LOW_SHELF, HIGH_SHELF }

    data class Coefficients(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double
    ) {
        /** Complex-frequency magnitude in dB, useful for offline response inspection. */
        fun magnitudeDb(sampleRateHz: Double, frequencyHz: Double): Double {
            require(sampleRateHz > 0.0 && frequencyHz in 0.0..(sampleRateHz / 2.0))
            val omega = 2.0 * Math.PI * frequencyHz / sampleRateHz
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val cos2Omega = cos(2.0 * omega)
            val sin2Omega = sin(2.0 * omega)
            val numeratorReal = b0 + b1 * cosOmega + b2 * cos2Omega
            val numeratorImaginary = -b1 * sinOmega - b2 * sin2Omega
            val denominatorReal = 1.0 + a1 * cosOmega + a2 * cos2Omega
            val denominatorImaginary = -a1 * sinOmega - a2 * sin2Omega
            val denominator = hypot(denominatorReal, denominatorImaginary)
            require(denominator > 0.0) { "Biquad denominator is singular" }
            return 20.0 * log10(
                hypot(numeratorReal, numeratorImaginary) / denominator
            )
        }

        /** Returns the two denominator poles; both must remain inside the unit circle. */
        fun poles(): List<Complex> {
            val discriminant = a1 * a1 - 4.0 * a2
            return if (discriminant >= 0.0) {
                val sqrtDiscriminant = sqrt(discriminant)
                listOf(
                    Complex((-a1 + sqrtDiscriminant) / 2.0, 0.0),
                    Complex((-a1 - sqrtDiscriminant) / 2.0, 0.0)
                )
            } else {
                val imaginary = sqrt(-discriminant) / 2.0
                listOf(
                    Complex(-a1 / 2.0, imaginary),
                    Complex(-a1 / 2.0, -imaginary)
                )
            }
        }
    }

    data class Complex(val real: Double, val imaginary: Double) {
        val magnitude: Double get() = hypot(real, imaginary)
    }

    /** Designs one normalized, direct-form-II-transposed biquad using the RBJ cookbook formulas. */
    fun design(
        type: Type,
        sampleRateHz: Double,
        frequencyHz: Double,
        gainDb: Double,
        q: Double = 1.0,
        shelfSlope: Double = 1.0
    ): Coefficients {
        require(sampleRateHz.isFinite() && sampleRateHz > 0.0)
        require(frequencyHz.isFinite() && frequencyHz > 0.0 && frequencyHz < sampleRateHz / 2.0)
        require(gainDb.isFinite())
        require(q.isFinite() && q > 0.0)
        require(shelfSlope.isFinite() && shelfSlope > 0.0)

        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * Math.PI * frequencyHz / sampleRateHz
        val cosine = cos(omega)
        val sine = sin(omega)
        val alpha = when (type) {
            Type.PEAKING -> sine / (2.0 * q)
            Type.LOW_SHELF, Type.HIGH_SHELF -> sine / 2.0 * sqrt(
                (a + 1.0 / a) * (1.0 / shelfSlope - 1.0) + 2.0
            )
        }
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val raw = when (type) {
            Type.PEAKING -> Raw(
                b0 = 1.0 + alpha * a,
                b1 = -2.0 * cosine,
                b2 = 1.0 - alpha * a,
                a0 = 1.0 + alpha / a,
                a1 = -2.0 * cosine,
                a2 = 1.0 - alpha / a
            )
            Type.LOW_SHELF -> Raw(
                b0 = a * ((a + 1.0) - (a - 1.0) * cosine + twoSqrtAAlpha),
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosine),
                b2 = a * ((a + 1.0) - (a - 1.0) * cosine - twoSqrtAAlpha),
                a0 = (a + 1.0) + (a - 1.0) * cosine + twoSqrtAAlpha,
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosine),
                a2 = (a + 1.0) + (a - 1.0) * cosine - twoSqrtAAlpha
            )
            Type.HIGH_SHELF -> Raw(
                b0 = a * ((a + 1.0) + (a - 1.0) * cosine + twoSqrtAAlpha),
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosine),
                b2 = a * ((a + 1.0) + (a - 1.0) * cosine - twoSqrtAAlpha),
                a0 = (a + 1.0) - (a - 1.0) * cosine + twoSqrtAAlpha,
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosine),
                a2 = (a + 1.0) - (a - 1.0) * cosine - twoSqrtAAlpha
            )
        }
        require(raw.a0.isFinite() && raw.a0 > 0.0) { "Invalid biquad normalization" }
        return Coefficients(
            b0 = raw.b0 / raw.a0,
            b1 = raw.b1 / raw.a0,
            b2 = raw.b2 / raw.a0,
            a1 = raw.a1 / raw.a0,
            a2 = raw.a2 / raw.a0
        )
    }

    /** Processes one stereo frame in a numerically stable transposed direct-form-II state. */
    class State(private val coefficients: Coefficients) {
        private var leftZ1 = 0.0
        private var leftZ2 = 0.0
        private var rightZ1 = 0.0
        private var rightZ2 = 0.0

        fun process(left: Double, right: Double): Pair<Double, Double> =
            processChannel(left, true) to processChannel(right, false)

        fun reset() {
            leftZ1 = 0.0
            leftZ2 = 0.0
            rightZ1 = 0.0
            rightZ2 = 0.0
        }

        private fun processChannel(input: Double, isLeft: Boolean): Double {
            require(input.isFinite())
            val output = coefficients.b0 * input + if (isLeft) leftZ1 else rightZ1
            val z1 = coefficients.b1 * input - coefficients.a1 * output +
                if (isLeft) leftZ2 else rightZ2
            val z2 = coefficients.b2 * input - coefficients.a2 * output
            if (isLeft) {
                leftZ1 = z1
                leftZ2 = z2
            } else {
                rightZ1 = z1
                rightZ2 = z2
            }
            return output
        }
    }

    private data class Raw(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a0: Double,
        val a1: Double,
        val a2: Double
    )
}
