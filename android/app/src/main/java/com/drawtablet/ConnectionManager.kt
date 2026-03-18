package com.drawtablet

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class ConnectionManager(private val onStatusChange: (Boolean) -> Unit) {

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val connected = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val messageQueue = LinkedBlockingQueue<String>()
    private var senderThread: Thread? = null

    fun connect(ip: String, port: Int) {
        if (connected.get() || connecting.get()) return
        
        connecting.set(true)
        thread {
            try {
                val newSocket = Socket()
                newSocket.connect(InetSocketAddress(ip, port), 5000)
                socket = newSocket
                writer = PrintWriter(BufferedWriter(OutputStreamWriter(newSocket.getOutputStream())), true)
                connected.set(true)
                connecting.set(false)
                onStatusChange(true)
                startSender()
            } catch (e: Exception) {
                e.printStackTrace()
                connecting.set(false)
                disconnect()
            }
        }
    }

    private fun startSender() {
        senderThread = thread {
            try {
                while (connected.get()) {
                    val msg = messageQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (msg != null) {
                        writer?.println(msg)
                    }
                }
            } catch (e: Exception) {
                disconnect()
            }
        }
    }

    fun sendCommand(command: String) {
        if (connected.get()) {
            messageQueue.offer(command)
        }
    }

    fun disconnect() {
        if (!connected.get() && !connecting.get() && socket == null) return
        
        connected.set(false)
        connecting.set(false)
        messageQueue.clear()
        try {
            writer?.close()
            socket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        socket = null
        writer = null
        onStatusChange(false)
    }

    fun isConnected() = connected.get()
    fun isConnecting() = connecting.get()
}
