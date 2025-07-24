package com.example.webrtcqrdemo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import org.webrtc.SessionDescription
import android.app.NotificationManager

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
        
        // For now, we'll handle ICE candidates through the WebRTC manager
        // In a real implementation, you'd serialize/deserialize the candidates
        
        callerUserId?.let { callerId ->
            tvCallerName.text = callerId
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
        
        try {
            webRTCManager?.let { manager ->
                Log.d(TAG, "Setting up WebRTC for incoming call")
                
                // Create peer connection
                manager.createPeerConnection()
                manager.addMediaTracks()
                
                // Set remote description (offer)
                val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offer)
                manager.setRemoteDescription(sessionDescription)
                
                // Set up listener for answer creation
                manager.setListener(object : WebRTCManager.WebRTCListener {
                    override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                        Log.d(TAG, "ICE candidate generated")
                    }
                    
                    override fun onIceConnectionChange(newState: org.webrtc.PeerConnection.IceConnectionState) {
                        runOnUiThread {
                            tvConnectionStatus.text = "Connection: $newState"
                            tvConnectionStatus.visibility = android.view.View.VISIBLE
                            
                            when (newState) {
                                org.webrtc.PeerConnection.IceConnectionState.CONNECTED,
                                org.webrtc.PeerConnection.IceConnectionState.COMPLETED -> {
                                    Log.d(TAG, "Call connected successfully")
                                    startVideoCall()
                                }
                                org.webrtc.PeerConnection.IceConnectionState.FAILED -> {
                                    Log.e(TAG, "Call connection failed")
                                    Toast.makeText(this@IncomingCallActivity, "Connection failed", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                                else -> {
                                    Log.d(TAG, "Connection state: $newState")
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
                
                // Create answer
                manager.createAnswer()
                
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