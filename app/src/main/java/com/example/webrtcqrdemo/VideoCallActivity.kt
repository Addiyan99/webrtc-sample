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
import android.view.View
import org.webrtc.RtpReceiver
import org.webrtc.VideoTrack
import org.webrtc.RtpTransceiver

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
    private var isRemoteVideoViewInitialized = false
    
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
        
        // Proper z-ordering for video views
        localVideoView.clipToOutline = true
        localVideoView.setZOrderMediaOverlay(true) // Local video on top
        remoteVideoView.setZOrderMediaOverlay(false) // Remote video in background
        remoteVideoView.setEnableHardwareScaler(true)
        remoteVideoView.setMirror(false)
        localVideoView.setMirror(true) // Mirror local video for user
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
            Log.e(TAG, "No WebRTC manager available from MainActivity")
            statusText.text = "WebRTC connection error"
            return
        }
        
        Log.d(TAG, "Using existing WebRTC manager")
        webRTCManager?.let { manager ->
            // CRITICAL FIX: Initialize remote video view with proper error handling and retry
            try {
                val eglContext = manager.getEglBaseContext()
                if (eglContext != null) {
                    Log.d(TAG, "Initializing remote video view with EGL context")
                    
                    // ENHANCED: Force initialization on main thread with multiple attempts
                    runOnUiThread {
                        try {
                            // Ensure the view is ready
                            remoteVideoView.visibility = View.VISIBLE
                            remoteVideoView.setZOrderMediaOverlay(false)
                            remoteVideoView.setEnableHardwareScaler(true)
                            remoteVideoView.setMirror(false)
                            
                            // Initialize with shared EGL context
                            remoteVideoView.init(eglContext, null)
                            isRemoteVideoViewInitialized = true
                            
                            Log.d(TAG, "✅ Remote video view initialized successfully")
                            
                            // CRITICAL: Setup the remote video view in the manager AFTER successful init
                            manager.setupRemoteVideoView(remoteVideoView)
                            Log.d(TAG, "✅ Remote video view registered with manager")
                            
                            // Force layout to ensure view is ready for rendering
                            remoteVideoView.requestLayout()
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error initializing remote video view", e)
                            isRemoteVideoViewInitialized = false
                            
                            // RETRY MECHANISM: Try again after a short delay
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                Log.d(TAG, "🔄 Retrying remote video view initialization")
                                try {
                                    remoteVideoView.init(eglContext, null)
                                    manager.setupRemoteVideoView(remoteVideoView)
                                    isRemoteVideoViewInitialized = true
                                    Log.d(TAG, "✅ Remote video view initialized on retry")
                                } catch (retryException: Exception) {
                                    Log.e(TAG, "❌ Retry failed for remote video view", retryException)
                                }
                            }, 500)
                        }
                    }
                    
                } else {
                    Log.e(TAG, "❌ EGL context is null!")
                    statusText.text = "Video initialization error"
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error getting EGL context", e)
                isRemoteVideoViewInitialized = false
                return
            }
            
            videoCallListener = object : WebRTCManager.WebRTCListener {
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                    // ICE candidates are handled in MainActivity during signaling
                }
                
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    runOnUiThread {
                        Log.d(TAG, "ICE Connection State: $newState")
                        statusText.text = when (newState) {
                            PeerConnection.IceConnectionState.CONNECTED -> "Connected - setting up video..."
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

                override fun onTrack(transceiver: RtpTransceiver) {
                    runOnUiThread {
                        Log.d(TAG, "onTrack called in VideoCallActivity")

                        val track = transceiver.receiver?.track()
                        Log.d(TAG, "Track details - id: ${track?.id()}, kind: ${track?.kind()}, enabled: ${track?.enabled()}")
                        
                        if (track is VideoTrack) {
                            Log.d(TAG, "🎥 Received remote video track: ${track.id()}")

                            // ENHANCED: Wait for proper initialization before adding track
                            if (isRemoteVideoViewInitialized) {
                                try {
                                    Log.d(TAG, "🎥 Adding remote video track to initialized view")
                                    track.addSink(remoteVideoView)
                                    
                                    // CRITICAL: Ensure proper view state for rendering
                                    remoteVideoView.visibility = View.VISIBLE
                                    remoteVideoView.bringToFront()
                                    
                                    // Force layout and rendering
                                    remoteVideoView.requestLayout()
                                    
                                    statusText.text = "✅ Remote video connected!"
                                    Log.d(TAG, "✅ Remote video track added successfully")
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error adding remote video track", e)
                                    statusText.text = "Remote video error: ${e.message}"
                                    
                                    // RETRY: Try to reinitialize and add track
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        Log.d(TAG, "🔄 Retrying video track connection")
                                        try {
                                            val eglContext = manager.getEglBaseContext()
                                            if (eglContext != null && !isRemoteVideoViewInitialized) {
                                                remoteVideoView.init(eglContext, null)
                                                isRemoteVideoViewInitialized = true
                                            }
                                            track.addSink(remoteVideoView)
                                            statusText.text = "✅ Remote video connected (retry)!"
                                            Log.d(TAG, "✅ Remote video track added on retry")
                                        } catch (retryError: Exception) {
                                            Log.e(TAG, "❌ Retry failed", retryError)
                                        }
                                    }, 1000)
                                }
                            } else {
                                Log.w(TAG, "⚠️ Remote video view not initialized, scheduling retry")
                                
                                // ENHANCED RETRY: Multiple attempts with increasing delays
                                val maxRetries = 5
                                var retryCount = 0
                                
                                fun retryAddTrack() {
                                    retryCount++
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        if (isRemoteVideoViewInitialized) {
                                            try {
                                                Log.d(TAG, "🎥 Retry $retryCount: Adding video track")
                                                track.addSink(remoteVideoView)
                                                remoteVideoView.visibility = View.VISIBLE
                                                statusText.text = "✅ Remote video connected (retry $retryCount)!"
                                                Log.d(TAG, "✅ Remote video track added on retry $retryCount")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "❌ Retry $retryCount failed", e)
                                                if (retryCount < maxRetries) {
                                                    retryAddTrack()
                                                }
                                            }
                                        } else {
                                            Log.w(TAG, "⚠️ View still not initialized, retry $retryCount")
                                            if (retryCount < maxRetries) {
                                                // Try to reinitialize the view
                                                try {
                                                    val eglContext = manager.getEglBaseContext()
                                                    if (eglContext != null) {
                                                        remoteVideoView.init(eglContext, null)
                                                        isRemoteVideoViewInitialized = true
                                                        Log.d(TAG, "🔄 Reinitialized view on retry $retryCount")
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "❌ View reinitialization failed", e)
                                                }
                                                retryAddTrack()
                                            }
                                        }
                                    }, retryCount * 300L) // Increasing delay: 300ms, 600ms, 900ms, etc.
                                }
                                
                                retryAddTrack()
                            }
                        } else if (track != null) {
                            Log.d(TAG, "🎵 Received remote audio track: ${track.id()}")
                        }
                    }
                }
                
                override fun onAddStream(stream: MediaStream) {
                    runOnUiThread {
                        Log.d(TAG, "🌊 Remote stream added: ${stream.id}")
                        
                        // Handle video tracks in the stream
                        if (stream.videoTracks.size > 0) {
                            Log.d(TAG, "Stream has ${stream.videoTracks.size} video tracks")
                            val videoTrack = stream.videoTracks[0]
                            
                            // Use same enhanced retry logic as onTrack
                            if (isRemoteVideoViewInitialized) {
                                try {
                                    Log.d(TAG, "🎥 Adding video track from stream")
                                    videoTrack.addSink(remoteVideoView)
                                    remoteVideoView.visibility = View.VISIBLE
                                    remoteVideoView.requestLayout()
                                    statusText.text = "✅ Remote video from stream connected!"
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error adding video track from stream", e)
                                }
                            } else {
                                Log.w(TAG, "⚠️ Remote video view not ready for stream")
                            }
                        }
                        
                        // Handle audio tracks
                        if (stream.audioTracks.size > 0) {
                            Log.d(TAG, "Stream has ${stream.audioTracks.size} audio tracks")
                        }
                    }
                }
                
                override fun onRemoveStream(stream: MediaStream) {
                    Log.d(TAG, "Remote stream removed: ${stream.id}")
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
            
            // Set up local video capture
            try {
                Log.d(TAG, "Setting up local video capture")
                manager.startLocalVideoCapture(localVideoView)
                Log.d(TAG, "Local video capture started successfully")
                statusText.text = "Video setup complete - connecting remote video..."
                
                // Set up voice detection
                manager.setAudioLevelCallback { localActive, remoteActive ->
                    showVoiceIndicator(true, localActive && !isMuted)
                    showVoiceIndicator(false, remoteActive)
                }
                Log.d(TAG, "Voice detection setup complete")
                
                // EXTENDED TIMEOUT: Give more time for answer side remote video
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (statusText.text.contains("connecting remote video") || 
                        statusText.text.contains("waiting for remote video")) {
                        statusText.text = "Connected - remote video should appear soon"
                        Log.w(TAG, "⚠️ Remote video still connecting after 8 seconds")
                    }
                }, 8000) // Extended to 8 seconds for answer side
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting local video", e)
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
            } else {
                btnMute.setImageResource(android.R.drawable.ic_btn_speak_now)
                btnMute.setColorFilter(resources.getColor(R.color.white, null))
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
                localVideoView.visibility = View.VISIBLE
            } else {
                btnVideo.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                btnVideo.setColorFilter(resources.getColor(R.color.error, null))
                localVideoView.visibility = View.GONE
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
                    if (indicator.visibility != View.VISIBLE) {
                        indicator.visibility = View.VISIBLE
                        indicator.alpha = 0.0f
                        // Smooth fade in
                        indicator.animate()
                            .alpha(1.0f)
                            .setDuration(200)
                            .start()
                    }
                } else {
                    if (indicator.visibility == View.VISIBLE) {
                        // Smooth fade out
                        indicator.animate()
                            .alpha(0.0f)
                            .setDuration(200)
                            .withEndAction {
                                indicator.visibility = View.GONE
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
        Log.d(TAG, "onDestroy called")
        
        // Remove our listener
        videoCallListener?.let { listener ->
            webRTCManager?.removeListener(listener)
        }
        
        // Clear references but don't dispose here since endCall() handles it
        webRTCManager = null
        videoCallListener = null
        
        // Clean up video views
        try {
            if (isRemoteVideoViewInitialized) {
                remoteVideoView.release()
                isRemoteVideoViewInitialized = false
            }
            localVideoView.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video views", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause called")
        // SurfaceViewRenderer lifecycle is handled automatically
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")
        // SurfaceViewRenderer lifecycle is handled automatically
    }
}