package com.example.p2pmesh

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.room.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

// ==========================================
// 1. ROOM DATABASE SETUP (PERMANENT HISTORY)
// ==========================================

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val text: String = "",
    val isMe: Boolean,
    val time: String,
    val type: String = "TEXT", // TEXT, IMAGE, VOICE, LOCATION, VIDEO
    val localFilePath: String? = null
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY id ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Insert
    suspend fun insertMessage(message: ChatMessage)
}

@Database(entities = [ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "p2p_chat_history.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 2. MAIN ACTIVITY
// ==========================================

class MainActivity : ComponentActivity() {

    private val SERVICE_ID = "com.example.p2pmesh.SERVICE"
    private var myNickname = "Sarkar_702"
    
    // Direct or Indirect Network Peers List
    private val connectedPeersMap = mutableStateMapOf<String, String>() // EndpointId -> Nickname
    
    private lateinit var db: AppDatabase
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        db = AppDatabase.getDatabase(this)
        requestPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF111B21)
                ) {
                    val messageList by db.messageDao().getAllMessages().collectAsState(initial = emptyList())
                    val activePeerList = connectedPeersMap.values.toList()

                    MainChatScreen(
                        nickname = myNickname,
                        connectedPeers = activePeerList,
                        messages = messageList,
                        onConnectClick = { startNearby() },
                        onSendText = { sendTextMessage(it) },
                        onSendLocation = { sendLiveLocation() },
                        onRecordVoice = { startRecording() },
                        onStopRecordVoice = { stopRecordingAndSend() },
                        onSendMedia = { uri, type -> sendMediaFile(uri, type) }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        requestPermissions(permissions.toTypedArray(), 101)
    }

    private fun startNearby() {
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(this)
            .startAdvertising(myNickname, SERVICE_ID, connectionLifecycleCallback, options)

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(this)
            .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)

        Toast.makeText(this, "Searching Nearby Peers...", Toast.LENGTH_SHORT).show()
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Nearby.getConnectionsClient(this@MainActivity)
                .requestConnection(myNickname, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            connectedPeersMap[endpointId] = connectionInfo.endpointName
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Toast.makeText(this@MainActivity, "Connected!", Toast.LENGTH_SHORT).show()
                broadcastMyPeerList()
            } else {
                connectedPeersMap.remove(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedPeersMap.remove(endpointId)
            broadcastMyPeerList()
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { bytes ->
                val strData = String(bytes)
                val parts = strData.split("||")
                val type = parts.getOrNull(0) ?: "TEXT"
                val senderName = parts.getOrNull(1) ?: "Peer"
                val content = parts.getOrNull(2) ?: ""

                // 🌐 PEER LIST PROPAGATION (Mesh Peer Sync)
                if (type == "PEER_SYNC") {
                    val sharedPeers = content.split(",")
                    for (peerName in sharedPeers) {
                        if (peerName.isNotBlank() && peerName != myNickname && !connectedPeersMap.containsValue(peerName)) {
                            connectedPeersMap["mesh_${peerName}"] = "$peerName (via Mesh)"
                        }
                    }
                    return@let
                }

                val time = getCurrentTime()

                CoroutineScope(Dispatchers.IO).launch {
                    val msg = ChatMessage(
                        sender = senderName,
                        text = if (type == "TEXT" || type == "LOCATION") content else "",
                        isMe = false,
                        time = time,
                        type = type,
                        localFilePath = if (type != "TEXT" && type != "LOCATION") saveIncomingFile(content, type) else null
                    )
                    db.messageDao().insertMessage(msg)
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun broadcastMyPeerList() {
        val allPeerNames = connectedPeersMap.values.joinToString(",")
        val syncData = "PEER_SYNC||$myNickname||$allPeerNames"
        broadcastPayload(syncData)
    }

    private fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        val payloadData = "TEXT||$myNickname||$text"
        broadcastPayload(payloadData)
        saveMessageToDb(ChatMessage(sender = myNickname, text = text, isMe = true, time = getCurrentTime(), type = "TEXT"))
    }

    private fun sendLiveLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    val locUrl = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                    val payloadData = "LOCATION||$myNickname||$locUrl"
                    broadcastPayload(payloadData)
                    saveMessageToDb(ChatMessage(sender = myNickname, text = locUrl, isMe = true, time = getCurrentTime(), type = "LOCATION"))
                }
            }
        }
    }

    private fun sendMediaFile(uri: Uri, type: String) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes()
            inputStream?.close()

            if (bytes != null) {
                val base64Str = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                val payloadData = "$type||$myNickname||$base64Str"
                broadcastPayload(payloadData)

                val file = File(cacheDir, "sent_${System.currentTimeMillis()}.${if(type == "IMAGE") "jpg" else "mp4"}")
                FileOutputStream(file).use { it.write(bytes) }

                saveMessageToDb(ChatMessage(
                    sender = myNickname,
                    isMe = true,
                    time = getCurrentTime(),
                    type = type,
                    localFilePath = file.absolutePath
                ))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to send file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        val file = File(cacheDir, "audio_${System.currentTimeMillis()}.3gp")
        audioFilePath = file.absolutePath
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            setOutputFile(audioFilePath)
            prepare()
            start()
        }
    }

    private fun stopRecordingAndSend() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null

            val file = File(audioFilePath)
            if (file.exists()) {
                val bytes = file.readBytes()
                val base64Str = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                broadcastPayload("VOICE||$myNickname||$base64Str")
                
                saveMessageToDb(ChatMessage(
                    sender = myNickname,
                    isMe = true,
                    time = getCurrentTime(),
                    type = "VOICE",
                    localFilePath = audioFilePath
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun broadcastPayload(data: String) {
        val payload = Payload.fromBytes(data.toByteArray())
        for (peerId in connectedPeersMap.keys) {
            if (!peerId.startsWith("mesh_")) { // Only send directly to physical connections
                Nearby.getConnectionsClient(this).sendPayload(peerId, payload)
            }
        }
    }

    private fun saveMessageToDb(msg: ChatMessage) {
        CoroutineScope(Dispatchers.IO).launch {
            db.messageDao().insertMessage(msg)
        }
    }

    private fun saveIncomingFile(base64Str: String, type: String): String {
        val bytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
        val ext = when(type) {
            "IMAGE" -> "jpg"
            "VIDEO" -> "mp4"
            else -> "3gp"
        }
        val file = File(filesDir, "received_${System.currentTimeMillis()}.$ext")
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun getCurrentTime(): String {
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    }
}

// ==========================================
// 3. COMPOSE UI COMPONENTS
// ==========================================

@Composable
fun MainChatScreen(
    nickname: String,
    connectedPeers: List<String>,
    messages: List<ChatMessage>,
    onConnectClick: () -> Unit,
    onSendText: (String) -> Unit,
    onSendLocation: () -> Unit,
    onRecordVoice: () -> Unit,
    onStopRecordVoice: () -> Unit,
    onSendMedia: (Uri, String) -> Unit
) {
    var textState by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var selectedMediaForView by remember { mutableStateOf<ChatMessage?>(null) }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSendMedia(it, "IMAGE") }
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSendMedia(it, "VIDEO") }
    }

    selectedMediaForView?.let { msg ->
        MediaViewerDialog(msg = msg, onDismiss = { selectedMediaForView = null })
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // Left Sidebar
        Column(
            modifier = Modifier
                .width(140.dp)
                .fillMaxHeight()
                .background(Color(0xFF111B21))
                .padding(8.dp)
        ) {
            OutlinedTextField(
                value = nickname,
                onValueChange = {},
                readOnly = true,
                label = { Text("My Nickname", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00A884))
            )
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onConnectClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Connect")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("CONNECTED PEERS (${connectedPeers.size})", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                items(connectedPeers) { peer ->
                    Text(
                        text = "• $peer",
                        color = Color(0xFF81D4FA),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }

        // Chat Screen
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(Color(0xFF0B141A))
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp)
            ) {
                items(messages) { msg ->
                    ChatBubbleItem(msg = msg, onClick = { selectedMediaForView = msg })
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { imageLauncher.launch("image/*") }) {
                    Icon(Icons.Default.Share, contentDescription = "Photo", tint = Color.Gray)
                }
                IconButton(onClick = { videoLauncher.launch("video/*") }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Video", tint = Color.Gray)
                }
                IconButton(onClick = onSendLocation) {
                    Icon(Icons.Default.LocationOn, contentDescription = "Location", tint = Color.Gray)
                }

                OutlinedTextField(
                    value = textState,
                    onValueChange = { textState = it },
                    placeholder = { Text("Type message...") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF00A884))
                )

                Spacer(modifier = Modifier.width(4.dp))

                if (textState.isNotBlank()) {
                    IconButton(onClick = {
                        onSendText(textState)
                        textState = ""
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF00A884))
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (isRecording) {
                                onStopRecordVoice()
                                isRecording = false
                            } else {
                                onRecordVoice()
                                isRecording = true
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Voice",
                            tint = if (isRecording) Color.Red else Color(0xFF00A884)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBubbleItem(msg: ChatMessage, onClick: () -> Unit) {
    val alignment = if (msg.isMe) Alignment.End else Alignment.Start
    val bgColor = if (msg.isMe) Color(0xFF005C4B) else Color(0xFF202C33)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .clickable { onClick() }
                .padding(10.dp)
        ) {
            Column {
                if (!msg.isMe) {
                    Text(text = msg.sender, color = Color(0xFFE57373), style = MaterialTheme.typography.labelSmall)
                }

                when (msg.type) {
                    "TEXT" -> Text(text = msg.text, color = Color.White)
                    "LOCATION" -> Text(text = "📍 Live Location:\n${msg.text}", color = Color(0xFF81D4FA))
                    "IMAGE" -> {
                        msg.localFilePath?.let { path ->
                            val bitmap = BitmapFactory.decodeFile(path)
                            bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = "Image",
                                    modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } ?: Text("📷 Photo Received", color = Color.White)
                    }
                    "VIDEO" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Video File (Tap to view)", color = Color.White)
                    }
                    "VOICE" -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            msg.localFilePath?.let { path ->
                                val mp = MediaPlayer()
                                mp.setDataSource(path)
                                mp.prepare()
                                mp.start()
                            }
                        }
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Voice Message (Tap to Play)", color = Color.White)
                    }
                }

                Text(
                    text = msg.time,
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
fun MediaViewerDialog(msg: ChatMessage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111B21))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (msg.type == "IMAGE" && msg.localFilePath != null) {
                    val bitmap = BitmapFactory.decodeFile(msg.localFilePath)
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Preview",
                            modifier = Modifier.fillMaxWidth().height(300.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else if (msg.type == "VIDEO") {
                    Text("🎥 Video File Received", color = Color.White)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onDismiss) { Text("Close") }
                    Button(
                        onClick = {
                            msg.localFilePath?.let { path ->
                                saveFileToGallery(context, path, msg.type)
                                Toast.makeText(context, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A884))
                    ) {
                        Text("Save to Gallery")
                    }
                }
            }
        }
    }
}

fun saveFileToGallery(context: Context, filePath: String, type: String) {
    val file = File(filePath)
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        put(MediaStore.MediaColumns.MIME_TYPE, if (type == "IMAGE") "image/jpeg" else "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SarkarMesh")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { outputStream ->
            outputStream.write(file.readBytes())
        }
    }
}
