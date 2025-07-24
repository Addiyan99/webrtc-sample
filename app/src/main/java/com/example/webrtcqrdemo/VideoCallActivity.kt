package com.example.webrtcqrdemo

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer

class VideoCallActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "VideoCallActivity"
    }
    
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var btnEndCall: ImageView
    private lateinit var btnMute: ImageView
    private lateinit var btnVideo: ImageView
    private lateinit var btnSwitchCamera: ImageView
    private lateinit var statusText: TextView
    private lateinit var localVoiceIndicator: ImageView
    private lateinit var remoteVoiceIndicator: ImageView
    
    private var webRTCManager: WebRTCManager? = null
    private var videoCallListener: WebRTCManager.WebRTCListener? = null
    
    // State variables
    private var isMuted = false
    private var isVideoEnabled = true
    private var isUsingFrontCamera = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_call)
        
        initViews()
        setupWebRTC()
        setupClickListeners()
    }
    
    private fun initViews() {
        localVideoView = findViewById(R.id.localVideoView)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        btnEndCall = findViewById(R.id.btnEndCall)
        btnMute = findViewById(R.id.btnMute)
        btnVideo = findViewById(R.id.btnVideo)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        statusText = findViewById(R.id.statusText)
        localVoiceIndicator = findViewById(R.id.localVoiceIndicator)
        remoteVoiceIndicator = findViewById(R.id.remoteVoiceIndicator)
        
        // Ensure proper z-ordering for video views
        localVideoView.clipToOutline = true
        remoteVideoView.setZOrderMediaOverlay(false)
    }
    
    private fun setupClickListeners() {
        btnEndCall.setOnClickListener {
            endCall()
        }
        
        btnMute.setOnClickListener {
            toggleMute()
        }
        
        btnVideo.setOnClickListener {
            toggleVideo()
        }
        
        btnSwitchCamera.setOnClickListener {
            switchCamera()
        }
    }
    
    private fun setupWebRTC() {
        // Use the existing WebRTC manager from MainActivity
        webRTCManager = WebRTCManagerSingleton.getInstance()
        
        if (webRTCManager == null) {
            Log.e("VideoCallActivity", "No WebRTC manager available from MainActivity")
            statusText.text = "WebRTC connection error"
            return
        }
        
        Log.d("VideoCallActivity", "Using existing WebRTC manager")
        webRTCManager?.let { manager ->
            videoCallListener = object : WebRTCManager.WebRTCListener {
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                    // ICE candidates are handled in MainActivity during signaling
                }
                
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    runOnUiThread {
                        Log.d(TAG, "ICE Connection State: $newState")
                        statusText.text = when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED -> "Connected - waiting for video..."
                            PeerConnection.IceConnectionState.COMPLETED -> "Connection complete"
                            PeerConnection.IceConnectionState.DISCONNECTED -> "Disconnected"
                            PeerConnection.IceConnectionState.FAILED -> "Connection Failed"
                            PeerConnection.IceConnectionState.CHECKING -> "Connecting..."
                            PeerConnection.IceConnectionState.NEW -> "Initializing..."
                            else -> newState.toString()
                        }
                        
                        if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                            newState == PeerConnection.IceConnectionState.FAILED) {
                            Log.e(TAG, "Connection failed/disconnected, finishing activity")
                            finish()
                        }
                    }
                }
                
                override fun onAddStream(stream: MediaStream) {
                    runOnUiThread {
                        Log.d(TAG, "VideoCallActivity: Remote stream added: ${stream.id}")
                        Log.d(TAG, "VideoCallActivity: Video tracks: ${stream.videoTracks.size}, Audio tracks: ${stream.audioTracks.size}")
                        
                        if (stream.videoTracks.isNotEmpty()) {
                            val remoteVideoTrack = stream.videoTracks[0]
                            Log.d(TAG, "VideoCallActivity: Adding remote video track to remoteVideoView")
                            
                            try {
                                // Initialize remote video view with shared EglBase context
                                val eglContext = manager.getEglBaseContext()
                                remoteVideoView.init(eglContext, null)
                                Log.d(TAG, "VideoCallActivity: Remote video view initialized with shared context")
                                
                                // Add the video track to the view
                                remoteVideoTrack.addSink(remoteVideoView)
                                statusText.text = "Remote video connected!"
                                Log.d(TAG, "VideoCallActivity: Remote video track added to sink successfully")
                                
                                // Make sure the view is visible
                                remoteVideoView.visibility = android.view.View.VISIBLE
                                Log.d(TAG, "VideoCallActivity: Remote video view set to visible")
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "VideoCallActivity: Error setting up remote video", e)
                                statusText.text = "Remote video error: ${e.message}"
                            }
                        } else {
                            Log.w(TAG, "VideoCallActivity: Remote stream has no video tracks")
                            statusText.text = "Remote audio only"
                        }
                    }
                }
                
                override fun onRemoveStream(stream: MediaStream) {
                    runOnUiThread {
                        Log.d(TAG, "Remote stream removed")
                    }
                }
                
                override fun onOfferCreated(sdp: SessionDescription) {
                    // Not used in this activity
                }
                
                override fun onAnswerCreated(sdp: SessionDescription) {
                    // Not used in this activity
                }
            }
            
            // Add the listener to the manager
            manager.addListener(videoCallListener!!)
            
            // Use the shared WebRTC manager's video setup
            Log.d("VideoCallActivity", "Setting up video with shared WebRTC manager")
            statusText.text = "Connected - waiting for video..."
            
            // Set up local video capture in VideoCallActivity where the views exist
            try {
                manager.startLocalVideoCapture(localVideoView)
                Log.d("VideoCallActivity", "Local video capture started")
                statusText.text = "Video setup complete"
                
                // Set up voice detection
                manager.setAudioLevelCallback { localActive, remoteActive ->
                    showVoiceIndicator(true, localActive && !isMuted)
                    showVoiceIndicator(false, remoteActive)
                }
                Log.d("VideoCallActivity", "Voice detection setup complete")
            } catch (e: Exception) {
                Log.e("VideoCallActivity", "Error starting local video", e)
                statusText.text = "Local video error: ${e.message}"
            }
        }
    }
    
    private fun toggleMute() {
        try {
            isMuted = !isMuted
            webRTCManager?.setAudioEnabled(!isMuted)
            
            // Update UI
            if (isMuted) {
                btnMute.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                btnMute.setColorFilter(resources.getColor(R.color.error, null))
                statusText.text = "Microphone muted"
            } else {
                btnMute.setImageResource(android.R.drawable.ic_btn_speak_now)
                btnMute.setColorFilter(resources.getColor(R.color.white, null))
                statusText.text = "Connected"
            }
            
            Log.d(TAG, "Mute toggled: $isMuted")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling mute", e)
        }
    }
    
    private fun toggleVideo() {
        try {
            isVideoEnabled = !isVideoEnabled
            webRTCManager?.setVideoEnabled(isVideoEnabled)
            
            // Update UI
            if (isVideoEnabled) {
                btnVideo.setImageResource(android.R.drawable.ic_menu_camera)
                btnVideo.setColorFilter(resources.getColor(R.color.white, null))
                localVideoView.visibility = android.view.View.VISIBLE
            } else {
                btnVideo.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                btnVideo.setColorFilter(resources.getColor(R.color.error, null))
                localVideoView.visibility = android.view.View.GONE
            }
            
            Log.d(TAG, "Video toggled: $isVideoEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling video", e)
        }
    }
    
    private fun switchCamera() {
        try {
            webRTCManager?.switchCamera()
            isUsingFrontCamera = !isUsingFrontCamera
            
            // Provide visual feedback
            btnSwitchCamera.alpha = 0.5f
            btnSwitchCamera.animate().alpha(1.0f).setDuration(200).start()
            
            Log.d(TAG, "Camera switched to: ${if (isUsingFrontCamera) "front" else "back"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error switching camera", e)
        }
    }
    
    private fun showVoiceIndicator(isLocal: Boolean, isActive: Boolean) {
        runOnUiThread {
            try {
                val indicator = if (isLocal) localVoiceIndicator else remoteVoiceIndicator
                
                if (isActive) {
                    if (indicator.visibility != android.view.View.VISIBLE) {
                        indicator.visibility = android.view.View.VISIBLE
                        indicator.alpha = 0.0f
                        // Smooth fade in
                        indicator.animate()
                            .alpha(1.0f)
                            .setDuration(200)
                            .start()
                    }
                } else {
                    if (indicator.visibility == android.view.View.VISIBLE) {
                        // Smooth fade out
                        indicator.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction {
                                indicator.visibility = android.view.View.GONE
                            }
                            .start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating voice indicator", e)
            }
        }
    }

    private fun endCall() {
        try {
            Log.d(TAG, "endCall: Ending video call")
            statusText.text = "Call ending..."
            
            // Safely dispose WebRTC resources
            webRTCManager?.let { manager ->
                if (!manager.isDisposed()) {
                    Log.d(TAG, "endCall: Disposing WebRTC manager")
                    // Post disposal to background thread to avoid blocking UI
                    Thread {
                        try {
                            manager.dispose()
                            Log.d(TAG, "endCall: WebRTC manager disposed successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "endCall: Error disposing WebRTC manager", e)
                        }
                    }.start()
                } else {
                    Log.d(TAG, "endCall: WebRTC manager already disposed")
                }
            }
            
            // Wait a moment for cleanup to complete, then finish
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "endCall: Finishing activity")
                finish()
            }, 300)
            
        } catch (e: Exception) {
            Log.e(TAG, "endCall: Error ending call", e)
            // Still finish the activity even if cleanup fails
            finish()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Remove our listener
        videoCallListener?.let { listener ->
            webRTCManager?.removeListener(listener)
        }
        // Clear references but don't dispose here since endCall() handles it
        webRTCManager = null
        videoCallListener = null
    }
    
    override fun onPause() {
        super.onPause()
        // SurfaceViewRenderer doesn't have onPause/onResume methods
        // The lifecycle is handled automatically
    }
    
    override fun onResume() {
        super.onResume()
        // SurfaceViewRenderer doesn't have onPause/onResume methods
        // The lifecycle is handled automatically
    }
}