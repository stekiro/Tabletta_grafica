package com.drawtablet

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var drawingView: DrawingView
    private lateinit var connectionManager: ConnectionManager
    private lateinit var tvStatus: TextView
    private lateinit var etIpAddress: EditText
    private lateinit var btnConnect: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        tvStatus = findViewById(R.id.tvStatus)
        etIpAddress = findViewById(R.id.etIpAddress)
        btnConnect = findViewById(R.id.btnConnect)

        connectionManager = ConnectionManager { connected ->
            runOnUiThread {
                if (connected) {
                    tvStatus.text = "✅ Connesso"
                    tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    btnConnect.text = "Disconnetti"
                } else {
                    tvStatus.text = "❌ Disconnesso"
                    tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    btnConnect.text = "Connetti"
                }
            }
        }

        drawingView.setConnectionManager(connectionManager)

        btnConnect.setOnClickListener {
            if (connectionManager.isConnected()) {
                connectionManager.disconnect()
            } else {
                val ip = etIpAddress.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "Inserisci l'IP del PC", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                connectionManager.connect(ip, 9999)
            }
        }

        setupToolbar()
    }

    private fun setupToolbar() {
        // Tool buttons
        findViewById<ImageButton>(R.id.btnPencil).setOnClickListener {
            drawingView.setTool(DrawingView.Tool.PENCIL)
            highlightTool(it)
        }
        findViewById<ImageButton>(R.id.btnBrush).setOnClickListener {
            drawingView.setTool(DrawingView.Tool.BRUSH)
            highlightTool(it)
        }
        findViewById<ImageButton>(R.id.btnEraser).setOnClickListener {
            drawingView.setTool(DrawingView.Tool.ERASER)
            highlightTool(it)
        }
        findViewById<ImageButton>(R.id.btnFill).setOnClickListener {
            drawingView.setTool(DrawingView.Tool.FILL)
            highlightTool(it)
        }
        findViewById<ImageButton>(R.id.btnClear).setOnClickListener {
            drawingView.clearCanvas()
            connectionManager.sendCommand("CLEAR")
        }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener {
            drawingView.saveImage(this)
        }
        findViewById<ImageButton>(R.id.btnColor).setOnClickListener {
            showColorPicker()
        }

        // Stroke size seekbar
        val seekBar = findViewById<SeekBar>(R.id.seekStroke)
        seekBar.progress = 10
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = (progress + 2).toFloat()
                drawingView.setStrokeSize(size)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Default tool
        highlightTool(findViewById(R.id.btnPencil))
    }

    private var lastHighlighted: View? = null
    private fun highlightTool(view: View) {
        lastHighlighted?.setBackgroundResource(0)
        view.setBackgroundResource(R.drawable.tool_selected_bg)
        lastHighlighted = view
    }

    private fun showColorPicker() {
        val colors = arrayOf(
            "Nero" to 0xFF000000.toInt(),
            "Rosso" to 0xFFE53935.toInt(),
            "Verde" to 0xFF43A047.toInt(),
            "Blu" to 0xFF1E88E5.toInt(),
            "Giallo" to 0xFFFDD835.toInt(),
            "Arancione" to 0xFFFB8C00.toInt(),
            "Viola" to 0xFF8E24AA.toInt(),
            "Ciano" to 0xFF00ACC1.toInt(),
            "Rosa" to 0xFFE91E63.toInt(),
            "Bianco" to 0xFFFFFFFF.toInt(),
            "Marrone" to 0xFF6D4C41.toInt(),
            "Grigio" to 0xFF757575.toInt()
        )
        val names = colors.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Scegli colore")
            .setItems(names) { _, which ->
                drawingView.setColor(colors[which].second)
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        // Disconnect when app is no longer visible to save resources
        if (::connectionManager.isInitialized) {
            connectionManager.disconnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::connectionManager.isInitialized) {
            connectionManager.disconnect()
        }
    }
}
