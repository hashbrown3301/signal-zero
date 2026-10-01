package com.itantra.translation

import java.io.ByteArrayOutputStream

/**
 * Builds two small ONNX wrappers around the installed, unmodified SentencePiece model.
 * ONNX Runtime Extensions performs the actual normalization/tokenization in native code.
 * The fairseq option implements NLLB's vocabulary offset; no approximate Kotlin tokenizer.
 * Only the protobuf fields needed by these two fixed graphs are emitted here.
 */
internal object SentencePieceModels {
    fun encoder(model: ByteArray): ByteArray = graph(
        "nllb_encode", "SentencepieceTokenizer", model,
        inputs = listOf("text", "nbest_size", "alpha", "add_bos", "add_eos", "reverse", "fairseq"),
        outputs = listOf("tokens", "indices"),
        inputTypes = listOf(value("text", 8, 1)),
        outputTypes = listOf(value("tokens", 6, null), value("indices", 7, null)),
        constants = listOf(
            constant("nbest_size", 7, ByteArray(8)),
            constant("alpha", 1, ByteArray(4)),
            constant("add_bos", 9, byteArrayOf(0)),
            constant("add_eos", 9, byteArrayOf(0)),
            constant("reverse", 9, byteArrayOf(0)),
            constant("fairseq", 9, byteArrayOf(1)),
        ),
    )

    fun decoder(model: ByteArray): ByteArray = graph(
        "nllb_decode", "SentencepieceDecoder", model,
        inputs = listOf("ids", "fairseq"), outputs = listOf("text"),
        inputTypes = listOf(value("ids", 7, null)), outputTypes = listOf(value("text", 8, 1)),
        constants = listOf(constant("fairseq", 9, byteArrayOf(1))),
    )

    private fun graph(
        name: String, operation: String, model: ByteArray,
        inputs: List<String>, outputs: List<String>, inputTypes: List<ByteArray>,
        outputTypes: List<ByteArray>, constants: List<ByteArray>,
    ): ByteArray {
        require(model.isNotEmpty()) { "SentencePiece model is empty" }
        val node = proto {
            inputs.forEach { string(1, it) }
            outputs.forEach { string(2, it) }
            string(4, operation)
            bytes(5, proto { string(1, "model"); bytes(4, model); number(20, 3) })
            string(7, "ai.onnx.contrib")
        }
        val graph = proto {
            bytes(1, node); string(2, name)
            constants.forEach { bytes(5, it) }
            inputTypes.forEach { bytes(11, it) }
            outputTypes.forEach { bytes(12, it) }
        }
        return proto {
            number(1, 9) // ModelProto.ir_version
            bytes(7, graph)
            bytes(8, proto { string(1, ""); number(2, 17) })
            bytes(8, proto { string(1, "ai.onnx.contrib"); number(2, 1) })
        }
    }

    private fun value(name: String, type: Int, dimension: Int?): ByteArray = proto {
        string(1, name)
        bytes(2, proto {
            bytes(1, proto {
                number(1, type)
                bytes(2, proto { bytes(1, proto { dimension?.let { number(1, it) } }) })
            })
        })
    }

    private fun constant(name: String, type: Int, raw: ByteArray): ByteArray = proto {
        number(1, 1); number(2, type); string(8, name); bytes(9, raw)
    }

    private inline fun proto(write: Wire.() -> Unit): ByteArray = Wire().apply(write).result()

    private class Wire {
        private val out = ByteArrayOutputStream()
        fun number(field: Int, value: Int) { varint(field shl 3); varint(value) }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun bytes(field: Int, value: ByteArray) {
            varint((field shl 3) or 2); varint(value.size); out.write(value)
        }
        private fun varint(value: Int) {
            var remaining = value
            while (remaining > 127) { out.write((remaining and 127) or 128); remaining = remaining ushr 7 }
            out.write(remaining)
        }
        fun result(): ByteArray = out.toByteArray()
    }
}
