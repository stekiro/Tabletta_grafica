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
import androidx.core.graphics.createBitmap
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Tool { PENCIL, BRUSH, ERASER, FILL }

    data class LayerInfo(val id: Int, val name: String, val visible: Boolean, val active: Boolean)

    private data class DrawingLayer(
        val id: Int,
        val name: String,
        var bitmap: Bitmap,
        var canvas: Canvas,
        var visible: Boolean = true
    )

    private var currentTool = Tool.PENCIL
    private var currentColor = Color.BLACK
    private var strokeSize = 8f
    private var currentPath = Path()
    private var connectionManager: ConnectionManager? = null
    private var lastX = 0f
    private var lastY = 0f
    private var canvasWidth = 0
    private var canvasHeight = 0
    private val layers = mutableListOf<DrawingLayer>()
    private var activeLayerId = 0
    private var nextLayerId = 1

    private val drawPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun newTransparentBitmap(width: Int, height: Int): Bitmap =
        createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun activeLayer(): DrawingLayer =
        layers.firstOrNull { it.id == activeLayerId } ?: layers.first()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        canvasWidth = w
        canvasHeight = h

        if (layers.isEmpty()) {
            val bitmap = newTransparentBitmap(w, h)
            layers.add(DrawingLayer(0, "Base", bitmap, Canvas(bitmap)))
            return
        }

        layers.forEach { layer ->
            val resized = newTransparentBitmap(w, h)
            Canvas(resized).drawBitmap(layer.bitmap, null, Rect(0, 0, w, h), null)
            layer.bitmap = resized
            layer.canvas = Canvas(resized)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        layers.filter { it.visible }.forEach { canvas.drawBitmap(it.bitmap, 0f, 0f, null) }

        if (currentTool != Tool.FILL && !currentPath.isEmpty) {
            updatePaint(currentColor, strokeSize, currentTool, eraseOnBitmap = false)
            canvas.drawPath(currentPath, drawPaint)
        }
    }

    private fun updatePaint(color: Int, width: Float, tool: Tool, eraseOnBitmap: Boolean) {
        drawPaint.strokeWidth = if (tool == Tool.BRUSH) width * 2f else width
        drawPaint.maskFilter = if (tool == Tool.BRUSH) {
            BlurMaskFilter(width * 0.4f, BlurMaskFilter.Blur.NORMAL)
        } else null
        drawPaint.alpha = if (tool == Tool.BRUSH) 180 else 255
        drawPaint.xfermode = if (tool == Tool.ERASER && eraseOnBitmap) {
            PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        } else null
        drawPaint.color = if (tool == Tool.ERASER) Color.WHITE else color
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0 || layers.isEmpty()) return false
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        val nx = (x / width.toFloat()).coerceIn(0f, 1f)
        val ny = (y / height.toFloat()).coerceIn(0f, 1f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (currentTool == Tool.FILL) {
                    floodFill(x.toInt(), y.toInt(), currentColor)
                    connectionManager?.sendCommand("FILL|$nx|$ny|$currentColor")
                    invalidate()
                    performClick()
                    return true
                }
                currentPath = Path().apply { moveTo(x, y) }
                lastX = x
                lastY = y
                connectionManager?.sendCommand(
                    "DOWN|$nx|$ny|$currentColor|$strokeSize|${currentTool.name}"
                )
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (currentTool == Tool.FILL) return true
                for (index in 0 until event.historySize) {
                    val historicalX = event.getHistoricalX(index)
                    val historicalY = event.getHistoricalY(index)
                    currentPath.quadTo(
                        lastX, lastY,
                        (historicalX + lastX) / 2f,
                        (historicalY + lastY) / 2f
                    )
                    lastX = historicalX
                    lastY = historicalY
                }
                currentPath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
                lastX = x
                lastY = y
                connectionManager?.sendCommand("MOVE|$nx|$ny")
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (currentTool == Tool.FILL) return true
                currentPath.lineTo(x, y)
                updatePaint(currentColor, strokeSize, currentTool, eraseOnBitmap = true)
                activeLayer().canvas.drawPath(currentPath, drawPaint)
                drawPaint.xfermode = null
                currentPath = Path()
                connectionManager?.sendCommand("UP|$nx|$ny")
                invalidate()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> currentPath = Path()
        }
        return true
    }

    private fun compositeBitmap(): Bitmap {
        val bitmap = newTransparentBitmap(canvasWidth, canvasHeight)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        layers.filter { it.visible }.forEach { canvas.drawBitmap(it.bitmap, 0f, 0f, null) }
        return bitmap
    }

    private fun floodFill(startX: Int, startY: Int, newColor: Int) {
        if (startX !in 0 until canvasWidth || startY !in 0 until canvasHeight) return
        val composite = compositeBitmap()
        val size = canvasWidth * canvasHeight
        val visiblePixels = IntArray(size)
        val layerPixels = IntArray(size)
        composite.getPixels(visiblePixels, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)
        activeLayer().bitmap.getPixels(layerPixels, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight)

        val start = startY * canvasWidth + startX
        val targetColor = visiblePixels[start]
        if (targetColor == newColor) return

        val visited = BooleanArray(size)
        val queue = ArrayDeque<Int>()
        queue.add(start)
        visited[start] = true

        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (visiblePixels[index] != targetColor) continue
            layerPixels[index] = newColor
            val px = index % canvasWidth
            val py = index / canvasWidth

            fun enqueue(candidate: Int, inBounds: Boolean) {
                if (inBounds && !visited[candidate] && visiblePixels[candidate] == targetColor) {
                    visited[candidate] = true
                    queue.addLast(candidate)
                }
            }

            enqueue(index - 1, px > 0)
            enqueue(index + 1, px < canvasWidth - 1)
            enqueue(index - canvasWidth, py > 0)
            enqueue(index + canvasWidth, py < canvasHeight - 1)
        }

        activeLayer().bitmap.setPixels(
            layerPixels, 0, canvasWidth, 0, 0, canvasWidth, canvasHeight
        )
    }

    fun setTool(tool: Tool) { currentTool = tool }
    fun setColor(color: Int) { currentColor = color }
    fun setStrokeSize(size: Float) { strokeSize = size }

    fun getLayers(): List<LayerInfo> = layers.map {
        LayerInfo(it.id, it.name, it.visible, it.id == activeLayerId)
    }

    fun addLayer(): LayerInfo? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val id = nextLayerId++
        val name = "Livello ${layers.size + 1}"
        val bitmap = newTransparentBitmap(canvasWidth, canvasHeight)
        layers.add(DrawingLayer(id, name, bitmap, Canvas(bitmap)))
        activeLayerId = id
        connectionManager?.sendCommand("LAYER|ADD|$id|$name")
        connectionManager?.sendCommand("LAYER|SELECT|$id")
        invalidate()
        return getLayers().first { it.id == id }
    }

    fun selectLayer(id: Int) {
        if (layers.none { it.id == id }) return
        activeLayerId = id
        connectionManager?.sendCommand("LAYER|SELECT|$id")
        invalidate()
    }

    fun deleteActiveLayer(): Boolean {
        if (layers.size <= 1) return false
        val id = activeLayerId
        layers.removeAll { it.id == id }
        connectionManager?.sendCommand("LAYER|DELETE|$id")
        activeLayerId = layers.last().id
        connectionManager?.sendCommand("LAYER|SELECT|$activeLayerId")
        invalidate()
        return true
    }

    fun toggleActiveLayerVisibility(): Boolean {
        val layer = activeLayer()
        layer.visible = !layer.visible
        connectionManager?.sendCommand("LAYER|VISIBLE|${layer.id}|${layer.visible}")
        invalidate()
        return layer.visible
    }

    fun clearActiveLayer() {
        activeLayer().bitmap.eraseColor(Color.TRANSPARENT)
        currentPath = Path()
        connectionManager?.sendCommand("LAYER|CLEAR|$activeLayerId")
        invalidate()
    }

    fun clearCanvas() {
        layers.forEach { it.bitmap.eraseColor(Color.TRANSPARENT) }
        currentPath = Path()
        invalidate()
    }

    fun syncLayersToPc() {
        layers.forEach { layer ->
            connectionManager?.sendCommand("LAYER|ADD|${layer.id}|${layer.name}")
            connectionManager?.sendCommand("LAYER|VISIBLE|${layer.id}|${layer.visible}")
        }
        connectionManager?.sendCommand("LAYER|SELECT|$activeLayerId")
    }

    fun saveImage(context: Context) {
        if (canvasWidth <= 0 || canvasHeight <= 0) return
        val filename = "DrawTablet_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DrawTablet")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        try {
            uri?.let { targetUri ->
                resolver.openOutputStream(targetUri).use { output ->
                    if (output != null) compositeBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(targetUri, values, null, null)
                }
                Toast.makeText(context, "Immagine salvata in Galleria", Toast.LENGTH_SHORT).show()
            } ?: error("Impossibile creare il file")
        } catch (error: Exception) {
            error.printStackTrace()
            Toast.makeText(context, "Errore salvataggio immagine", Toast.LENGTH_SHORT).show()
        }
    }

    fun setConnectionManager(manager: ConnectionManager) { connectionManager = manager }
}
