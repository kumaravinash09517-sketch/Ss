package com.example.p2pmesh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateFormat
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Date
import java.util.UUID
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val SERVICE_ID = "com.example.p2pmesh.SERVICE_ID"
        private const val BROADCAST_TARGET = "BROADCAST"
        private const val IMPACT_G_THRESHOLD = 4.5f
    }

    private lateinit var connectionsClient: ConnectionsClient
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private lateinit var etUserName: EditText
    private lateinit var btnRegister: Button
    private lateinit var rvPeers: RecyclerView
    private lateinit var btnStartAdv: Button
    private lateinit var btnStartDisc: Button
    private lateinit var btnBroadcastSos: Button
    private lateinit var btnSimulateImpact: Button
    private lateinit var btnSendImage: Button
    private lateinit var tvAuditLog: TextView
    private lateinit var svAuditLog: ScrollView

    private lateinit var peerAdapter: PeerAdapter
    private val peerList = mutableListOf<Peer>()
    private val connectedEndpoints = mutableMapOf<String, String>() // endpointId -> name

    private var localUserName: String = "MeshNode_" + UUID.randomUUID().toString().take(4)
    private var isRegistered: Boolean = false
    private val seenPacketIds = mutableSetOf<String>()
    private val chatHistories = mutableMapOf<String, StringBuilder>() // endpointId/name -> chat log

    private var activeChatDialog: AlertDialog? = null
    private var activeChatPeer: Peer? = null
    private var activeChatHistoryTextView: TextView? = null

    private var lastImpactTimestamp = 0L

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { sendImageFromUri(it) }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            logAudit("All required Bluetooth & Mesh permissions granted.")
        } else {
            logAudit("Warning: Some permissions were denied. Mesh discovery may be restricted.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectionsClient = Nearby.getConnectionsClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        initViews()
        setupRecyclerView()
        checkAndRequestPermissions()

        logAudit("Initialization complete. Enter Peer Name and click Register & Join Mesh.")
    }

    private fun initViews() {
        etUserName = findViewById(R.id.etUserName)
        btnRegister = findViewById(R.id.btnRegister)
        rvPeers = findViewById(R.id.rvPeers)
        btnStartAdv = findViewById(R.id.btnStartAdv)
        btnStartDisc = findViewById(R.id.btnStartDisc)
        btnBroadcastSos = findViewById(R.id.btnBroadcastSos)
        btnSimulateImpact = findViewById(R.id.btnSimulateImpact)
        btnSendImage = findViewById(R.id.btnSendImage)
        tvAuditLog = findViewById(R.id.tvAuditLog)
        svAuditLog = findViewById(R.id.svAuditLog)

        etUserName.setText(localUserName)

        btnRegister.setOnClickListener {
            val inputName = etUserName.text.toString().trim()
            if (inputName.isNotEmpty()) {
                localUserName = inputName
                isRegistered = true
                logAudit("Node Registered as: '$localUserName'. Auto-starting Advertising & Discovery.")
                startAdvertising()
                startDiscovery()
            } else {
                Toast.makeText(this, "Please enter a valid peer name", Toast.LENGTH_SHORT).show()
            }
        }

        btnStartAdv.setOnClickListener { startAdvertising() }
        btnStartDisc.setOnClickListener { startDiscovery() }

        btnBroadcastSos.setOnClickListener {
            broadcastSosAlert("MANUAL EMERGENCY SOS BROADCAST")
        }

        btnSimulateImpact.setOnClickListener {
            simulateImpactAccident()
        }

        btnSendImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }
    }

    private fun setupRecyclerView() {
        peerAdapter = PeerAdapter(peerList) { peer ->
            openPrivateChatDialog(peer)
        }
        rvPeers.layoutManager = LinearLayoutManager(this)
        rvPeers.adapter = peerAdapter
    }

    // --- NEARBY CONNECTIONS MESH ENGINE ---

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startAdvertising(
            localUserName,
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            logAudit("ADVERTISING: Started successfully as '$localUserName'.")
        }.addOnFailureListener { e ->
            logAudit("ADVERTISING ERROR: ${e.localizedMessage}")
        }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            logAudit("DISCOVERY: Started searching for mesh peers...")
        }.addOnFailureListener { e ->
            logAudit("DISCOVERY ERROR: ${e.localizedMessage}")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            logAudit("PEER DISCOVERED: ID=$endpointId, Name=${info.endpointName}")
            addOrUpdatePeer(endpointId, info.endpointName, PeerStatus.DISCOVERED)

            // Auto-request connection for seamless P2P mesh cluster
            logAudit("Connecting to peer $endpointId...")
            connectionsClient.requestConnection(localUserName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    addOrUpdatePeer(endpointId, info.endpointName, PeerStatus.CONNECTING)
                }
                .addOnFailureListener { e ->
                    logAudit("Connection request to $endpointId failed: ${e.localizedMessage}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            logAudit("PEER LOST: Endpoint $endpointId went out of range.")
            addOrUpdatePeer(endpointId, connectedEndpoints[endpointId] ?: "Unknown", PeerStatus.DISCONNECTED)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            logAudit("CONNECTION INITIATED: Accepting connection with ${info.endpointName} ($endpointId)")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
            addOrUpdatePeer(endpointId, info.endpointName, PeerStatus.CONNECTING)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val peerName = peerList.find { it.endpointId == endpointId }?.name ?: "Peer_$endpointId"
                connectedEndpoints[endpointId] = peerName
                addOrUpdatePeer(endpointId, peerName, PeerStatus.CONNECTED)
                logAudit("MESH CONNECTED: Peer '$peerName' ($endpointId) joined local cluster.")
                
                // Send node announce packet
                sendMeshPacket(
                    targetEndpointId = BROADCAST_TARGET,
                    type = "ANNOUNCE",
                    content = "Node $localUserName online."
                )
            } else {
                logAudit("CONNECTION REJECTED/FAILED with endpoint $endpointId")
                addOrUpdatePeer(endpointId, "Unknown", PeerStatus.DISCONNECTED)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val peerName = connectedEndpoints.remove(endpointId) ?: "Unknown"
            addOrUpdatePeer(endpointId, peerName, PeerStatus.DISCONNECTED)
            logAudit("PEER DISCONNECTED: '$peerName' ($endpointId)")
        }
    }

    // --- PAYLOAD & MULTI-HOP MESH RELAY ENGINE ---

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val jsonStr = String(bytes, Charsets.UTF_8)
            try {
                val packet = JSONObject(jsonStr)
                val packetId = packet.getString("packetId")
                val senderName = packet.getString("senderName")
                val senderId = packet.getString("senderId")
                val targetId = packet.getString("targetId")
                val type = packet.getString("type")
                val content = packet.getString("content")
                val hopCount = packet.optInt("hopCount", 0)

                // Deduplication Check
                if (seenPacketIds.contains(packetId)) {
                    return // Ignore duplicate packet
                }
                seenPacketIds.add(packetId)

                logAudit("PAYLOAD RECEIVED [Type: $type] from $senderName (Hops: $hopCount)")

                val isForMe = targetId == BROADCAST_TARGET || targetId == localUserName || targetId == getLocalEndpointId()

                if (isForMe) {
                    handleIncomingPacket(senderName, senderId, targetId, type, content)
                }

                // MULTI-HOP RELAY FORWARDING LOGIC
                // Relay packet to all other connected peers except the immediate incoming sender
                if (hopCount < 5) {
                    relayPacketToCluster(packet, incomingSenderEndpoint = endpointId)
                }

            } catch (e: Exception) {
                logAudit("ERROR parsing mesh payload: ${e.localizedMessage}")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Optional transfer progress tracking
        }
    }

    private fun handleIncomingPacket(senderName: String, senderId: String, targetId: String, type: String, content: String) {
        when (type) {
            "CHAT" -> {
                appendChatMessage(senderId, senderName, "[$senderName]: $content")
                logAudit("CHAT MSG from $senderName: $content")
            }
            "SOS" -> {
                logAudit("🚨 SOS EMERGENCY ALERT from $senderName: $content")
                showAlertModal("🚨 EMERGENCY SOS ALERT", "Received SOS from Mesh Peer '$senderName':\n\n$content")
            }
            "IMPACT" -> {
                logAudit("💥 COLLISION IMPACT DETECTED on $senderName: $content")
                showAlertModal("💥 VEHICLE CRASH DETECTED", "G-Force Impact threshold exceeded on peer '$senderName'!\nTelemetry: $content")
            }
            "IMAGE" -> {
                logAudit("📸 IMAGE PAYLOAD received from $senderName (Base64 length: ${content.length})")
                appendChatMessage(senderId, senderName, "[$senderName]: [Received Image - ${content.length / 1024} KB]")
            }
            "ANNOUNCE" -> {
                logAudit("MESH ANNOUNCE: $content")
            }
        }
    }

    private fun relayPacketToCluster(packetJson: JSONObject, incomingSenderEndpoint: String) {
        try {
            val newHopCount = packetJson.optInt("hopCount", 0) + 1
            packetJson.put("hopCount", newHopCount)
            val jsonBytes = packetJson.toString().toByteArray(Charsets.UTF_8)

            var relayCount = 0
            for ((epId, _) in connectedEndpoints) {
                if (epId != incomingSenderEndpoint) {
                    connectionsClient.sendPayload(epId, Payload.fromBytes(jsonBytes))
                    relayCount++
                }
            }
            if (relayCount > 0) {
                logAudit("FORWARDED/RELAYED packet ${packetJson.optString("packetId").take(8)} to $relayCount mesh peers.")
            }
        } catch (e: Exception) {
            logAudit("Relay error: ${e.localizedMessage}")
        }
    }

    private fun sendMeshPacket(targetEndpointId: String, type: String, content: String) {
        val packetId = UUID.randomUUID().toString()
        seenPacketIds.add(packetId)

        val packet = JSONObject().apply {
            put("packetId", packetId)
            put("senderName", localUserName)
            put("senderId", localUserName)
            put("targetId", targetEndpointId)
            put("type", type)
            put("content", content)
            put("hopCount", 0)
        }

        val bytes = packet.toString().toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(bytes)

        if (connectedEndpoints.isEmpty()) {
            logAudit("WARNING: No connected peers to transmit packet ($type). Searching for peers...")
            return
        }

        if (targetEndpointId == BROADCAST_TARGET) {
            for ((epId, _) in connectedEndpoints) {
                connectionsClient.sendPayload(epId, payload)
            }
            logAudit("BROADCAST SENT [Type: $type] to ${connectedEndpoints.size} peers.")
        } else {
            // Direct target endpoint
            var sent = false
            for ((epId, peerName) in connectedEndpoints) {
                if (epId == targetEndpointId || peerName == targetEndpointId) {
                    connectionsClient.sendPayload(epId, payload)
                    sent = true
                    logAudit("DIRECT PACKET SENT [Type: $type] to $peerName ($epId)")
                    break
                }
            }
            if (!sent) {
                // Relay broadcast to reach non-adjacent target
                for ((epId, _) in connectedEndpoints) {
                    connectionsClient.sendPayload(epId, payload)
                }
                logAudit("RELAY PACKET DISPATCHED [Target: $targetEndpointId] across mesh cluster.")
            }
        }
    }

    private fun broadcastSosAlert(message: String) {
        sendMeshPacket(BROADCAST_TARGET, "SOS", message)
        logAudit("🚨 SOS BROADCAST DISPATCHED: $message")
        Toast.makeText(this, "Emergency SOS Broadcast Sent!", Toast.LENGTH_SHORT).show()
    }

    private fun simulateImpactAccident() {
        val simulatedG = 6.8f
        val crashTelemetry = "Simulated Collision Impact: ${simulatedG}G at ${DateFormat.format("HH:mm:ss", Date())}"
        logAudit("💥 SENSOR CRASH SIMULATED: Force=${simulatedG}G")
        sendMeshPacket(BROADCAST_TARGET, "IMPACT", crashTelemetry)
        showAlertModal("💥 COLLISION IMPACT ALERT", "Simulated G-Force Accident Sensor Triggered!\n$crashTelemetry")
    }

    private fun sendImageFromUri(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 300, 300, true)
            val baos = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
            val base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            sendMeshPacket(BROADCAST_TARGET, "IMAGE", base64Image)
            logAudit("📸 Image sent over mesh (${baos.size() / 1024} KB).")
            Toast.makeText(this, "Mesh Image Transmitted", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            logAudit("Error processing image: ${e.localizedMessage}")
        }
    }

    // --- ACCELEROMETER G-FORCE ACCIDENT SENSOR ---

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH

            if (gForce > IMPACT_G_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (now - lastImpactTimestamp > 5000) { // 5s cooldown
                    lastImpactTimestamp = now
                    val telemetry = "Real Accelerometer Impact: ${"%.2f".format(gForce)}G"
                    logAudit("💥 REAL ACCELEROMETER CRASH DETECTED! Telemetry: $telemetry")
                    sendMeshPacket(BROADCAST_TARGET, "IMPACT", telemetry)
                    sendMeshPacket(BROADCAST_TARGET, "SOS", "AUTOMATIC SOS: Impact Crash Detected ($telemetry)")
                    showAlertModal("💥 REAL IMPACT DETECTED", "Accelerometer registered ${"%.2f".format(gForce)}G force!\nEmergency SOS auto-transmitted to mesh.")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- PRIVATE CHAT DIALOG ---

    private fun openPrivateChatDialog(peer: Peer) {
        activeChatPeer = peer
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_private_chat, null)
        val tvChatTitle = dialogView.findViewById<TextView>(R.id.tvChatTitle)
        val btnCloseChat = dialogView.findViewById<ImageButton>(R.id.btnCloseChat)
        val tvChatHistory = dialogView.findViewById<TextView>(R.id.tvChatHistory)
        val etChatMessage = dialogView.findViewById<EditText>(R.id.etChatMessage)
        val btnSendChat = dialogView.findViewById<Button>(R.id.btnSendChat)

        activeChatHistoryTextView = tvChatHistory
        tvChatTitle.text = "Private Chat with ${peer.name}"

        val key = peer.endpointId
        val existingHistory = chatHistories.getOrPut(key) { StringBuilder("[Mesh Chat Session Established]\n") }
        tvChatHistory.text = existingHistory.toString()

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        activeChatDialog = dialog

        btnCloseChat.setOnClickListener {
            activeChatDialog?.dismiss()
            activeChatPeer = null
            activeChatHistoryTextView = null
        }

        btnSendChat.setOnClickListener {
            val msg = etChatMessage.text.toString().trim()
            if (msg.isNotEmpty()) {
                sendMeshPacket(peer.endpointId, "CHAT", msg)
                appendChatMessage(peer.endpointId, peer.name, "[Me]: $msg")
                etChatMessage.setText("")
            }
        }

        dialog.setOnDismissListener {
            activeChatPeer = null
            activeChatHistoryTextView = null
        }

        dialog.show()
    }

    private fun appendChatMessage(peerKey: String, peerName: String, formattedLine: String) {
        val history = chatHistories.getOrPut(peerKey) { StringBuilder() }
        history.append(formattedLine).append("\n")

        if (activeChatPeer?.endpointId == peerKey || activeChatPeer?.name == peerName) {
            runOnUiThread {
                activeChatHistoryTextView?.text = history.toString()
            }
        }
    }

    // --- HELPERS & UTILS ---

    private fun addOrUpdatePeer(endpointId: String, name: String, status: PeerStatus) {
        runOnUiThread {
            val existingIndex = peerList.indexOfFirst { it.endpointId == endpointId }
            if (existingIndex >= 0) {
                peerList[existingIndex].status = status
                if (name != "Unknown") {
                    peerList[existingIndex] = peerList[existingIndex].copy(name = name)
                }
            } else {
                peerList.add(Peer(endpointId, name, status))
            }
            peerAdapter.updatePeers(peerList)
        }
    }

    private fun logAudit(message: String) {
        val timestamp = DateFormat.format("HH:mm:ss", Date())
        val logLine = "[$timestamp] $message\n"
        runOnUiThread {
            tvAuditLog.append(logLine)
            svAuditLog.post { svAuditLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun showAlertModal(title: String, message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { d, _ -> d.dismiss() }
                .show()
        }
    }

    private fun getLocalEndpointId(): String = "LOCAL_NODE"

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
