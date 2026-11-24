package com.sdmu.guanlitong.data.jwxt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * 验证码识别：灰度化 → Otsu 二值化 → 连通域剔除干扰 → 放大后交给 ML Kit。
 *
 * 教务验证码固定 6 位数字/字母，图上会混入噪点、细斜线和色块干扰。
 * 直接丢给文字识别成功率很低，所以先在像素层面把干扰元素去掉：
 * 1. 按亮度转灰度，用 Otsu 自动求阈值二值化（不依赖固定阈值）；
 * 2. 连通域分析，去掉面积过小的噪点、横跨大半张图的细线/边框；
 * 3. 裁到有效字符范围，放大并补白边，再识别。
 *
 * 识别结果限制为 6 位 ASCII 数字/字母，只作为输入框默认值，用户可随时修改。
 */
object CaptchaOcr {

    private const val CAPTCHA_LENGTH = 6

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bytes: ByteArray): String = withContext(Dispatchers.Default) {
        val bitmap = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: return@withContext ""

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return@withContext ""

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. 灰度 + Otsu 阈值
        val gray = IntArray(pixels.size) { index ->
            val color = pixels[index]
            (Color.red(color) * 0.299f + Color.green(color) * 0.587f + Color.blue(color) * 0.114f)
                .toInt()
                .coerceIn(0, 255)
        }
        val threshold = otsuThreshold(gray)

        // 2. 二值化并剔除噪点 / 干扰线
        val ink = BooleanArray(gray.size) { gray[it] < threshold }
        val cleaned = removeInterference(ink, width, height)
        val bounds = contentBounds(cleaned, width, height)

        val variants = buildList {
            // 主方案：清理后的二值图
            renderBinary(cleaned, width, height, bounds)?.let(::add)
            // 兜底：保留灰度层次的原图（ML Kit 对带抗锯齿的笔画更敏感）
            renderGray(gray, width, height, threshold, bounds)?.let(::add)
        }

        var best = ""
        for (variant in variants) {
            val normalized = normalize(recognizeBitmap(variant))
            if (normalized.length == CAPTCHA_LENGTH) return@withContext normalized
            if (normalized.length > best.length) best = normalized
        }
        best
    }

    // ── 识别 ────────────────────────────────────────────────

    private suspend fun recognizeBitmap(bitmap: Bitmap): String {
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }
        return text.text
    }

    /** 只保留 6 位 ASCII 数字/字母；多出来的通常是两端的干扰字符。 */
    private fun normalize(value: String): String {
        val cleaned = buildString {
            value.forEach { char ->
                if (char.code < 128 && char.isLetterOrDigit()) append(char.uppercaseChar())
            }
        }
        if (cleaned.length <= CAPTCHA_LENGTH) return cleaned
        // 7 位时干扰字符一般在最前面；更长就只取前 6 位。
        return if (cleaned.length == CAPTCHA_LENGTH + 1) {
            cleaned.drop(1)
        } else {
            cleaned.take(CAPTCHA_LENGTH)
        }
    }

    // ── 图像处理 ─────────────────────────────────────────────

    /** Otsu 最大类间方差阈值。 */
    private fun otsuThreshold(gray: IntArray): Int {
        val histogram = IntArray(256)
        gray.forEach { histogram[it]++ }
        val total = gray.size
        var sum = 0L
        for (value in 0..255) sum += value.toLong() * histogram[value]

        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var bestThreshold = 128
        for (value in 0..255) {
            weightBackground += histogram[value]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break
            sumBackground += value.toLong() * histogram[value]
            val meanBackground = sumBackground.toDouble() / weightBackground
            val meanForeground = (sum - sumBackground).toDouble() / weightForeground
            val delta = meanBackground - meanForeground
            val variance = weightBackground.toDouble() * weightForeground * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = value
            }
        }
        // 极小的验证码图噪声很强，阈值过低会把背景纹理也算成笔画。
        return bestThreshold.coerceIn(60, 200)
    }

    /**
     * 连通域过滤：
     * - 面积过小 → 噪点；
     * - 细长且横跨大半张图 / 贴边通栏 → 干扰线或边框；
     * - 其余视为字符笔画保留。
     */
    private fun removeInterference(ink: BooleanArray, width: Int, height: Int): BooleanArray {
        val visited = BooleanArray(ink.size)
        val kept = BooleanArray(ink.size)
        val queue = IntArray(ink.size)
        val minArea = max(6, width * height / 700)
        val minWidthSpan = (width * 0.55f).toInt()
        val minHeightSpan = (height * 0.55f).toInt()

        for (start in ink.indices) {
            if (!ink[start] || visited[start]) continue

            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var area = 0
            var left = width
            var right = 0
            var top = height
            var bottom = 0
            val members = mutableListOf<Int>()

            while (head < tail) {
                val index = queue[head++]
                members += index
                area++
                val x = index % width
                val y = index / width
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                // 4 邻域足够把笔画连起来，斜笔画也能通过相邻点串起。
                if (x > 0) enqueue(index - 1, ink, visited, queue, tail).let { if (it) tail++ }
                if (x + 1 < width) enqueue(index + 1, ink, visited, queue, tail).let { if (it) tail++ }
                if (y > 0) enqueue(index - width, ink, visited, queue, tail).let { if (it) tail++ }
                if (y + 1 < height) enqueue(index + width, ink, visited, queue, tail).let { if (it) tail++ }
            }

            val spanX = right - left + 1
            val spanY = bottom - top + 1
            val density = area.toFloat() / (spanX * spanY).toFloat()
            val isNoise = when {
                area < minArea -> true
                spanX >= minWidthSpan && spanY <= height * 0.45f -> true // 横穿的干扰线
                spanY >= minHeightSpan && spanX <= width * 0.3f -> true   // 竖穿的干扰线
                spanX >= width * 0.9f || spanY >= height * 0.9f -> true   // 贴边框
                density < 0.08f && (spanX > width * 0.35f || spanY > height * 0.35f) -> true
                else -> false
            }
            if (!isNoise) {
                members.forEach { kept[it] = true }
            }
        }
        return kept
    }

    /** 若该点属于未访问的墨迹，标记并写入队列；返回是否入队。 */
    private fun enqueue(
        index: Int,
        ink: BooleanArray,
        visited: BooleanArray,
        queue: IntArray,
        tail: Int,
    ): Boolean {
        if (!ink[index] || visited[index]) return false
        visited[index] = true
        queue[tail] = index
        return true
    }

    private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 保留内容的包围盒，向外留一点边。 */
    private fun contentBounds(ink: BooleanArray, width: Int, height: Int): Bounds {
        var left = width
        var right = 0
        var top = height
        var bottom = 0
        var found = false
        for (index in ink.indices) {
            if (!ink[index]) continue
            found = true
            val x = index % width
            val y = index / width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (!found) return Bounds(0, 0, width - 1, height - 1)
        val marginX = max(2, width / 40)
        val marginY = max(2, height / 20)
        return Bounds(
            left = (left - marginX).coerceAtLeast(0),
            top = (top - marginY).coerceAtLeast(0),
            right = (right + marginX).coerceAtMost(width - 1),
            bottom = (bottom + marginY).coerceAtMost(height - 1),
        )
    }

    /** 把清理后的二值图裁到字符范围并放大，四周留白。 */
    private fun renderBinary(
        ink: BooleanArray,
        width: Int,
        height: Int,
        bounds: Bounds,
    ): Bitmap? {
        val cropWidth = bounds.right - bounds.left + 1
        val cropHeight = bounds.bottom - bounds.top + 1
        if (cropWidth <= 0 || cropHeight <= 0) return null

        val crop = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        val cropPixels = IntArray(cropWidth * cropHeight) { Color.WHITE }
        for (y in 0 until cropHeight) {
            val sourceY = bounds.top + y
            for (x in 0 until cropWidth) {
                val sourceX = bounds.left + x
                if (ink[sourceY * width + sourceX]) {
                    cropPixels[y * cropWidth + x] = Color.BLACK
                }
            }
        }
        crop.setPixels(cropPixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
        return scaleWithPadding(crop)
    }

    /** 灰度版本：做一次对比度拉伸，让笔画和背景分得更开。 */
    private fun renderGray(
        gray: IntArray,
        width: Int,
        height: Int,
        threshold: Int,
        bounds: Bounds,
    ): Bitmap? {
        val cropWidth = bounds.right - bounds.left + 1
        val cropHeight = bounds.bottom - bounds.top + 1
        if (cropWidth <= 0 || cropHeight <= 0) return null

        val pixels = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            val sourceY = bounds.top + y
            for (x in 0 until cropWidth) {
                val value = gray[sourceY * width + bounds.left + x]
                // 阈值附近做线性拉伸：比阈值暗的压到接近全黑，亮的推白。
                val stretched = when {
                    value < threshold -> (value * 140 / threshold.coerceAtLeast(1))
                    else -> 210 + (value - threshold) * 45 / (255 - threshold).coerceAtLeast(1)
                }.coerceIn(0, 255)
                pixels[y * cropWidth + x] = Color.rgb(stretched, stretched, stretched)
            }
        }
        val crop = Bitmap.createBitmap(cropWidth, cropHeight, Bitmap.Config.ARGB_8888)
        crop.setPixels(pixels, 0, cropWidth, 0, 0, cropWidth, cropHeight)
        return scaleWithPadding(crop)
    }

    /**
     * 验证码原图很小（约 100×40），直接识别容易漏字。
     * 放大到高度 120 左右（3～4 倍）并加一圈白边，小字号和贴边字符会更稳。
     */
    private fun scaleWithPadding(source: Bitmap): Bitmap {
        val scale = max(3, 120 / source.height.coerceAtLeast(1))
        val scaled = Bitmap.createScaledBitmap(
            source,
            source.width * scale,
            source.height * scale,
            true,
        )
        val padding = max(12, scaled.width / 10)
        val padded = Bitmap.createBitmap(
            scaled.width + padding * 2,
            scaled.height + padding * 2,
            Bitmap.Config.ARGB_8888,
        )
        Canvas(padded).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, padding.toFloat(), padding.toFloat(), null)
        }
        return padded
    }
}