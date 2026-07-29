package com.example.p2pmesh

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.location.LocationServices
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.*

// --- DATA STRUCTURES ---
enum class MessageType { TEXT, IMAGE, VOICE, LOCATION }
enum class ChatType { PERSONAL, GROUP }

data class MeshMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val senderName: String,
    val senderId: String,
    val receiverId: String?, // Group Chat के लिए null
    val chatType: ChatType,
    val type: MessageType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

data class MeshPeer(
    val endpointId: String,
    val name: String
)

// --- MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {

    private var myUsername by mutableStateOf("Sarkar_" + Random().nextInt(1000))
    private var myEndpointId by mutableStateOf("")
    private val activePeers = mutableStateListOf<MeshPeer>()
    private val messageHistory = mutableStateListOf<MeshMessage>()
    private var selectedPeer by mutableStateOf<MeshPeer?>(null) // null = Group Chat

    private val SERVICE_ID = "com.example.ss.P2P_MESH"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen(
                    myUsername = myUsername,
                    onUsernameChange = { myUsername = it },
                    activePeers = activePeers,
                    selectedPeer = selectedPeer,
                    onSelectPeer = { selectedPeer = it },
                    messages = messageHistory.filter {
                        if (selectedPeer == null) {
                            it.chatType == ChatType.GROUP
                        } else {
                            (it.senderId == selectedPeer?.endpointId || it.receiverId == selectedPeer?.endpointId)
                        }
                    },
                    onSendMessage = { content, type ->
                        sendMessage(content, type)
                    },
                    onStartMesh = {
                        startAdvertising()
                        startDiscovery()
                    },
                    onSendLocation = { shareLocation() }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
        requestPermissions(permissions, 101)
    }

    // --- NEARBY CONNECTIONS (P2P MESH ENGINE) ---
    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(this).startAdvertising(
            myUsername, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            Toast.makeText(this, "Mesh Network Connected (Advertising)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(this).startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, discoveryOptions
        )
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myUsername, endpointId, connectionLifecycleCallback)
        }

        override fun onEndpointLost(endpointId: String) {
            activePeers.removeAll { it.endpointId == endpointId }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
            val newPeer = MeshPeer(endpointId, info.endpointName)
            if (!activePeers.contains(newPeer)) activePeers.add(newPeer)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                myEndpointId = endpointId
            }
        }

        override fun onDisconnected(endpointId: String) {
            activePeers.removeAll { it.endpointId == endpointId }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val rawData = String(bytes, Charsets.UTF_8)
                val parts = rawData.split("|", limit = 6)
                if (parts.size == 6) {
                    val msg = MeshMessage(
                        type = MessageType.valueOf(parts[0]),
                        senderName = parts[1],
                        senderId = parts[2],
                        receiverId = if (parts[3] == "null") null else parts[3],
                        chatType = ChatType.valueOf(parts[4]),
                        content = parts[5]
                    )
                    messageHistory.add(msg)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun sendMessage(content: String, type: MessageType) {
        val chatType = if (selectedPeer == null) ChatType.GROUP else ChatType.PERSONAL
        val receiverId = selectedPeer?.endpointId

        val msg = MeshMessage(
            senderName = myUsername,
            senderId = myEndpointId.ifEmpty { "LOCAL" },
            receiverId = receiverId,
            chatType = chatType,
            type = type,
            content = content
        )

        messageHistory.add(msg)

        val rawPayload = "${type.name}|${myUsername}|${myEndpointId}|${receiverId}|${chatType.name}|${content}"
        val payload = Payload.fromBytes(rawPayload.toByteArray(Charsets.UTF_8))

        if (chatType == ChatType.GROUP) {
            activePeers.forEach { peer ->
                Nearby.getConnectionsClient(this).sendPayload(peer.endpointId, payload)
            }
        } else {
            receiverId?.let { id ->
                Nearby.getConnectionsClient(this).sendPayload(id, payload)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun shareLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
            if (loc != null) {
                val locData = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                sendMessage("📍 Live Location: $locData", MessageType.LOCATION)
            } else {
                Toast.makeText(this, "GPS Signal Not Found!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// --- WHATSAPP-STYLE UI DESIGN ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    myUsername: String,
    onUsernameChange: (String) -> Unit,
    activePeers: List<MeshPeer>,
    selectedPeer: MeshPeer?,
    onSelectPeer: (MeshPeer?) -> Unit,
    messages: List<MeshMessage>,
    onSendMessage: (String, MessageType) -> Unit,
    onStartMesh: () -> Unit,
    onSendLocation: () -> Unit
) {
    var textState by remember { mutableStateOf("") }
    var showAttachmentMenu by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }

    val context = LocalContext.current
    var mediaRecorder: MediaRecorder? by remember { mutableStateOf(null) }
    var audioFilePath by remember { mutableStateOf("") }

    // Gallery Launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val base64Str = uriToBase64(context, it)
            if (base64Str != null) {
                onSendMessage(base64Str, MessageType.IMAGE)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedPeer?.name ?: "🌐 Mesh Group (Broadcast)",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectedPeer == null) "${activePeers.size} Peers Connected" else "Direct Mesh Chat",
                            fontSize = 12.sp,
                            color = Color.LightGray
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { onStartMesh() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                    ) {
                        Text("Connect", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF075E54))
            )
        }
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF121B22))
        ) {
            // LEFT SIDEBAR: Active Peers List (जहाँ आपने टिक मारा था)
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF1F2C34))
            ) {
                OutlinedTextField(
                    value = myUsername,
                    onValueChange = onUsernameChange,
                    label = { Text("My Nickname", fontSize = 10.sp) },
                    modifier = Modifier.padding(6.dp),
                    singleLine = true
                )

                HorizontalDivider(color = Color.Gray, thickness = 0.5.dp)

                // Group Option
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp)
                        .clickable { onSelectPeer(null) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedPeer == null) Color(0xFF00A884) else Color(0xFF2A3942)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("All Group", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }

                Text(
                    text = "CONNECTED PEERS (${activePeers.size})",
                    fontSize = 10.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // Individual Peers List
                LazyColumn {
                    items(activePeers) { peer ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .clickable { onSelectPeer(peer) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedPeer?.endpointId == peer.endpointId) Color(0xFF00A884) else Color(0xFF2A3942)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color.Green)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    peer.name,
                                    fontSize = 12.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }

            // RIGHT CHAT AREA
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                // Messages List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp),
                    reverseLayout = false
                ) {
                    items(messages) { msg ->
                        ChatBubble(msg, isMe = msg.senderName == myUsername)
                    }
                }

                // Popup Attachment Menu (Gallery, Location, Voice)
                if (showAttachmentMenu) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2C34))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            // Location Button
                            IconButton(onClick = {
                                onSendLocation()
                                showAttachmentMenu = false
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = Color(0xFF00E676))
                                    Text("Location", fontSize = 10.sp, color = Color.White)
                                }
                            }

                            // Gallery Button
                            IconButton(onClick = {
                                galleryLauncher.launch("image/*")
                                showAttachmentMenu = false
                            }) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Image, contentDescription = "Gallery", tint = Color(0xFF29B6F6))
                                    Text("Gallery", fontSize = 10.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                // Bottom Input Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment Icon (Pin)
                    IconButton(onClick = { showAttachmentMenu = !showAttachmentMenu }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = Color.Gray)
                    }

                    OutlinedTextField(
                        value = textState,
                        onValueChange = { textState = it },
                        placeholder = { Text("Type message...") },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(25.dp)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1F2C34),
                            unfocusedContainerColor = Color(0xFF1F2C34),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Audio Record / Send Button
                    if (textState.isNotBlank()) {
                        FloatingActionButton(
                            onClick = {
                                onSendMessage(textState, MessageType.TEXT)
                                textState = ""
                            },
                            containerColor = Color(0xFF00A884),
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                        }
                    } else {
                        FloatingActionButton(
                            onClick = {
                                if (!isRecording) {
                                    // Start Recording
                                    audioFilePath = "${context.cacheDir.absolutePath}/voice_msg.3gp"
                                    mediaRecorder = MediaRecorder().apply {
                                        setAudioSource(MediaRecorder.AudioSource.MIC)
                                        setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                                        setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                                        setOutputFile(audioFilePath)
                                        prepare()
                                        start()
                                    }
                                    isRecording = true
                                    Toast.makeText(context, "Recording started...", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Stop Recording & Send
                                    try {
                                        mediaRecorder?.stop()
                                        mediaRecorder?.release()
                                        mediaRecorder = null
                                        isRecording = false

                                        val audioBytes = File(audioFilePath).readBytes()
                                        val base64Audio = Base64.encodeToString(audioBytes, Base64.DEFAULT)
                                        onSendMessage(base64Audio, MessageType.VOICE)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            containerColor = if (isRecording) Color.Red else Color(0xFF00A884),
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Mic",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- CHAT BUBBLE COMPONENT ---
@Composable
fun ChatBubble(message: MeshMessage, isMe: Boolean) {
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    val timeString = formatter.format(Date(message.timestamp))
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isMe) Color(0xFF005C4B) else Color(0xFF202C33)
            ),
            modifier = Modifier.widthIn(max = 260.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                if (!isMe) {
                    Text(
                        text = message.senderName,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF8A65)
                    )
                }

                when (message.type) {
                    MessageType.TEXT -> {
                        Text(text = message.content, color = Color.White, fontSize = 14.sp)
                    }
                    MessageType.LOCATION -> {
                        Text(text = message.content, color = Color(0xFF00E676), fontSize = 13.sp)
                    }
                    MessageType.IMAGE -> {
                        val bitmap = decodeBase64ToBitmap(message.content)
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = "Shared Image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                    MessageType.VOICE -> {
                        var isPlaying by remember { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            IconButton(onClick = {
                                try {
                                    val audioBytes = Base64.decode(message.content, Base64.DEFAULT)
                                    val tempFile = File.createTempFile("voice", "3gp", context.cacheDir)
                                    val fos = FileOutputStream(tempFile)
                                    fos.write(audioBytes)
                                    fos.close()

                                    val mediaPlayer = MediaPlayer().apply {
                                        setDataSource(tempFile.absolutePath)
                                        prepare()
                                        start()
                                    }
                                    isPlaying = true
                                    mediaPlayer.setOnCompletionListener {
                                        isPlaying = false
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play Audio",
                                    tint = Color.Cyan
                                )
                            }
                            Text(
                                text = "Voice Message",
                                color = Color.Cyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeString,
                        fontSize = 9.sp,
                        color = Color.LightGray
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = "Delivered",
                            tint = Color(0xFF34B7F1),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

// Utility Helpers
fun decodeBase64ToBitmap(base64Str: String): Bitmap? {
    return try {
        val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    } catch (e: Exception) {
        null
    }
}

fun uriToBase64(context: Context, uri: Uri): String? {
    return try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 60, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        Base64.encodeToString(byteArray, Base64.DEFAULT)
    } catch (e: Exception) {
        null
    }
}
