package com.skynav.data.photo

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Feature vector of one image from the bundled MobileNet v3 small embedder.
 * MediaPipe normalises float inputs to the range -1..1. The interpreter is not thread-safe.
 */
class FrameEmbedder private constructor(private val interpreter: Interpreter) : AutoCloseable {

    private val inputShape = interpreter.getInputTensor(0).shape()
    private val inputType = interpreter.getInputTensor(0).dataType()
    private val width = inputShape[2]
    private val height = inputShape[1]
    private val output = Array(1) { FloatArray(interpreter.getOutputTensor(0).numElements()) }

    fun embed(bitmap: Bitmap): FloatArray {
        val scaled = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
        val input = ByteBuffer.allocateDirect(inputBytes()).order(ByteOrder.nativeOrder())
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        when (inputType) {
            DataType.FLOAT32 -> pixels.forEach { pixel ->
                input.putFloat(channel(pixel shr 16) )
                input.putFloat(channel(pixel shr 8))
                input.putFloat(channel(pixel))
            }
            DataType.UINT8 -> pixels.forEach { pixel ->
                input.put((pixel shr 16).toByte())
                input.put((pixel shr 8).toByte())
                input.put(pixel.toByte())
            }
            else -> error("unsupported embedder input $inputType")
        }
        input.rewind()
        interpreter.run(input, output)
        return normalize(output[0].copyOf())
    }

    override fun close() {
        interpreter.close()
    }

    private fun inputBytes(): Int {
        val channels = inputShape.getOrElse(3) { 3 }
        val perValue = if (inputType == DataType.FLOAT32) 4 else 1
        return height * width * channels * perValue
    }

    private fun channel(value: Int): Float = (value and 0xFF) / 127.5f - 1f

    companion object {
        const val MODEL_ASSET = "mobilenet_v3_small.tflite"

        fun load(context: Context): FrameEmbedder {
            val file = context.assets.openFd(MODEL_ASSET)
            val bytes = file.use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            return FrameEmbedder(Interpreter(bytes, Interpreter.Options().apply { numThreads = 2 }))
        }

        private fun normalize(vector: FloatArray): FloatArray {
            var sum = 0.0
            for (value in vector) sum += value.toDouble() * value
            val norm = sqrt(sum)
            if (norm == 0.0) return vector
            for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
            return vector
        }
    }
}
