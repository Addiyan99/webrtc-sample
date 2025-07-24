package com.example.webrtcqrdemo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.webrtc.SessionDescription

class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL_ID = "incoming_calls"
        private const val NOTIFICATION_ID = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
    
    private lateinit var btnStartCall: Button
    private lateinit var btnScanQR: Button
    private lateinit var btnDismissQR: Button
    private lateinit var statusText: TextView
    private lateinit var qrContainer: androidx.cardview.widget.CardView
    private lateinit var qrCodeImage: ImageView
    private lateinit var qrTitle: TextView
    
    // User ID UI elements
    private lateinit var etMyUserId: android.widget.EditText
    private lateinit var btnSaveUserId: Button
    private lateinit var etTargetUserId: android.widget.EditText
    private lateinit var btnCallUser: Button
    
    // Tab UI elements
    private lateinit var btnTabCallId: Button
    private lateinit var btnTabQrCode: Button
    private lateinit var tabCallIdContent: LinearLayout
    private lateinit var tabQrCodeContent: LinearLayout
    
    private var webRTCManager: WebRTCManager? = null
    private var isOfferer = false
    private var pendingOfferData: SignalingData? = null
    private var myUserId: String? = null
    private var signalingClient: SignalingClient? = null
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            initializeWebRTC()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }
    
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(this, "Cancelled", Toast.LENGTH_LONG).show()
        } else {
            handleQRCodeScanned(result.contents)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            Log.d(TAG, "onCreate: Starting activity creation")
            
            setContentView(R.layout.activity_main)
            Log.d(TAG, "onCreate: Layout set successfully")
            
            initViews()
            Log.d(TAG, "onCreate: Views initialized successfully")
            
            setupClickListeners()
            Log.d(TAG, "onCreate: Click listeners set successfully")
            
            loadSavedUserId()
            Log.d(TAG, "onCreate: User ID loaded")
            
            createNotificationChannel()
            Log.d(TAG, "onCreate: Notification channel created")
            
            checkPermissions()
            Log.d(TAG, "onCreate: Permission check completed")
            
            
        } catch (e: Exception) {
            Log.e(TAG, "onCreate: Fatal error during activity creation", e)
            // Show a basic error dialog and finish the activity
            try {
                Toast.makeText(this, "App initialization failed: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (toastError: Exception) {
                Log.e(TAG, "Could not even show toast", toastError)
            }
            finish()
        }
    }
    
    private fun initViews() {
        try {
            btnStartCall = findViewById(R.id.btnStartCall)
            Log.d(TAG, "btnStartCall initialized")
            
            btnScanQR = findViewById(R.id.btnScanQR)
            Log.d(TAG, "btnScanQR initialized")
            
            btnDismissQR = findViewById(R.id.btnDismissQR)
            Log.d(TAG, "btnDismissQR initialized")
            
            statusText = findViewById(R.id.statusText)
            Log.d(TAG, "statusText initialized")
            
            qrContainer = findViewById(R.id.qrContainer)
            Log.d(TAG, "qrContainer initialized")
            
            qrCodeImage = findViewById(R.id.qrCodeImage)
            Log.d(TAG, "qrCodeImage initialized")
            
            qrTitle = findViewById(R.id.qrTitle)
            Log.d(TAG, "qrTitle initialized")
            
            // Initialize User ID UI elements
            etMyUserId = findViewById(R.id.etMyUserId)
            Log.d(TAG, "etMyUserId initialized")
            
            btnSaveUserId = findViewById(R.id.btnSaveUserId)
            Log.d(TAG, "btnSaveUserId initialized")
            
            etTargetUserId = findViewById(R.id.etTargetUserId)
            Log.d(TAG, "etTargetUserId initialized")
            
            btnCallUser = findViewById(R.id.btnCallUser)
            Log.d(TAG, "btnCallUser initialized")
            
            // Initialize Tab UI elements
            btnTabCallId = findViewById(R.id.btnTabCallId)
            Log.d(TAG, "btnTabCallId initialized")
            
            btnTabQrCode = findViewById(R.id.btnTabQrCode)
            Log.d(TAG, "btnTabQrCode initialized")
            
            tabCallIdContent = findViewById(R.id.tabCallIdContent)
            Log.d(TAG, "tabCallIdContent initialized")
            
            tabQrCodeContent = findViewById(R.id.tabQrCodeContent)
            Log.d(TAG, "tabQrCodeContent initialized")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize views", e)
            throw RuntimeException("View initialization failed", e)
        }
    }
    
    private fun setupClickListeners() {
        // Tab functionality
        btnTabCallId.setOnClickListener { showCallIdTab() }
        btnTabQrCode.setOnClickListener { showQrCodeTab() }
        
        // QR Code tab buttons (only for generating QR codes)
        btnStartCall.setOnClickListener { generateQRCode() }
        btnScanQR.setOnClickListener { scanQRCode() }
        btnDismissQR.setOnClickListener { 
            Log.d(TAG, "Dismiss QR button pressed")
            hideQRCode()
        }
        
        // Call via ID tab buttons (only for direct calls)
        btnSaveUserId.setOnClickListener { saveUserId() }
        btnCallUser.setOnClickListener { initiateDirectCall() }
        
        // Enable call button when both user IDs are set
        etTargetUserId.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateCallButtonState()
            }
        })
    }
    
    private fun showCallIdTab() {
        // Update tab button states
        btnTabCallId.background = resources.getDrawable(R.drawable.button_background, null)
        btnTabCallId.setTextColor(resources.getColor(R.color.white, null))
        btnTabQrCode.background = resources.getDrawable(R.drawable.button_outline_background, null)
        btnTabQrCode.setTextColor(resources.getColor(R.color.accent, null))
        
        // Show/hide tab content
        tabCallIdContent.visibility = View.VISIBLE
        tabQrCodeContent.visibility = View.GONE
        
        Log.d(TAG, "Switched to Call via ID tab")
    }
    
    private fun showQrCodeTab() {
        // Update tab button states
        btnTabQrCode.background = resources.getDrawable(R.drawable.button_background, null)
        btnTabQrCode.setTextColor(resources.getColor(R.color.white, null))
        btnTabCallId.background = resources.getDrawable(R.drawable.button_outline_background, null)
        btnTabCallId.setTextColor(resources.getColor(R.color.accent, null))
        
        // Show/hide tab content
        tabCallIdContent.visibility = View.GONE
        tabQrCodeContent.visibility = View.VISIBLE
        
        Log.d(TAG, "Switched to Call via QR tab")
    }
    
    private fun generateQRCode() {
        Log.d(TAG, "Generate QR Code button pressed - only generating QR, not calling")
        startCall() // This will generate the QR code only
    }
    
    private fun initiateDirectCall() {
        val targetUserId = etTargetUserId.text.toString().trim()
        if (targetUserId.isEmpty()) {
            Toast.makeText(this, "Please enter a user ID to call", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.d(TAG, "Initiating direct call to: $targetUserId")
        // Call the existing initiateCall method
        initiateCall()
    }

    private fun saveUserId() {
        val userId = etMyUserId.text.toString().trim()
        if (userId.isEmpty()) {
            Toast.makeText(this, "Please enter a user ID", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Save to SharedPreferences
        val sharedPref = getSharedPreferences("WebRTCApp", MODE_PRIVATE)
        sharedPref.edit().putString("myUserId", userId).apply()
        
        myUserId = userId
        statusText.text = "User ID saved: $userId"
        Toast.makeText(this, "User ID saved: $userId", Toast.LENGTH_SHORT).show()
        
        // Initialize signaling client
        initializeSignalingClient()
        
        // Update UI
        updateCallButtonState()
        
        Log.d(TAG, "User ID saved: $userId")
    }
    
    private fun loadSavedUserId() {
        val sharedPref = getSharedPreferences("WebRTCApp", MODE_PRIVATE)
        val savedUserId = sharedPref.getString("myUserId", null)
        if (savedUserId != null) {
            myUserId = savedUserId
            etMyUserId.setText(savedUserId)
            statusText.text = "Logged in as: $savedUserId"
            Log.d(TAG, "Loaded saved user ID: $savedUserId")
            
            // Initialize signaling client with saved user ID
            initializeSignalingClient()
        }
        updateCallButtonState()
    }
    
    private fun updateCallButtonState() {
        val hasMyUserId = !myUserId.isNullOrEmpty()
        val hasTargetUserId = etTargetUserId.text.toString().trim().isNotEmpty()
        btnCallUser.isEnabled = hasMyUserId && hasTargetUserId
    }
    
    private fun initiateCall() {
        val targetUserId = etTargetUserId.text.toString().trim()
        if (targetUserId.isEmpty()) {
            Toast.makeText(this, "Please enter a user ID to call", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (myUserId.isNullOrEmpty()) {
            Toast.makeText(this, "Please save your user ID first", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Prevent calling yourself
        if (targetUserId.equals(myUserId, ignoreCase = true)) {
            Toast.makeText(this, "Cannot call yourself! Please enter a different user ID", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check permissions before calling
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions for call: ${missingPermissions.joinToString()}")
            Toast.makeText(this, "Camera and microphone permissions required for calls", Toast.LENGTH_LONG).show()
            permissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }
        
        Log.d(TAG, "Initiating call from $myUserId to $targetUserId")
        statusText.text = "Calling $targetUserId..."
        
        // Check if signaling client is connected
        if (signalingClient?.isConnected() != true) {
            statusText.text = "Connecting to server..."
            initializeSignalingClient()
            // Will attempt call after connection
            return
        }
        
        // Create WebRTC offer
        createOfferForCall(targetUserId)
    }
    
    private fun initializeSignalingClient() {
        val userId = myUserId
        if (userId.isNullOrEmpty()) {
            Log.e(TAG, "Cannot initialize signaling client without user ID")
            return
        }
        
        Log.d(TAG, "Initializing signaling client for user: $userId")
        
        signalingClient = SignalingClient()
        SignalingClientSingleton.setInstance(signalingClient!!)
        
        signalingClient?.setListener(object : SignalingClient.SignalingListener {
            override fun onConnected() {
                runOnUiThread {
                    Log.d(TAG, "Connected to signaling server")
                    statusText.text = "Connected to server - Ready to receive calls"
                    Toast.makeText(this@MainActivity, "Connected! Ready to receive calls", Toast.LENGTH_SHORT).show()
                    signalingClient?.registerUser(userId)
                }
            }
            
            override fun onDisconnected() {
                runOnUiThread {
                    Log.d(TAG, "Disconnected from signaling server")
                    statusText.text = "Disconnected from server"
                }
            }
            
            override fun onUserRegistered(success: Boolean) {
                runOnUiThread {
                    if (success) {
                        Log.d(TAG, "User registered successfully")
                        statusText.text = "Ready to make/receive calls as: $userId"
                    } else {
                        Log.e(TAG, "User registration failed")
                        statusText.text = "Registration failed"
                    }
                }
            }
            
            override fun onIncomingCall(fromUserId: String, offer: String, iceCandidates: List<IceCandidate>) {
                Log.d(TAG, "SignalingClient.onIncomingCall: Received call from $fromUserId")
                runOnUiThread {
                    Log.d(TAG, "Processing incoming call from: $fromUserId")
                    Toast.makeText(this@MainActivity, "Incoming call from $fromUserId", Toast.LENGTH_SHORT).show()
                    showIncomingCall(fromUserId, offer, iceCandidates)
                }
            }
            
            override fun onCallAnswered(answer: String, iceCandidates: List<IceCandidate>) {
                runOnUiThread {
                    Log.d(TAG, "Call answered")
                    handleCallAnswered(answer, iceCandidates)
                }
            }
            
            override fun onCallDeclined(fromUserId: String) {
                runOnUiThread {
                    Log.d(TAG, "Call declined by: $fromUserId")
                    statusText.text = "$fromUserId declined the call"
                    Toast.makeText(this@MainActivity, "$fromUserId declined the call", Toast.LENGTH_SHORT).show()
                }
            }
            
            override fun onError(error: String) {
                runOnUiThread {
                    Log.e(TAG, "Signaling error: $error")
                    statusText.text = "Error: $error"
                    Toast.makeText(this@MainActivity, "Connection error: $error", Toast.LENGTH_SHORT).show()
                }
            }
        })
        
        signalingClient?.connect()
    }
    
    private fun createOfferForCall(targetUserId: String) {
        try {
            Log.d(TAG, "createOfferForCall: Starting call to $targetUserId")
            
            // Ensure WebRTC is initialized
            if (webRTCManager == null || webRTCManager?.isDisposed() == true) {
                Log.w(TAG, "WebRTC manager not ready, initializing...")
                statusText.text = "Initializing WebRTC..."
                initializeWebRTC()
                
                // Retry after a delay
                btnCallUser.postDelayed({
                    if (webRTCManager != null && !webRTCManager!!.isDisposed()) {
                        createOfferForCall(targetUserId)
                    } else {
                        statusText.text = "Failed to initialize WebRTC"
                    }
                }, 2000)
                return
            }
            
            isOfferer = true
            statusText.text = "Creating offer for $targetUserId..."
            
            webRTCManager?.let { manager ->
                Log.d(TAG, "Creating peer connection...")
                try {
                    manager.createPeerConnection()
                    Log.d(TAG, "Peer connection created, adding media tracks...")
                    manager.addMediaTracks()
                    Log.d(TAG, "Media tracks added, setting up listener...")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in WebRTC setup", e)
                    statusText.text = "WebRTC setup failed: ${e.message}"
                    return
                }
                
                // Set up listener for offer creation
                manager.setListener(object : WebRTCManager.WebRTCListener {
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                        Log.d(TAG, "ICE candidate generated for call")
                    }
                    
                    override fun onIceConnectionChange(newState: org.webrtc.PeerConnection.IceConnectionState) {
                        runOnUiThread {
                            Log.d(TAG, "ICE connection state: $newState")
                            when (newState) {
                                org.webrtc.PeerConnection.IceConnectionState.CONNECTED,
                                org.webrtc.PeerConnection.IceConnectionState.COMPLETED -> {
                                    statusText.text = "Connected to $targetUserId!"
                                    startVideoCall()
                                }
                                org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                                    statusText.text = "Connection failed"
                                }
                                else -> {
                                    statusText.text = "Connection: $newState"
                                }
                            }
                        }
                    }
                    
                    override fun onAddStream(stream: org.webrtc.MediaStream) {
                        Log.d(TAG, "Remote stream added")
                    }
                    
                    override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                        Log.d(TAG, "Remote stream removed")
                    }
                    
                    override fun onOfferCreated(sdp: org.webrtc.SessionDescription) {
                        try {
                            Log.d(TAG, "Offer created, sending to $targetUserId")
                            
                            // Send offer via signaling
                            val iceCandidates = manager.getCollectedIceCandidates()
                            Log.d(TAG, "Got ${iceCandidates.size} ICE candidates")
                            
                            signalingClient?.makeCall(targetUserId, sdp.description, iceCandidates)
                            
                            runOnUiThread {
                                statusText.text = "Offer sent to $targetUserId, waiting for answer..."
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error sending offer", e)
                            runOnUiThread {
                                statusText.text = "Failed to send offer: ${e.message}"
                            }
                        }
                    }
                    
                    override fun onAnswerCreated(sdp: org.webrtc.SessionDescription) {
                        // Not used when making a call
                    }
                })
                
                Log.d(TAG, "Calling createOffer()...")
                try {
                    manager.createOffer()
                    Log.d(TAG, "createOffer() called successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in createOffer()", e)
                    runOnUiThread {
                        statusText.text = "Failed to create offer: ${e.message}"
                        Toast.makeText(this@MainActivity, "Create offer failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating offer", e)
            runOnUiThread {
                statusText.text = "Failed to create call: ${e.message}"
                Toast.makeText(this, "Failed to create call: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Video Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming video calls"
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                
                // Phone call-like settings
                setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                
                // Long vibration pattern like phone calls
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000, 1000, 1000)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created successfully")
        }
    }
    
    private fun showIncomingCall(fromUserId: String, offer: String, iceCandidates: List<IceCandidate>) {
        try {
            Log.d(TAG, "showIncomingCall: Incoming call from $fromUserId")
            
            // Wake up the device using modern approach
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            val wakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "WebRTC:IncomingCall"
            )
            wakeLock.acquire(30000) // 30 seconds
            
            // Create intent for incoming call activity
            val intent = Intent(this, IncomingCallActivity::class.java).apply {
                putExtra(IncomingCallActivity.EXTRA_CALLER_ID, fromUserId)
                putExtra(IncomingCallActivity.EXTRA_OFFER, offer)
                // Note: ICE candidates would need proper serialization in a real app
                
                // Modern flags for incoming call display
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                       Intent.FLAG_ACTIVITY_CLEAR_TOP or
                       Intent.FLAG_ACTIVITY_SINGLE_TOP or
                       Intent.FLAG_ACTIVITY_NO_USER_ACTION
            }
            
            // Create decline broadcast intent
            val declineIntent = Intent(CallActionReceiver.ACTION_DECLINE_CALL).apply {
                setClass(this@MainActivity, CallActionReceiver::class.java)
                putExtra(IncomingCallActivity.EXTRA_CALLER_ID, fromUserId)
            }
            val declinePendingIntent = PendingIntent.getBroadcast(
                this, 1, declineIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Create accept broadcast intent  
            val acceptIntent = Intent(CallActionReceiver.ACTION_ACCEPT_CALL).apply {
                setClass(this@MainActivity, CallActionReceiver::class.java)
                putExtra(IncomingCallActivity.EXTRA_CALLER_ID, fromUserId)
                putExtra(IncomingCallActivity.EXTRA_OFFER, offer)
            }
            val acceptPendingIntent = PendingIntent.getBroadcast(
                this, 2, acceptIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Create full-screen intent for immediate display
            val fullScreenPendingIntent = PendingIntent.getActivity(
                this, 0, intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Show notification for background calls with call-like behavior
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Incoming Video Call")
                .setContentText("$fromUserId is calling you")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setTimeoutAfter(45000) // Auto-dismiss after 45 seconds
                .setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI)
                .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000, 1000, 1000))
                .addAction(android.R.drawable.sym_call_missed, "Decline", declinePendingIntent)
                .addAction(android.R.drawable.sym_call_incoming, "Accept", acceptPendingIntent)
                .setContentIntent(fullScreenPendingIntent) // Make notification clickable
                .setDeleteIntent(declinePendingIntent) // Auto decline when swiped away
                .build()
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Full-screen notification sent with ID: $NOTIFICATION_ID")
            
            // Force start the activity immediately for foreground users
            startActivity(intent)
            Log.d(TAG, "IncomingCallActivity started directly")
            
            // Release wake lock after a delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }, 30000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error showing incoming call", e)
            Toast.makeText(this, "Error showing incoming call: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    
    private fun handleCallAnswered(answer: String, iceCandidates: List<IceCandidate>) {
        try {
            webRTCManager?.let { manager ->
                val sessionDescription = org.webrtc.SessionDescription(
                    org.webrtc.SessionDescription.Type.ANSWER,
                    answer
                )
                manager.setRemoteDescription(sessionDescription)
                
                // Add ICE candidates
                iceCandidates.forEach { candidate ->
                    val webrtcCandidate = org.webrtc.IceCandidate(
                        candidate.sdpMid,
                        candidate.sdpMLineIndex,
                        candidate.candidate
                    )
                    manager.addIceCandidate(webrtcCandidate)
                }
                
                statusText.text = "Answer received, connecting..."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling call answer", e)
            statusText.text = "Failed to process answer"
        }
    }
    
    private fun testSimpleQR() {
        Log.d(TAG, "Testing simple QR generation")
        statusText.text = "Testing simple QR..."
        
        try {
            val simpleQR = generateSimpleQR("TEST123")
            if (simpleQR != null) {
                qrTitle.text = "Simple Test QR"
                qrCodeImage.setImageBitmap(simpleQR)
                qrContainer.visibility = View.VISIBLE
                statusText.text = "Simple QR works!"
            } else {
                statusText.text = "Simple QR returned null"
            }
        } catch (e: Exception) {
            statusText.text = "Simple QR failed: ${e.message}"
            Log.e(TAG, "Simple QR test failed", e)
        }
    }
    
    private fun testQRGeneration() {
        Log.d(TAG, "Testing QR generation without WebRTC")
        val testData = SignalingData(
            type = "test",
            sdp = "This is a test SDP",
            iceCandidates = emptyList()
        )
        
        try {
            val qrBitmap = QRCodeManager.generateQRCode(testData)
            if (qrBitmap != null) {
                Log.d(TAG, "Test QR generated successfully")
                qrTitle.text = "Test QR Code"
                qrCodeImage.setImageBitmap(qrBitmap)
                qrContainer.visibility = View.VISIBLE
                statusText.text = "Test QR code generated"
            } else {
                Log.e(TAG, "Test QR generation failed")
                statusText.text = "Test QR generation failed"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating test QR", e)
            statusText.text = "Error: ${e.message}"
        }
    }
    
    private fun checkPermissions() {
        try {
            Log.d(TAG, "checkPermissions: Starting permission check")
            val missingPermissions = REQUIRED_PERMISSIONS.filter {
                val granted = ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                Log.d(TAG, "Permission $it: ${if (granted) "GRANTED" else "MISSING"}")
                !granted
            }
            
            if (missingPermissions.isNotEmpty()) {
                Log.d(TAG, "Requesting permissions: ${missingPermissions.joinToString()}")
                statusText.text = "Requesting permissions..."
                permissionLauncher.launch(missingPermissions.toTypedArray())
            } else {
                Log.d(TAG, "All permissions granted, initializing WebRTC")
                statusText.text = "Permissions granted - initializing..."
                initializeWebRTC()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions", e)
            statusText.text = "Permission check failed: ${e.message}"
        }
    }
    
    private fun initializeWebRTC() {
        try {
            Log.d(TAG, "initializeWebRTC: Starting WebRTC initialization")
            statusText.text = "Initializing WebRTC..."
            
            webRTCManager = WebRTCManager(this)
            Log.d(TAG, "initializeWebRTC: WebRTCManager created")
            
            webRTCManager!!.initialize()
            Log.d(TAG, "initializeWebRTC: WebRTC initialized successfully")
            
            // Store in singleton for VideoCallActivity to use
            WebRTCManagerSingleton.setInstance(webRTCManager!!)
            Log.d(TAG, "initializeWebRTC: Singleton set")
            
            statusText.text = "WebRTC ready - you can start calls"
            
            webRTCManager?.setListener(object : WebRTCManager.WebRTCListener {
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                    Log.d(TAG, "onIceCandidate: New ICE candidate received: ${candidate.sdp}")
                    Log.d(TAG, "onIceCandidate: sdpMid=${candidate.sdpMid}, sdpMLineIndex=${candidate.sdpMLineIndex}")
                    // ICE candidates are collected in WebRTCManager and included in offers/answers
                }
                
                override fun onIceConnectionChange(newState: org.webrtc.PeerConnection.IceConnectionState) {
                    runOnUiThread {
                        Log.d(TAG, "ICE connection state changed to: $newState")
                        statusText.text = "Connection: $newState"
                        when (newState) {
                            org.webrtc.PeerConnection.IceConnectionState.CHECKING -> {
                                Log.d(TAG, "ICE checking - connection attempt in progress")
                                statusText.text = "Connecting..."
                                // Only hide QR if we're the offerer (answer QR should stay visible)
                                if (isOfferer) {
                                    hideQRCode()
                                }
                            }
                            org.webrtc.PeerConnection.IceConnectionState.CONNECTED,
                            org.webrtc.PeerConnection.IceConnectionState.COMPLETED -> {
                                Log.d(TAG, "WebRTC connection established!")
                                statusText.text = "Connected! Starting call..."
                                hideQRCode()
                                startVideoCall()
                            }
                            org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                                Log.e(TAG, "ICE connection failed - analyzing connectivity:")
                                webRTCManager?.let { manager ->
                                    val candidates = manager.getCollectedIceCandidates()
                                    Log.e(TAG, "Total ICE candidates collected: ${candidates.size}")
                                    
                                    val hostCount = candidates.count { it.candidate.contains("typ host") }
                                    val srflxCount = candidates.count { it.candidate.contains("typ srflx") }
                                    val relayCount = candidates.count { it.candidate.contains("typ relay") }
                                    
                                    Log.e(TAG, "Candidate analysis - Host: $hostCount, SRFLX: $srflxCount, RELAY: $relayCount")
                                    
                                    when {
                                        relayCount == 0 -> {
                                            statusText.text = "Connection failed: No TURN servers accessible. Both devices need same WiFi or mobile data."
                                        }
                                        srflxCount == 0 -> {
                                            statusText.text = "Connection failed: NAT detection failed. Check network settings."
                                        }
                                        else -> {
                                            statusText.text = "Connection failed: Network incompatible. Try same WiFi."
                                        }
                                    }
                                    
                                    candidates.forEachIndexed { index, candidate ->
                                        Log.e(TAG, "Candidate $index: ${candidate.candidate}")
                                    }
                                }
                                hideQRCode()
                            }
                            org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED -> {
                                Log.w(TAG, "ICE connection disconnected")
                                statusText.text = "Disconnected"
                                hideQRCode()
                            }
                            else -> {
                                Log.d(TAG, "ICE state: $newState")
                            }
                        }
                    }
                }
                
                override fun onAddStream(stream: org.webrtc.MediaStream) {
                    Log.d(TAG, "Stream added")
                }
                
                override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                    Log.d(TAG, "Stream removed")
                }
                
                override fun onOfferCreated(sdp: SessionDescription) {
                    Log.d(TAG, "onOfferCreated: Received offer callback")
                    runOnUiThread {
                        statusText.text = "Show this QR to Device B, then scan Device B's answer QR"
                        Log.d(TAG, "onOfferCreated: Calling showOfferQRCode")
                        showOfferQRCode(sdp)
                    }
                }
                
                override fun onAnswerCreated(sdp: SessionDescription) {
                    runOnUiThread {
                        statusText.text = "Show this answer QR to Device A"
                        // Show QR code immediately
                        showAnswerQRCode(sdp)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
            webRTCManager = null
            statusText.text = "WebRTC failed: ${e.message}"
            Toast.makeText(this, "WebRTC initialization failed. Check logs for details.", Toast.LENGTH_LONG).show()
            
            // Don't disable buttons - let them show error messages
            Log.e(TAG, "WebRTC initialization failed with exception: ${e.javaClass.simpleName}")
            Log.e(TAG, "Stack trace: ${e.stackTrace.joinToString("\n")}")
        }
    }
    
    private fun startCall() {
        try {
            Log.d(TAG, "startCall: Starting call as offerer")
            Log.d(TAG, "startCall: webRTCManager is ${if (webRTCManager != null) "NOT NULL" else "NULL"}")
            
            // Check if WebRTCManager is null or has been disposed
            if (webRTCManager == null || webRTCManager?.isDisposed() == true) {
                Log.e(TAG, "startCall: WebRTC manager is null or disposed - reinitializing")
                statusText.text = "WebRTC not initialized - reinitializing..."
                webRTCManager = null // Clear the reference
                initializeWebRTC()
                return
            }
            
            isOfferer = true
            statusText.text = "Starting call..."
            
            webRTCManager?.let { manager ->
                Log.d(TAG, "Creating peer connection...")
                manager.createPeerConnection()
                Log.d(TAG, "Adding media tracks...")
                manager.addMediaTracks()
                Log.d(TAG, "Creating offer...")
                manager.createOffer()
                
                // Add a timeout fallback in case the offer callback never comes
                btnStartCall.postDelayed({
                    if (qrContainer.visibility != View.VISIBLE) {
                        Log.w(TAG, "Offer callback didn't trigger, showing fallback QR")
                        showFallbackOfferQR()
                    }
                }, 5000) // 5 second timeout
                
                // No auto-timeout - wait for proper signaling
                
            } ?: run {
                Log.e(TAG, "WebRTC manager is null!")
                statusText.text = "WebRTC not initialized"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting call", e)
            statusText.text = "Failed to start call: ${e.message}"
        }
    }
    
    private fun scanQRCode() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt("Scan QR code from the other device")
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(true)
        options.setOrientationLocked(true) // Lock to portrait orientation
        options.setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity::class.java)
        qrScanLauncher.launch(options)
    }
    
    
    private fun handleQRCodeScanned(qrContent: String) {
        val signalingData = QRCodeManager.parseQRCodeData(qrContent)
        if (signalingData != null) {
            when (signalingData.type) {
                "offer" -> handleOfferReceived(signalingData)
                "answer" -> handleAnswerReceived(signalingData)
                else -> {
                    Toast.makeText(this, "Invalid QR code format", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // For POC: If QR parsing fails, just start the call anyway for testing
            Log.d(TAG, "QR parsing failed, starting test call for POC")
            statusText.text = "QR parsing failed - starting test call for POC"
            Toast.makeText(this, "QR parsing failed - starting test call", Toast.LENGTH_LONG).show()
            
            // Wait a moment then start call for testing
            btnStartCall.postDelayed({
                startVideoCall()
            }, 2000)
        }
    }
    
    private fun handleOfferReceived(offerData: SignalingData) {
        Log.d(TAG, "handleOfferReceived: Processing received offer")
        isOfferer = false
        statusText.text = "Processing offer..."
        
        try {
            webRTCManager?.let { manager ->
                Log.d(TAG, "handleOfferReceived: Creating peer connection")
                manager.createPeerConnection()
                
                Log.d(TAG, "handleOfferReceived: Adding media tracks")
                manager.addMediaTracks()
                
                Log.d(TAG, "handleOfferReceived: Setting remote description")
                val sessionDescription = SessionDescription(
                    SessionDescription.Type.OFFER,
                    offerData.sdp
                )
                manager.setRemoteDescription(sessionDescription)
                
                // Add ICE candidates from the offer
                Log.d(TAG, "handleOfferReceived: Adding ${offerData.iceCandidates.size} ICE candidates")
                offerData.iceCandidates.forEach { iceCandidate ->
                    Log.d(TAG, "handleOfferReceived: Adding ICE candidate: ${iceCandidate.candidate}")
                    val webrtcCandidate = org.webrtc.IceCandidate(
                        iceCandidate.sdpMid,
                        iceCandidate.sdpMLineIndex,
                        iceCandidate.candidate
                    )
                    manager.addIceCandidate(webrtcCandidate)
                }
                
                Log.d(TAG, "handleOfferReceived: Creating answer")
                manager.createAnswer()
                
                // No auto-timeout - let user manually proceed
                Log.d(TAG, "Answer created - waiting for Device A to scan")
                
            } ?: run {
                Log.e(TAG, "handleOfferReceived: WebRTC manager is null")
                statusText.text = "WebRTC not initialized"
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleOfferReceived: Error processing offer", e)
            statusText.text = "Error processing offer: ${e.message}"
        }
    }
    
    private fun handleAnswerReceived(answerData: SignalingData) {
        Log.d(TAG, "handleAnswerReceived: Received answer from peer")
        
        if (!isOfferer) {
            Log.w(TAG, "handleAnswerReceived: Not the offerer, ignoring answer")
            Toast.makeText(this, "Received answer but we're not the offerer", Toast.LENGTH_SHORT).show()
            return
        }
        
        statusText.text = "Processing answer..."
        
        try {
            webRTCManager?.let { manager ->
                Log.d(TAG, "handleAnswerReceived: Setting remote description (answer)")
                val sessionDescription = SessionDescription(
                    SessionDescription.Type.ANSWER,
                    answerData.sdp
                )
                manager.setRemoteDescription(sessionDescription)
                
                // Add ICE candidates from the answer
                Log.d(TAG, "handleAnswerReceived: Adding ${answerData.iceCandidates.size} ICE candidates")
                answerData.iceCandidates.forEach { iceCandidate ->
                    Log.d(TAG, "handleAnswerReceived: Adding ICE candidate: ${iceCandidate.candidate}")
                    val webrtcCandidate = org.webrtc.IceCandidate(
                        iceCandidate.sdpMid,
                        iceCandidate.sdpMLineIndex,
                        iceCandidate.candidate
                    )
                    manager.addIceCandidate(webrtcCandidate)
                }
                
                statusText.text = "Answer processed - waiting for connection..."
                
                // Connection should happen automatically through ICE callbacks
                Log.d(TAG, "Answer processed - waiting for ICE connection")
                
            } ?: run {
                Log.e(TAG, "handleAnswerReceived: WebRTC manager is null")
                statusText.text = "WebRTC manager error"
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAnswerReceived: Error processing answer", e)
            statusText.text = "Error processing answer: ${e.message}"
        }
        
        hideQRCode()
    }
    
    private fun showFallbackOfferQR() {
        Log.d(TAG, "Showing fallback offer QR code")
        
        // Try with working SignalingData (QR generation works, so let's use it)
        try {
            Log.d(TAG, "Creating fallback SignalingData QR")
            val fallbackData = SignalingData(
                type = "offer",
                sdp = "v=0\r\no=fallback 123456789 1 IN IP4 0.0.0.0\r\ns=Fallback WebRTC Call\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\na=sendrecv",
                iceCandidates = emptyList()
            )
            
            val qrBitmap = QRCodeManager.generateQRCode(fallbackData)
            if (qrBitmap != null) {
                Log.d(TAG, "Fallback offer QR generated successfully")
                qrTitle.text = getString(R.string.offer_qr_title)
                qrCodeImage.setImageBitmap(qrBitmap)
                qrContainer.visibility = View.VISIBLE
                statusText.text = "Fallback offer QR (WebRTC didn't respond)"
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback offer QR failed: ${e.message}", e)
        }
        
        // If simple QR fails, try minimal SignalingData
        try {
            Log.d(TAG, "Trying minimal SignalingData QR")
            val fallbackData = SignalingData(
                type = "test",
                sdp = "minimal",
                iceCandidates = emptyList()
            )
            
            val qrBitmap = QRCodeManager.generateQRCode(fallbackData)
            if (qrBitmap != null) {
                Log.d(TAG, "Fallback QR generated successfully")
                qrTitle.text = "Fallback Test QR"
                qrCodeImage.setImageBitmap(qrBitmap)
                qrContainer.visibility = View.VISIBLE
                statusText.text = "Fallback QR generated"
            } else {
                Log.e(TAG, "Fallback QR generation returned null")
                statusText.text = "QR generation returned null"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating fallback QR", e)
            statusText.text = "QR Error: ${e.message} - ${e.javaClass.simpleName}"
        }
    }
    
    private fun generateSimpleQR(text: String): android.graphics.Bitmap? {
        return try {
            val writer = com.google.zxing.qrcode.QRCodeWriter()
            val bitMatrix = writer.encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Simple QR generation failed", e)
            null
        }
    }
    
    private fun showOfferQRCode(sdp: SessionDescription) {
        Log.d(TAG, "showOfferQRCode: Starting QR generation")
        
        // Get collected ICE candidates from WebRTC manager
        val iceCandidates = webRTCManager?.getCollectedIceCandidates() ?: emptyList()
        Log.d(TAG, "showOfferQRCode: Including ${iceCandidates.size} ICE candidates in offer")
        
        val signalingData = SignalingData(
            type = "offer",
            sdp = sdp.description,
            iceCandidates = iceCandidates
        )
        Log.d(TAG, "showOfferQRCode: SignalingData created with SDP length: ${sdp.description.length}")
        
        try {
            Log.d(TAG, "Generating QR code for offer...")
            val qrBitmap = QRCodeManager.generateQRCode(signalingData)
            if (qrBitmap != null) {
                Log.d(TAG, "QR code generated successfully")
                qrTitle.text = getString(R.string.offer_qr_title)
                qrCodeImage.setImageBitmap(qrBitmap)
                qrContainer.visibility = View.VISIBLE
                statusText.text = getString(R.string.waiting_for_peer)
            } else {
                Log.e(TAG, "Failed to generate QR code bitmap")
                Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
                statusText.text = "QR code generation failed"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating QR code", e)
            statusText.text = "QR Error Details: ${e.message} (${e.javaClass.simpleName})"
        }
    }
    
    private fun showAnswerQRCode(sdp: SessionDescription) {
        Log.d(TAG, "showAnswerQRCode: Starting QR generation")
        
        // Get collected ICE candidates from WebRTC manager
        val iceCandidates = webRTCManager?.getCollectedIceCandidates() ?: emptyList()
        Log.d(TAG, "showAnswerQRCode: Including ${iceCandidates.size} ICE candidates in answer")
        
        val signalingData = SignalingData(
            type = "answer",
            sdp = sdp.description,
            iceCandidates = iceCandidates
        )
        
        val qrBitmap = QRCodeManager.generateQRCode(signalingData)
        if (qrBitmap != null) {
            qrTitle.text = getString(R.string.answer_qr_title)
            qrCodeImage.setImageBitmap(qrBitmap)
            qrContainer.visibility = View.VISIBLE
            statusText.text = "Show this QR to complete connection"
        } else {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun hideQRCode() {
        qrContainer.visibility = View.GONE
    }
    
    private fun startVideoCall() {
        val intent = Intent(this, VideoCallActivity::class.java)
        startActivity(intent)
        // Don't finish MainActivity so we can keep the WebRTC connection
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webRTCManager?.dispose()
        SignalingClientSingleton.clearInstance()
    }
}