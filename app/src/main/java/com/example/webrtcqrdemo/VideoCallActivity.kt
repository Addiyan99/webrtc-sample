package com.example.webrtcqrdemo

import android.os.Bundle
import android.util.Log
import android.widget.Button
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
    private lateinit var btnEndCall: Button
    private lateinit var statusText: TextView
    
    private var webRTCManager: WebRTCManager? = null
    private var videoCallListener: WebRTCManager.WebRTCListener? = null
    
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
        statusText = findViewById(R.id.statusText)
    }
    
    private fun setupClickListeners() {
        btnEndCall.setOnClickListener {
            endCall()
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
                                // Initialize remote video view with a fresh EglBase
                                val eglBase = org.webrtc.EglBase.create()
                                remoteVideoView.init(eglBase.eglBaseContext, null)
                                Log.d(TAG, "VideoCallActivity: Remote video view initialized")
                                
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
            } catch (e: Exception) {
                Log.e("VideoCallActivity", "Error starting local video", e)
                statusText.text = "Local video error: ${e.message}"
            }
        }
    }
    
    private fun endCall() {
        webRTCManager?.dispose()
        WebRTCManagerSingleton.clearInstance()
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Remove our listener
        videoCallListener?.let { listener ->
            webRTCManager?.removeListener(listener)
        }
        // Don't dispose here since MainActivity manages the connection
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