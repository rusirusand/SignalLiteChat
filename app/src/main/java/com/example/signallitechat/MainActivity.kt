
package com.example.signallitechat

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy {
        getSharedPreferences("signal_lite", MODE_PRIVATE)
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private lateinit var serverInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var recipientInput: EditText
    private lateinit var messageInput: EditText
    private lateinit var statusText: TextView
    private lateinit var chatText: TextView

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var manuallyStopped = false
    private var retrySeconds = 2L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        restoreFields()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            setBackgroundColor(0xFFF6F8FC.toInt())
        }

        val title = TextView(this).apply {
            text = "Signal Lite Chat"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF111827.toInt())
        }
        root.addView(title)

        statusText = TextView(this).apply {
            text = "Not connected"
            textSize = 13f
            setPadding(0, dp(5), 0, dp(8))
        }
        root.addView(statusText)

        serverInput = field("WebSocket server URL (wss://your-domain)")
        usernameInput = field("Your username")
        recipientInput = field("Recipient username")

        root.addView(serverInput)
        root.addView(usernameInput)
        root.addView(recipientInput)

        val connectRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener {
                saveFields()
                manuallyStopped = false
                retrySeconds = 2L
                connect()
            }
        }

        val disconnectButton = Button(this).apply {
            text = "Disconnect"
            setOnClickListener {
                manuallyStopped = true
                connected = false
                socket?.close(1000, "User disconnected")
                socket = null
                setStatus("Disconnected")
            }
        }

        connectRow.addView(
            connectButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        connectRow.addView(
            disconnectButton,
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )
        root.addView(connectRow)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        chatText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF1F2937.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = "Messages will appear here.\n"
        }

        scroll.addView(chatText)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        )

        messageInput = field("Write a text message")
        messageInput.setSingleLine(false)
        messageInput.maxLines = 4
        root.addView(messageInput)

        val sendButton = Button(this).apply {
            text = "Send message"
            setOnClickListener {
                sendMessage()
            }
        }

        root.addView(
            sendButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        setContentView(root)
    }

    private fun field(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            textSize = 14f
            setSingleLine(hintText != "Write a text message")
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = getDrawable(android.R.drawable.edit_text)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                bottomMargin = dp(5)
            }
        }
    }

    private fun restoreFields() {
        serverInput.setText(
            prefs.getString("server", "ws://10.0.2.2:8080")
        )
        usernameInput.setText(prefs.getString("username", "alice"))
        recipientInput.setText(prefs.getString("recipient", "bob"))
    }

    private fun saveFields() {
        prefs.edit()
            .putString("server", serverInput.text.toString().trim())
            .putString("username", usernameInput.text.toString().trim())
            .putString("recipient", recipientInput.text.toString().trim())
            .apply()
    }

    private fun connect() {
        if (manuallyStopped) return

        val url = serverInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()

        if (!(url.startsWith("ws://") || url.startsWith("wss://")) ||
            username.isBlank()
        ) {
            setStatus("Enter a valid server URL and username")
            return
        }

        val request = try {
            Request.Builder().url(url).build()
        } catch (_: Exception) {
            setStatus("Invalid server URL")
            return
        }

        socket?.cancel()
        connected = false
        setStatus("Connecting…")

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    connected = true
                    retrySeconds = 2L

                    val register = JSONObject()
                        .put("type", "register")
                        .put("username", username)

                    webSocket.send(register.toString())

                    mainHandler.post {
                        setStatus("Connected · registering…")
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    try {
                        val obj = JSONObject(text)

                        when (obj.optString("type")) {
                            "registered" -> {
                                mainHandler.post {
                                    setStatus(
                                        "Connected as ${obj.optString("username")}"
                                    )
                                    flushOutbox()
                                }
                            }

                            "message" -> {
                                val id = obj.optString("id")
                                val from = obj.optString("from")
                                val body = obj.optString("text")

                                mainHandler.post {
                                    appendChat("$from: $body")
                                }

                                webSocket.send(
                                    JSONObject()
                                        .put("type", "delivered")
                                        .put("id", id)
                                        .toString()
                                )
                            }

                            "status" -> {
                                val id = obj.optString("id")
                                removeFromOutbox(id)

                                mainHandler.post {
                                    appendChat(
                                        "Message $id: ${obj.optString("status")}"
                                    )
                                }
                            }

                            "error" -> {
                                mainHandler.post {
                                    appendChat(
                                        "Server error: ${obj.optString("message")}"
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                        mainHandler.post {
                            appendChat("Could not read server response.")
                        }
                    }
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    connected = false
                    scheduleReconnect("Connection closed")
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    connected = false

                    mainHandler.post {
                        setStatus(
                            "Connection lost · ${t.message ?: "network error"}"
                        )
                    }

                    scheduleReconnect("Connection lost")
                }
            }
        )
    }

    private fun scheduleReconnect(reason: String) {
        if (manuallyStopped) return

        val delay = retrySeconds
        retrySeconds = min(retrySeconds * 2, 60L)

        mainHandler.post {
            if (!manuallyStopped) {
                setStatus("$reason · retrying in ${delay}s")
            }
        }

        mainHandler.postDelayed({
            if (!manuallyStopped && !connected) {
                connect()
            }
        }, delay * 1000)
    }

    private fun sendMessage() {
        val to = recipientInput.text.toString().trim()
        val body = messageInput.text.toString().trim()

        if (to.isBlank() || body.isBlank()) {
            setStatus("Enter a recipient and a message")
            return
        }

        if (body.length > 2000) {
            setStatus("Keep messages under 2000 characters")
            return
        }

        val item = JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("to", to)
            .put("text", body)

        addToOutbox(item)
        appendChat("You → $to: $body (pending)")
        messageInput.setText("")

        if (connected) {
            flushOutbox()
        } else {
            setStatus("Saved locally · waiting for connection")
        }
    }

    private fun flushOutbox() {
        if (!connected) return

        val current = socket ?: return
        val items = getOutbox()

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue

            val packet = JSONObject()
                .put("type", "send")
                .put("id", item.optString("id"))
                .put("to", item.optString("to"))
                .put("text", item.optString("text"))

            if (!current.send(packet.toString())) {
                setStatus("Network busy · message remains queued")
                break
            }
        }
    }

    private fun getOutbox(): JSONArray {
        return try {
            JSONArray(prefs.getString("outbox", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun addToOutbox(item: JSONObject) {
        val old = getOutbox()
        old.put(item)
        prefs.edit().putString("outbox", old.toString()).apply()
    }

    private fun removeFromOutbox(id: String) {
        val old = getOutbox()
        val updated = JSONArray()

        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("id") != id) {
                updated.put(item)
            }
        }

        prefs.edit().putString("outbox", updated.toString()).apply()
    }

    private fun appendChat(line: String) {
        chatText.append("$line\n\n")
    }

    private fun setStatus(value: String) {
        statusText.text = value
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        socket?.close(1000, "App closed")
        client.dispatcher.executorService.shutdown()
    }
}
