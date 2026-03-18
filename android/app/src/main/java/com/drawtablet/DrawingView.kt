package com.drawtablet

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.graphics.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.ArrayDeque

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PENCIL, BRUSH, ERASER, FILL }

    private var currentTool = Tool.PENCIL
    private var currentColor = Color.BLACK
    private var strokeSize = 8f
    private var currentPath = Path()
    private val paths = mutableListOf<DrawPath>()

    private var connectionManager: ConnectionManager? = null

    private var lastX = 0f
    private var lastY = 0f
    private var canvasWidth = 0
    private var canvasHeight = 0

    // Bitmap for drawing and fill operations
    private lateinit var canvasBitmap: Bitmap
    private lateinit var bitmapCanvas: Canvas

    // Pre-allocated paint and helper objects to avoid allocations in onDraw
    private val drawPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    data class DrawPath(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val tool: Tool
    )

    private fun updatePaint(color: Int, strokeWidth: Float, tool: Tool) {
        drawPaint.color = if (tool == Tool.ERASER) Color.WHITE else color
        drawPaint.strokeWidth = strokeWidth
        if (tool == Tool.BRUSH) {
            drawPaint.maskFilter = BlurMaskFilter(strokeWidth * 0.4f, BlurMaskFilter.Blur.NORMAL)
            drawPaint.alpha = 180
        } else {
            drawPaint.maskFilter = null
            drawPaint.alpha = 255
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        
        canvasWidth = w
        canvasHeight = h
        
        // Create or recreate bitmap on size change
        val newBitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.WHITE)
        
        // If we already had a bitmap, we could draw it onto the new one here
        if (::canvasBitmap.isInitialized) {
            newCanvas.drawBitmap(canvasBitmap, 0f, 0f, null)
        }
        
        canvasBitmap = newBitmap
        bitmapCanvas = newCanvas
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (::canvasBitmap.isInitialized) {
            canvas.drawBitmap(canvasBitmap, 0f, 0f, null)
        }
        
        if (currentTool != Tool.FILL) {
            updatePaint(currentColor, strokeSize, currentTool)
            canvas.drawPath(currentPath, drawPaint)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val nx = x / width   // normalized 0..1
        val ny = y / height

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (currentTool == Tool.FILL) {
                    floodFill(x.toInt(), y.toInt(), currentColor)
                    connectionManager?.sendCommand("FILL|${nx}|${ny}|${currentColor}")
                    invalidate()
                    performClick()
                    return true
                }
                currentPath = Path()
                currentPath.moveTo(x, y)
                lastX = x; lastY = y
                connectionManager?.sendCommand("DOWN|${nx}|${ny}|${currentColor}|${strokeSize}|${currentTool.name}")
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == Tool.FILL) return true
                currentPath.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                lastX = x; lastY = y
                connectionManager?.sendCommand("MOVE|${nx}|${ny}")
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (currentTool == Tool.FILL) return true
                
                currentPath.lineTo(x, y)
                val dp = DrawPath(currentPath, currentColor, strokeSize, currentTool)
                paths.add(dp)
                
                updatePaint(dp.color, dp.strokeWidth, dp.tool)
                bitmapCanvas.drawPath(currentPath, drawPaint)
                
                currentPath = Path()
                connectionManager?.sendCommand("UP|${nx}|${ny}")
                invalidate()
                performClick()
            }
        }
        return true
    }

    /**
     * Optimized Flood Fill using IntArray for high performance.
     */
    private fun floodFill(startX: Int, startY: Int, newColor: Int) {
        if (!::canvasBitmap.isInitialized) return
        if (startX !in 0 until canvasBitmap.width || startY !in 0 until canvasBitmap.height) return

        val targetColor = canvasBitmap[startX, startY]
        if (targetColor == newColor) return

        val width = canvasBitmap.width
        val height = canvasBitmap.height
        val pixels = IntArray(width * height)
        canvasBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val queue = ArrayDeque<Int>()
        queue.add(startY * width + startX)
        pixels[startY * width + startX] = newColor

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % width
            val y = idx / width

            // Check 4-way neighbors
            // Left
            if (x > 0 && pixels[idx - 1] == targetColor) {
                pixels[idx - 1] = newColor
                queue.addLast(idx - 1)
            }
            // Right
            if (x < width - 1 && pixels[idx + 1] == targetColor) {
                pixels[idx + 1] = newColor
                queue.addLast(idx + 1)
            }
            // Up
            if (y > 0 && pixels[idx - width] == targetColor) {
                pixels[idx - width] = newColor
                queue.addLast(idx - width)
            }
            // Down
            if (y < height - 1 && pixels[idx + width] == targetColor) {
                pixels[idx + width] = newColor
                queue.addLast(idx + width)
            }
        }
        canvasBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    fun setTool(tool: Tool) { currentTool = tool }
    fun setColor(color: Int) { currentColor = color }
    fun setStrokeSize(size: Float) { strokeSize = size }

    fun clearCanvas() {
        paths.clear()
        currentPath = Path()
        if (::canvasBitmap.isInitialized) {
            bitmapCanvas.drawColor(Color.WHITE)
        }
        invalidate()
    }

    fun saveImage(context: Context) {
        val filename = "DrawTablet_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DrawTablet")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        try {
            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri).use { out ->
                    if (out != null) {
                        canvasBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(targetUri, contentValues, null, null)
                }
                Toast.makeText(context, "Immagine salvata in Galleria", Toast.LENGTH_SHORT).show()
            } ?: throw Exception("Impossibile creare URI per il salvataggio")
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Errore salvataggio immagine", Toast.LENGTH_SHORT).show()
        }
    }

    fun setConnectionManager(cm: ConnectionManager) { connectionManager = cm }
}
