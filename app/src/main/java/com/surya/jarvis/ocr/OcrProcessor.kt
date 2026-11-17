package com.surya.jarvis.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.Text.TextBlock
import com.google.mlkit.vision.text.Text.Line
import com.google.mlkit.vision.text.Text.Element
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await

class OcrProcessor(private val context: Context) {
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognizeText(bitmap: Bitmap): OcrResult = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val mlResult: Text = recognizer.process(image).await()

        val blocks = mutableListOf<OcrBlock>()
        for (mlBlock in mlResult.textBlocks) {
            val lines = mutableListOf<OcrLine>()
            for (mlLine in mlBlock.lines) {
                val elements = mutableListOf<OcrElement>()
                for (mlElement in mlLine.elements) {
                    val elementPoints: List<Point> = (mlElement.cornerPoints as? List<Point>) ?: emptyList()
                    elements.add(OcrElement(
                        text = mlElement.text,
                        boundingBox = mlElement.boundingBox ?: Rect(0, 0, 0, 0),
                        cornerPoints = elementPoints.toTypedArray(),
                        confidence = mlElement.confidence
                    ))
                }
                val linePoints: List<Point> = (mlLine.cornerPoints as? List<Point>) ?: emptyList()
                lines.add(OcrLine(
                    text = mlLine.text,
                    boundingBox = mlLine.boundingBox ?: Rect(0, 0, 0, 0),
                    cornerPoints = linePoints.toTypedArray(),
                    confidence = mlLine.confidence,
                    elements = elements
                ))
            }
            val blockPoints: List<Point> = (mlBlock.cornerPoints as? List<Point>) ?: emptyList()
            blocks.add(OcrBlock(
                text = mlBlock.text,
                boundingBox = mlBlock.boundingBox ?: Rect(0, 0, 0, 0),
                cornerPoints = blockPoints.toTypedArray(),
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
    val boundingBox: Rect,
    val cornerPoints: Array<Point>,
    val lines: List<OcrLine>
)

data class OcrLine(
    val text: String,
    val boundingBox: Rect,
    val cornerPoints: Array<Point>,
    val confidence: Float,
    val elements: List<OcrElement>
)

data class OcrElement(
    val text: String,
    val boundingBox: Rect,
    val cornerPoints: Array<Point>,
    val confidence: Float
)