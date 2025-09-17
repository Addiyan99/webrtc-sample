package com.example.webrtcqrdemo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import org.webrtc.SessionDescription
import android.app.NotificationManager
import org.webrtc.RtpTransceiver

class IncomingCallActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "IncomingCallActivity"
        const val EXTRA_CALLER_ID = "caller_id"
        const val EXTRA_OFFER = "offer"
        const val EXTRA_ICE_CANDIDATES = "ice_candidates"
    }
    
    private lateinit var tvCallerName: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnAccept: ImageView
    private lateinit var btnDecline: ImageView
    
    private var isAccepting = false
    
    private var callerUserId: String? = null
    private var offer: String? = null
    private var iceCandidates: List<IceCandidate>? = null
    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make sure the activity appears over lock screen and wakes the device
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            // For older APIs, use window flags
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        
        setContentView(R.layout.activity_incoming_call)
        
        initViews()
        extractIntentData()
        setupClickListeners()
        setupBackPressedHandler()
        handleActionFromNotification()
        
        // Get WebRTC manager and signaling client from singleton
        webRTCManager = WebRTCManagerSingleton.getInstance()
        signalingClient = SignalingClientSingleton.getInstance()
        
        Log.d(TAG, "Incoming call from: $callerUserId")
    }
    
    private fun initViews() {
        tvCallerName = findViewById(R.id.tvCallerName)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        btnAccept = findViewById(R.id.btnAccept)
        btnDecline = findViewById(R.id.btnDecline)
    }
    
    private fun extractIntentData() {
        callerUserId = intent.getStringExtra(EXTRA_CALLER_ID)
        offer = intent.getStringExtra(EXTRA_OFFER)
        
        // CRITICAL FIX: Properly extract ICE candidates
        val iceCandidatesJson = intent.getStringExtra(EXTRA_ICE_CANDIDATES)
        iceCandidates = if (!iceCandidatesJson.isNullOrEmpty()) {
            try {
                // Parse the JSON array of ICE candidates
                // This depends on how your SignalingClient serializes them
                parseIceCandidatesFromJson(iceCandidatesJson)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing ICE candidates: ${e.message}", e)
                emptyList()
            }
        } else {
            Log.w(TAG, "No ICE candidates received from caller!")
            emptyList()
        }
        
        Log.d(TAG, "Extracted ${iceCandidates?.size ?: 0} ICE candidates from intent")
        
        callerUserId?.let { callerId ->
            tvCallerName.text = callerId
        }
    }

    private fun parseIceCandidatesFromJson(jsonString: String): List<IceCandidate> {
        // This implementation depends on how your SignalingClient serializes ICE candidates
        // Example implementation:
        try {
            val jsonArray = org.json.JSONArray(jsonString)
            val candidates = mutableListOf<IceCandidate>()
            
            for (i in 0 until jsonArray.length()) {
                val candidateJson = jsonArray.getJSONObject(i)
                candidates.add(
                    IceCandidate(
                        candidate = candidateJson.getString("candidate"),
                        sdpMid = candidateJson.getString("sdpMid"),
                        sdpMLineIndex = candidateJson.getInt("sdpMLineIndex")
                    )
                )
            }
            
            Log.d(TAG, "Parsed ${candidates.size} ICE candidates from JSON")
            return candidates
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ICE candidates JSON", e)
            return emptyList()
        }
    }
    
    private fun setupClickListeners() {
        btnAccept.setOnClickListener {
            acceptCall()
        }
        
        btnDecline.setOnClickListener {
            declineCall()
        }
    }
    
    private fun acceptCall() {
        if (isAccepting) {
            Log.d(TAG, "Already accepting call, ignoring duplicate click")
            return
        }
        isAccepting = true
        
        Log.d(TAG, "Call accepted by user")
        
        // Clear the incoming call notification
        clearIncomingCallNotification()
        
        val offer = this.offer
        val callerId = this.callerUserId
        
        if (offer == null || callerId == null) {
            Log.e(TAG, "Missing offer or caller ID")
            Toast.makeText(this, "Invalid call data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Create the VideoCallActivity's remote video view early
        val intent = Intent(this, VideoCallActivity::class.java)
        // Set a flag so VideoCallActivity knows to set up remote sink immediately
        intent.putExtra("setup_remote_sink_immediately", true)
        
        try {
            webRTCManager?.let { manager ->
                Log.d(TAG, "Setting up WebRTC for incoming call")
                
                val dummyVideoView = org.webrtc.SurfaceViewRenderer(this)
                manager.setupRemoteVideoView(dummyVideoView)
                
                // Create peer connection
                manager.createPeerConnection()
                manager.addMediaTracks()

                // Set up listener for answer creation
                manager.setListener(object : WebRTCManager.WebRTCListener {
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                        Log.d(TAG, "ICE candidate generated")
                    }
                    
                    override fun onIceConnectionChange(newState: org.webrtc.PeerConnection.IceConnectionState) {
                        runOnUiThread {
                            tvConnectionStatus.text = "Connection: $newState"
                            tvConnectionStatus.visibility = android.view.View.VISIBLE
                            
                            Log.d(TAG, "ICE connection state changed to: $newState")
                            
                            when (newState) {
                                org.webrtc.PeerConnection.IceConnectionState.CHECKING -> {
                                    Log.d(TAG, "ICE checking - connection in progress...")
                                }
                                org.webrtc.PeerConnection.IceConnectionState.CONNECTED,
                                org.webrtc.PeerConnection.IceConnectionState.COMPLETED -> {
                                    Log.d(TAG, "Call connected successfully")
                                    // Add small delay to ensure connection is stable
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        startVideoCall()
                                    }, 100)
                                }
                                org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                                    Log.e(TAG, "Call connection failed")
                                    Toast.makeText(this@IncomingCallActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED -> {
                                    Log.w(TAG, "Call connection disconnected")
                                    tvConnectionStatus.text = "Connection lost, retrying..."
                                }
                                else -> {
                                    Log.d(TAG, "Connection state: $newState")
                                }
                            }
                        }
                    }
                    
                    override fun onTrack(transceiver: RtpTransceiver) {
                        Log.d(TAG, "Remote track added")
                    }

                    override fun onAddStream(stream: org.webrtc.MediaStream) {
                        Log.d(TAG, "Remote stream added")
                    }
                    
                    override fun onRemoveStream(stream: org.webrtc.MediaStream) {
                        Log.d(TAG, "Remote stream removed")
                    }
                    
                    override fun onOfferCreated(sdp: SessionDescription) {
                        // Not used in incoming call
                    }
                    
                    override fun onAnswerCreated(sdp: SessionDescription) {
                        Log.d(TAG, "Answer created, sending to caller")
                        
                        // Send answer back to caller via signaling
                        val iceCandidates = manager.getCollectedIceCandidates()
                        signalingClient?.answerCall(callerId, sdp.description, iceCandidates)
                        
                        runOnUiThread {
                            tvConnectionStatus.text = "Answer sent, waiting for connection..."
                            tvConnectionStatus.visibility = android.view.View.VISIBLE
                        }
                    }
                })
                
                // Set remote description (offer)
                val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offer)
                manager.setRemoteDescription(sessionDescription) {
                    Log.d(TAG, "✅ Remote description set. Now adding received ICE candidates.")
                    
                    // Apply candidates from the signaling payload
                    iceCandidates?.forEach { candidate ->
                        manager.addIceCandidate(
                            org.webrtc.IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
                        )
                    }
                    
                    // IMPORTANT: Add a small delay to ensure candidates are processed
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        manager.createAnswer()
                    }, 300) // 100ms delay
                }
            } ?: run {
                Log.e(TAG, "WebRTC manager not available")
                Toast.makeText(this, "WebRTC not available", Toast.LENGTH_SHORT).show()
                finish()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error accepting call", e)
            Toast.makeText(this, "Failed to accept call: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun declineCall() {
        Log.d(TAG, "Call declined by user")
        
        // Clear the incoming call notification
        clearIncomingCallNotification()
        
        callerUserId?.let { callerId ->
            signalingClient?.declineCall(callerId)
        }
        
        Toast.makeText(this, "Call declined", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun startVideoCall() {
        val intent = Intent(this, VideoCallActivity::class.java)
        startActivity(intent)
        finish()
    }
    
    private fun clearIncomingCallNotification() {
        try {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(1001) // Same ID used in MainActivity for incoming call notification
            Log.d(TAG, "Incoming call notification cleared (ID: 1001)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear notification", e)
        }
    }
    
    private fun setupBackPressedHandler() {
        // Handle back button press using the modern approach
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Prevent back button - user must explicitly accept or decline
                // Treat back press as decline
                declineCall()
            }
        })
    }
    
    private fun handleActionFromNotification() {
        val action = intent.getStringExtra("action")
        when (action) {
            "accept" -> {
                Log.d(TAG, "Auto-accepting call from notification")
                acceptCall()
            }
            "decline" -> {
                Log.d(TAG, "Auto-declining call from notification")
                declineCall()
            }
            else -> {
                Log.d(TAG, "No action specified, showing call UI")
            }
        }
    }
}