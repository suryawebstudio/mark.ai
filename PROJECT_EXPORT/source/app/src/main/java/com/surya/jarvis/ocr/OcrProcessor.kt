package com.surya.jarvis.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OcrProcessor(private val context: Context) {
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val mlResult = recognizer.process(image).await()

        val blocks = mutableListOf<OcrBlock>()
        for (mlBlock in mlResult.textBlocks) {
            val lines = mutableListOf<OcrLine>()
            for (mlLine in mlBlock.lines) {
                val elements = mutableListOf<OcrElement>()
                for (mlElement in mlLine.elements) {
                    elements.add(OcrElement(
                        text = mlElement.text,
                        boundingBox = mlElement.boundingBox,
                        cornerPoints = mlElement.cornerPoints,
                        confidence = mlElement.confidence
                    ))
                }
                lines.add(OcrLine(
                    text = mlLine.text,
                    boundingBox = mlLine.boundingBox,
                    cornerPoints = mlLine.cornerPoints,
                    confidence = mlLine.confidence,
                    elements = elements
                ))
            }
            blocks.add(OcrBlock(
                text = mlBlock.text,
                boundingBox = mlBlock.boundingBox,
                cornerPoints = mlBlock.cornerPoints,
                lines = lines
            ))
        }
        OcrResult(blocks = blocks, fullText = mlResult.text)
    }

    fun close() {
        recognizer.close()
    }
}

data class OcrResult(
    val blocks: List<OcrBlock>,
    val fullText: String
)

data class OcrBlock(
    val text: String,
    val boundingBox: android.graphics.Rect,
    val cornerPoints: Array<android.graphics.Point>,
    val lines: List<OcrLine>
)

data class OcrLine(
    val text: String,
    val boundingBox: android.graphics.Rect,
    val cornerPoints: Array<android.graphics.Point>,
    val confidence: Float,
    val elements: List<OcrElement>
)

data class OcrElement(
    val text: String,
    val boundingBox: android.graphics.Rect,
    val cornerPoints: Array<android.graphics.Point>,
    val confidence: Float
)