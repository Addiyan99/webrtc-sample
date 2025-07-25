package com.example.webrtcqrdemo

import android.content.Context
import android.util.Log
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.webrtc.RtpTransceiver
import org.webrtc.SurfaceViewRenderer

class WebRTCManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WebRTCManager"
        private val STUN_SERVERS = listOf(
            PeerConnection.IceServer.builder(
                listOf(
                    "stun:bn-turn2.xirsys.com",
                    "turn:bn-turn2.xirsys.com:80?transport=udp",
                    "turn:bn-turn2.xirsys.com:3478?transport=udp",
                    "turn:bn-turn2.xirsys.com:80?transport=tcp",
                    "turn:bn-turn2.xirsys.com:3478?transport=tcp",
                    "turns:bn-turn2.xirsys.com:443?transport=tcp",
                    "turns:bn-turn2.xirsys.com:5349?transport=tcp"
                )
            )
            .setUsername("U3_C1ao-jRQeG-ZdydmBPl9Le5d3f8IFbUFEhrLpEVpGOv4kIJd8-UAxjGJUEXFJAAAAAGiB30JhZGRpeWFu")
            .setPassword("fcd9c00a-685e-11f0-9a26-0242ac140004")
            .createIceServer()
        )
    }

    private val queuedRemoteCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false
    
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localVideoSink: VideoSink? = null
    private var remoteVideoSink: VideoSink? = null
    private var eglBase: EglBase? = null
    
    // Voice detection
    private var voiceDetectionHandler: android.os.Handler? = null
    private var voiceDetectionRunnable: Runnable? = null
    private var audioLevelCallback: ((Boolean, Boolean) -> Unit)? = null
    private var localVoiceSimulationState = false
    private var remoteVoiceSimulationState = false
    
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val collectedIceCandidates = mutableListOf<org.webrtc.IceCandidate>()
    private var isDisposed = false
    
    interface WebRTCListener {
        fun onIceCandidate(candidate: org.webrtc.IceCandidate)
        fun onIceConnectionChange(newState: PeerConnection.IceConnectionState)
        fun onTrack(transceiver: RtpTransceiver)
        fun onAddStream(stream: MediaStream)
        fun onRemoveStream(stream: MediaStream)
        fun onOfferCreated(sdp: SessionDescription)
        fun onAnswerCreated(sdp: SessionDescription)
    }
    
    private val listeners = mutableListOf<WebRTCListener>()
    
    fun initialize() {
        try {
            Log.d(TAG, "initialize: Starting WebRTC initialization")
            
            // Initialize on main thread to avoid JNI issues
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                Log.w(TAG, "WebRTC initialization called from background thread, switching to main thread")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    initialize()
                }
                return
            }
            
            // Create shared EglBase context for all video rendering
            if (eglBase == null) {
                eglBase = EglBase.create()
                Log.d(TAG, "Created shared EglBase context")
            }
            
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false) // Disable internal tracer to reduce overhead
                .setFieldTrials("") // Empty field trials to avoid issues
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
            Log.d(TAG, "initialize: PeerConnectionFactory initialized")
        
            val audioDeviceModule = try {
                JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(false) // Disable hardware AEC to avoid crashes
                    .setUseHardwareNoiseSuppressor(false) // Disable hardware NS to avoid crashes
                    .createAudioDeviceModule()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create audio device module with hardware features, trying basic", e)
                JavaAudioDeviceModule.builder(context)
                    .createAudioDeviceModule()
            }
            Log.d(TAG, "initialize: Audio device module created")
        
            try {
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, true, true))
                    .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
                    .setAudioDeviceModule(audioDeviceModule)
                    .createPeerConnectionFactory()
                    
                if (peerConnectionFactory != null) {
                    Log.d(TAG, "initialize: PeerConnectionFactory created successfully")
                } else {
                    Log.e(TAG, "initialize: PeerConnectionFactory creation returned null")
                    throw RuntimeException("PeerConnectionFactory creation failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "initialize: Failed to create PeerConnectionFactory", e)
                throw RuntimeException("PeerConnectionFactory creation failed: ${e.message}", e)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "initialize: Failed to initialize WebRTC factory", e)
            throw RuntimeException("WebRTC initialization failed: ${e.message}", e)
        }
    }
    
    fun addListener(listener: WebRTCListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: WebRTCListener) {
        listeners.remove(listener)
    }
    
    // Keep this for backward compatibility
    fun setListener(listener: WebRTCListener) {
        listeners.clear()
        listeners.add(listener)
    }
    
    fun createPeerConnection() {
        if (isDisposed) {
            Log.e(TAG, "createPeerConnection: WebRTCManager has been disposed!")
            return
        }
        
        // Ensure we're on the main thread for WebRTC operations
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            Log.w(TAG, "createPeerConnection called from background thread, switching to main thread")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                createPeerConnection()
            }
            return
        }
        
        Log.d(TAG, "createPeerConnection: Starting peer connection creation")
        
        if (peerConnectionFactory == null) {
            Log.e(TAG, "createPeerConnection: PeerConnectionFactory is null!")
            return
        }
        
        val rtcConfig = PeerConnection.RTCConfiguration(STUN_SERVERS).apply {
            // Simplified configuration for better compatibility
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            
            // Conservative timing for better success rate
            iceConnectionReceivingTimeout = 30000 // 30 seconds
            iceBackupCandidatePairPingInterval = 2000 // 2 seconds 
            keyType = PeerConnection.KeyType.ECDSA
            enableDtlsSrtp = true
            enableRtpDataChannel = false
            
            // Pre-gather candidates for faster connection
            iceCandidatePoolSize = 4 // Reasonable pool size
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        
        Log.d(TAG, "createPeerConnection: Creating peer connection with ${STUN_SERVERS.size} STUN servers")
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                candidate?.let {
                    Log.d(TAG, "New ICE candidate: ${it.sdp}")
                    Log.d(TAG, "Candidate type: ${if (it.sdp.contains("typ host")) "HOST" 
                        else if (it.sdp.contains("typ srflx")) "SRFLX" 
                        else if (it.sdp.contains("typ relay")) "RELAY" 
                        else "UNKNOWN"}")
                    Log.d(TAG, "Candidate protocol: ${if (it.sdp.contains("udp")) "UDP" else "TCP"}")
                    
                    collectedIceCandidates.add(it)
                    listeners.forEach { listener -> listener.onIceCandidate(it) }
                }
            }
            
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                newState?.let {
                    Log.d(TAG, "ICE connection state: $it")
                    
                    when (it) {
                        PeerConnection.IceConnectionState.CHECKING -> {
                            Log.i(TAG, "ICE connection CHECKING - Trying to connect through NAT...")
                            Log.i(TAG, "This may take 10-30 seconds. Ensure both devices have internet access.")
                            Log.i(TAG, "NOTE: If testing on emulators, connection may remain in CHECKING state")
                            
                            // Start a timeout mechanism for CHECKING state
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (peerConnection?.iceConnectionState() == PeerConnection.IceConnectionState.CHECKING) {
                                    Log.w(TAG, "ICE connection stuck in CHECKING state for 30 seconds")
                                    Log.w(TAG, "This is NORMAL for emulator testing - media should still flow")
                                    Log.w(TAG, "For real device testing, ensure both devices have internet access")
                                }
                            }, 30000) // 30 second timeout
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e(TAG, "ICE connection FAILED! No route found between devices")
                            Log.e(TAG, "Possible causes: Both devices behind NAT, TURN servers unavailable, firewall blocking")
                            // Note: restartIce() is not available in this WebRTC library version
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            Log.w(TAG, "ICE connection DISCONNECTED! Connection lost, trying to reconnect...")
                        }
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Log.i(TAG, "🎉 ICE connection CONNECTED successfully! Call established.")
                        }
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            Log.i(TAG, "🚀 ICE connection COMPLETED - optimal path found and confirmed!")
                        }
                        else -> {
                            Log.d(TAG, "ICE connection state: $it")
                        }
                    }
                    
                    listeners.forEach { listener -> listener.onIceConnectionChange(it) }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                if (transceiver == null) {
                    Log.w(TAG, "onTrack called with null transceiver")
                    return
                }

                val track = transceiver.receiver?.track()
                Log.d(TAG, "🎯 onTrack: Track received - id=${track?.id()}, kind=${track?.kind()}, enabled=${track?.enabled()}")
                Log.d(TAG, "🎯 onTrack: Transceiver direction=${transceiver.direction}")

                // CRITICAL: Enhanced video track handling for answer side
                if (track is VideoTrack) {
                    Log.d(TAG, "🎥 onTrack: Processing video track for answer side")
                    
                    // Multiple attempts with delays to ensure connection
                    if (remoteVideoSink != null) {
                        Log.d(TAG, "🎥 onTrack: Adding video track to remote sink IMMEDIATELY")
                        try {
                            val videoSink = remoteVideoSink as? SurfaceViewRenderer
                            if (videoSink != null) {
                                track.addSink(videoSink)
                                Log.d(TAG, "✅ onTrack: Video track added to remote sink successfully")
                            } else {
                                Log.e(TAG, "❌ onTrack: Remote video sink is not SurfaceViewRenderer")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ onTrack: Error adding track to remote sink", e)
                        }
                    } else {
                        Log.w(TAG, "⚠️ onTrack: Remote video sink not ready, scheduling retry")
                        // Schedule retry for answer side
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (remoteVideoSink != null) {
                                Log.d(TAG, "🎥 onTrack: Retry - Adding video track to remote sink")
                                try {
                                    val videoSink = remoteVideoSink as? SurfaceViewRenderer
                                    if (videoSink != null) {
                                        track.addSink(videoSink)
                                        Log.d(TAG, "✅ onTrack: Retry successful - Video track added")
                                    } else {
                                        Log.e(TAG, "❌ onTrack: Remote video sink is not SurfaceViewRenderer on retry")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ onTrack: Retry failed", e)
                                }
                            } else {
                                Log.e(TAG, "❌ onTrack: Remote video sink still not ready after retry")
                            }
                        }, 500) // 500ms retry for answer side
                    }
                }

                // Notify all listeners
                listeners.forEach { listener ->
                    try {
                        listener.onTrack(transceiver)
                    } catch (e: Exception) {
                        Log.e(TAG, "onTrack: Error notifying listener", e)
                    }
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                stream?.let {
                    Log.d(TAG, "🌊 Stream added: ${it.id}")
                    
                    // Handle video tracks - CRITICAL: Add to remote sink immediately
                    if (it.videoTracks.size > 0) {
                        Log.d(TAG, "Stream has ${it.videoTracks.size} video tracks")
                        val videoTrack = it.videoTracks[0]
                        
                        // Try to add to remote sink if available
                        remoteVideoSink?.let { sink ->
                            try {
                                Log.d(TAG, "🎥 Adding video track from stream to remote sink IMMEDIATELY")
                                videoTrack.addSink(sink)
                                Log.d(TAG, "✅ Video track from stream added to sink successfully")
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Error adding video track from stream to sink", e)
                            }
                        }
                    }
                    
                    listeners.forEach { listener -> 
                        try {
                            listener.onAddStream(it)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error notifying listener about stream", e)
                        }
                    }
                }
            }
            
            override fun onRemoveStream(stream: MediaStream?) {
                stream?.let {
                    Log.d(TAG, "Stream removed: ${it.id}")
                    listeners.forEach { listener -> listener.onRemoveStream(it) }
                }
            }
            
            override fun onDataChannel(dataChannel: DataChannel?) {
                Log.d(TAG, "onDataChannel: ${dataChannel?.label()}")
            }
            
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                Log.d(TAG, "onIceGatheringChange: $newState")
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    Log.d(TAG, "ICE gathering complete. Total candidates: ${collectedIceCandidates.size}")
                    
                    // Log candidate analysis for debugging
                    val hostCandidates = collectedIceCandidates.count { it.sdp.contains("typ host") }
                    val srflxCandidates = collectedIceCandidates.count { it.sdp.contains("typ srflx") }
                    val relayCandidates = collectedIceCandidates.count { it.sdp.contains("typ relay") }
                    
                    Log.d(TAG, "Candidate breakdown - Host: $hostCandidates, SRFLX: $srflxCandidates, RELAY: $relayCandidates")
                    
                    if (relayCandidates == 0) {
                        Log.w(TAG, "WARNING: No RELAY candidates found! Cross-network connectivity may fail.")
                        Log.w(TAG, "Consider checking TURN server credentials or network connectivity")
                    } else {
                        Log.i(TAG, "✓ Found $relayCandidates RELAY candidates for cross-network connectivity")
                    }
                    
                    if (hostCandidates == 0) {
                        Log.w(TAG, "WARNING: No HOST candidates found! Local network may have issues.")
                    }
                    
                    if (srflxCandidates == 0) {
                        Log.w(TAG, "WARNING: No SRFLX candidates found! STUN servers may be unreachable.")
                    }
                }
            }
            
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                Log.d(TAG, "onSignalingChange: $newState")
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>?) {
                Log.d(TAG, "onIceCandidatesRemoved: ${candidates?.size} candidates")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: Peer connection needs renegotiation")
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                Log.d(TAG, "onAddTrack called — no-op (handled via onTrack)")
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: receiving=$receiving")
            }
        })
        
        if (peerConnection != null) {
            Log.d(TAG, "createPeerConnection: Peer connection created successfully")
        } else {
            Log.e(TAG, "createPeerConnection: Failed to create peer connection")
        }
    }
    
    fun addMediaTracks() {
        Log.d(TAG, "addMediaTracks: Adding audio and video tracks")
        
        try {
            if (peerConnectionFactory == null) {
                Log.e(TAG, "addMediaTracks: PeerConnectionFactory is null!")
                return
            }
            
            if (peerConnection == null) {
                Log.e(TAG, "addMediaTracks: PeerConnection is null!")
                return
            }
            
            // Create audio track
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audioTrack", audioSource)
            
            // Add audio track to peer connection
            localAudioTrack?.let { track ->
                val result = peerConnection?.addTrack(track, listOf("mediaStream"))
                if (result != null) {
                    Log.d(TAG, "addMediaTracks: Audio track added successfully")
                } else {
                    Log.e(TAG, "addMediaTracks: Failed to add audio track")
                }
            }
            
            // Create video track for SDP negotiation (camera will be connected later)
            if (localVideoTrack == null) {
                Log.d(TAG, "addMediaTracks: Creating video track for SDP negotiation")
                createVideoTrackForNegotiation()
            }
            
            // Add video track to peer connection
            localVideoTrack?.let { track ->
                val result = peerConnection?.addTrack(track, listOf("mediaStream"))
                if (result != null) {
                    Log.d(TAG, "addMediaTracks: Video track added successfully")
                } else {
                    Log.e(TAG, "addMediaTracks: Failed to add video track")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "addMediaTracks: Exception adding media tracks", e)
        }
    }
    
    private fun createVideoTrackForNegotiation() {
        try {
            Log.d(TAG, "createVideoTrackForNegotiation: Creating video track with capturer")
            
            // Create capturer and video source early
            val videoCapturer = createCameraCapturer()
            this.videoCapturer = videoCapturer
            
            if (videoCapturer != null) {
                // Create video source
                val videoSource = peerConnectionFactory?.createVideoSource(false)
                this.videoSource = videoSource
                
                // Initialize capturer with source but don't start capture yet
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
                videoCapturer.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
                
                // Create video track
                localVideoTrack = peerConnectionFactory?.createVideoTrack("videoTrack", videoSource)
                Log.d(TAG, "createVideoTrackForNegotiation: Video track created and capturer initialized")
            } else {
                Log.e(TAG, "createVideoTrackForNegotiation: Failed to create camera capturer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "createVideoTrackForNegotiation: Failed to create video track", e)
        }
    }
    
    fun startLocalVideoCapture(localVideoView: SurfaceViewRenderer) {
        try {
            Log.d(TAG, "startLocalVideoCapture: Starting camera capture")
            
            if (videoCapturer == null || localVideoTrack == null) {
                Log.e(TAG, "startLocalVideoCapture: Video capturer or track not initialized")
                return
            }
            
            // Initialize the video view with shared context
            localVideoView.init(eglBase?.eglBaseContext, null)
            
            // Start video capture
            videoCapturer?.startCapture(1280, 720, 30)
            Log.d(TAG, "startLocalVideoCapture: Camera capture started")
            
            // Connect video track to the view
            localVideoSink = localVideoView
            localVideoTrack?.addSink(localVideoView)
            
            Log.d(TAG, "startLocalVideoCapture: Video capture setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "startLocalVideoCapture: Error starting video capture", e)
        }
    }
    
    fun setupRemoteVideoView(remoteVideoView: SurfaceViewRenderer) {
        try {
            Log.d(TAG, "🎥 setupRemoteVideoView: Setting up remote video view")
            if (!remoteVideoView.isInitialized()) {
                Log.d(TAG, "setupRemoteVideoView: Initializing remote video view")
                remoteVideoView.init(eglBase?.eglBaseContext, null)
            }
            
            // CRITICAL: Store the remote video sink for immediate use
            remoteVideoSink = remoteVideoView
            Log.d(TAG, "✅ setupRemoteVideoView: Remote video sink registered and ready")
            
            // If we already have existing remote video tracks, add them now
            // This handles the case where the track arrived before the view was set up
            tryAddExistingRemoteVideoTracks()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ setupRemoteVideoView: Error setting up remote video view", e)
        }
    }
    
    private fun tryAddExistingRemoteVideoTracks() {
        try {
            Log.d(TAG, "🔍 Checking for existing remote video tracks...")
            
            // Check transceivers for existing tracks
            peerConnection?.transceivers?.forEach { transceiver ->
                val track = transceiver.receiver?.track()
                Log.d(TAG, "🎯 Transceiver found - direction: ${transceiver.direction}, track: ${track?.id()}, kind: ${track?.kind()}")
                
                if (track is VideoTrack && remoteVideoSink != null) {
                    Log.d(TAG, "🎥 Found existing remote video track: ${track.id()}")
                    try {
                        // Cast to SurfaceViewRenderer for proper addSink method
                        val videoSink = remoteVideoSink as? SurfaceViewRenderer
                        if (videoSink != null) {
                            track.addSink(videoSink)
                            Log.d(TAG, "✅ Added existing video track to sink")
                        } else {
                            Log.e(TAG, "❌ Remote video sink is not SurfaceViewRenderer")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error adding existing video track (may already be added)", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for existing tracks", e)
        }
    }

    
    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        
        for (deviceName in deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        
        return null
    }
    
    fun createOffer() {
        if (!checkNotDisposed()) return
        Log.d(TAG, "createOffer: Starting offer creation")
        
        if (peerConnection == null) {
            Log.e(TAG, "createOffer: PeerConnection is null!")
            return
        }
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true")) // Enable video
        }
        
        Log.d(TAG, "createOffer: Creating offer with constraints")
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                Log.d(TAG, "SDP OFFER:\n${sessionDescription?.description}")
                Log.d(TAG, "createOffer: Offer created successfully")
                sessionDescription?.let {
                    Log.d(TAG, "createOffer: Setting local description")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "createOffer: Local description set successfully")
                            Log.d(TAG, "createOffer: Calling onOfferCreated listener")
                            listeners.forEach { listener -> listener.onOfferCreated(it) }
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, it)
                }
            }
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createOffer: Failed to create offer: $error")
                Log.e(TAG, "createOffer: Check if peer connection is properly initialized and media tracks are added")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    fun createAnswer() {
        if (!checkNotDisposed()) return
        Log.d(TAG, "createAnswer: Starting answer creation")
        
        if (peerConnection == null) {
            Log.e(TAG, "createAnswer: PeerConnection is null!")
            return
        }
        
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        
        Log.d(TAG, "createAnswer: Creating answer with constraints")
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                Log.d(TAG, "SDP ANSWER:\n${sessionDescription?.description}")
                Log.d(TAG, "createAnswer: Answer created successfully")
                sessionDescription?.let {
                    Log.d(TAG, "createAnswer: Setting local description for answer")
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "createAnswer: Local description set successfully")
                            
                            // CRITICAL: Enhanced answer side track detection after local description
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                Log.d(TAG, "🔍 Answer side: Check after local description set")
                                tryAddExistingRemoteVideoTracks()
                                checkTransceiversForTracks("After local description")
                            }, 150)
                            
                            // Additional check with longer delay
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                Log.d(TAG, "🔍 Answer side: Extended check after local description")
                                tryAddExistingRemoteVideoTracks()
                                checkTransceiversForTracks("Extended after local description")
                            }, 500)
                            
                            Log.d(TAG, "createAnswer: Calling onAnswerCreated listener")
                            listeners.forEach { listener -> listener.onAnswerCreated(it) }
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "Failed to set local description: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, it)
                }
            }
            
            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "createAnswer: Failed to create answer: $error")
            }
            
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    fun setRemoteDescription(sessionDescription: SessionDescription, onSuccess: () -> Unit) {
        if (!checkNotDisposed()) return
        Log.d(TAG, "setRemoteDescription: Setting remote description")
        if (peerConnection == null) {
            Log.e(TAG, "setRemoteDescription: PeerConnection is null!")
            return
        }
        
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Remote description set successfully")
                isRemoteDescriptionSet = true

                // Apply queued candidates now
                Log.d(TAG, "🔁 Flushing ${queuedRemoteCandidates.size} queued ICE candidates")
                queuedRemoteCandidates.forEach {
                    peerConnection?.addIceCandidate(
                        org.webrtc.IceCandidate(it.sdpMid, it.sdpMLineIndex, it.candidate)
                    )
                }
                queuedRemoteCandidates.clear()
                onSuccess()
                
                // CRITICAL: Enhanced answer side remote track handling
                // Multiple delayed checks to catch tracks that arrive at different times
                
                // Immediate check
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Log.d(TAG, "🔍 Answer side: IMMEDIATE check for remote tracks after setRemoteDescription")
                    tryAddExistingRemoteVideoTracks()
                }
                
                // Short delay check (100ms) - for tracks that arrive quickly
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "🔍 Answer side: SHORT delay check for remote tracks")
                    tryAddExistingRemoteVideoTracks()
                    checkTransceiversForTracks("SHORT delay")
                }, 100)
                
                // Medium delay check (300ms) - for tracks that arrive after negotiation
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "🔍 Answer side: MEDIUM delay check for remote tracks")
                    tryAddExistingRemoteVideoTracks()
                    checkTransceiversForTracks("MEDIUM delay")
                }, 300)
                
                // Long delay check (800ms) - final attempt for late-arriving tracks
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "🔍 Answer side: LONG delay check for remote tracks")
                    tryAddExistingRemoteVideoTracks()
                    checkTransceiversForTracks("LONG delay")
                }, 800)
            }
            
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription: Failed to set remote description: $error")
            }
            
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }

    private fun checkTransceiversForTracks(checkType: String) {
        try {
            Log.d(TAG, "🎯 checkTransceiversForTracks ($checkType): Examining all transceivers")
            
            peerConnection?.transceivers?.forEachIndexed { index, transceiver ->
                val track = transceiver.receiver?.track()
                val direction = transceiver.direction
                val mid = transceiver.mid
                
                Log.d(TAG, "🎯 Transceiver $index ($checkType): mid=$mid, direction=$direction, track=${track?.id()}, kind=${track?.kind()}, enabled=${track?.enabled()}")
                
                // Focus on receiving transceivers
                if (direction == RtpTransceiver.RtpTransceiverDirection.RECV_ONLY ||
                    direction == RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    
                    if (track is VideoTrack) {
                        Log.d(TAG, "🎥 Found video track in transceiver ($checkType): ${track.id()}")
                        
                        if (remoteVideoSink != null) {
                            try {
                                Log.d(TAG, "🎥 Adding video track from transceiver ($checkType) to remote sink")
                                val videoSink = remoteVideoSink as? SurfaceViewRenderer
                                if (videoSink != null) {
                                    // Check if track is already added to avoid exceptions
                                    track.addSink(videoSink)
                                    Log.d(TAG, "✅ Video track from transceiver ($checkType) added successfully")
                                } else {
                                    Log.e(TAG, "❌ Remote video sink is not SurfaceViewRenderer ($checkType)")
                                }
                            } catch (e: Exception) {
                                // This is expected if track is already added
                                Log.d(TAG, "🔄 Track may already be added to sink ($checkType): ${e.message}")
                            }
                        } else {
                            Log.w(TAG, "⚠️ No remote video sink available ($checkType)")
                        }
                    } else if (track != null) {
                        Log.d(TAG, "🎵 Found audio track in transceiver ($checkType): ${track.id()}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking transceivers ($checkType)", e)
        }
    }
    
    fun addIceCandidate(candidate: org.webrtc.IceCandidate) {
        if (!checkNotDisposed()) return

        if (!isRemoteDescriptionSet) {
            Log.w(TAG, "🕓 Remote description not set yet. Queuing ICE candidate.")
            queuedRemoteCandidates.add(
                com.example.webrtcqrdemo.IceCandidate(
                    candidate = candidate.sdp,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex
                )
            )
            return
        }

        Log.d(TAG, "addIceCandidate: Adding remote ICE candidate: ${candidate.sdp}")
        val result = peerConnection?.addIceCandidate(candidate)
        Log.d(TAG, "addIceCandidate: Result = $result")
    }
    
    fun getCollectedIceCandidates(): List<com.example.webrtcqrdemo.IceCandidate> {
        return collectedIceCandidates.map { candidate ->
            com.example.webrtcqrdemo.IceCandidate(
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            )
        }
    }
    
    fun getEglBaseContext(): EglBase.Context? {
        return eglBase?.eglBaseContext
    }
    
    fun setAudioEnabled(enabled: Boolean) {
        try {
            localAudioTrack?.setEnabled(enabled)
            Log.d(TAG, "Audio enabled: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting audio enabled", e)
        }
    }
    
    fun setVideoEnabled(enabled: Boolean) {
        try {
            localVideoTrack?.setEnabled(enabled)
            if (enabled) {
                videoCapturer?.startCapture(1280, 720, 30)
            } else {
                videoCapturer?.stopCapture()
            }
            Log.d(TAG, "Video enabled: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting video enabled", e)
        }
    }
    
    fun switchCamera() {
        try {
            if (videoCapturer is CameraVideoCapturer) {
                (videoCapturer as CameraVideoCapturer).switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                    override fun onCameraSwitchDone(p0: Boolean) {
                        Log.d(TAG, "Camera switch done: front camera = $p0")
                    }
                    
                    override fun onCameraSwitchError(error: String?) {
                        Log.e(TAG, "Camera switch error: $error")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error switching camera", e)
        }
    }
    
    fun setAudioLevelCallback(callback: (Boolean, Boolean) -> Unit) {
        audioLevelCallback = callback
        startVoiceDetection()
    }

    // Extension function to check if SurfaceViewRenderer is initialized
    private fun SurfaceViewRenderer.isInitialized(): Boolean {
        return try {
            // Try to access a property that would fail if not initialized
            this.visibility != null
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun startVoiceDetection() {
        try {
            voiceDetectionHandler = android.os.Handler(android.os.Looper.getMainLooper())
            voiceDetectionRunnable = object : Runnable {
                override fun run() {
                    try {
                        val isConnected = peerConnection?.connectionState() == PeerConnection.PeerConnectionState.CONNECTED
                        val isAudioEnabled = localAudioTrack?.enabled() == true
                        
                        // Simulate realistic voice activity patterns
                        // Local voice: only show when audio is enabled and randomly simulate speaking
                        val localVoiceActive = if (isAudioEnabled && !isDisposed) {
                            // Random chance to toggle voice state every check
                            if (Math.random() > 0.8) { // 20% chance to change state
                                localVoiceSimulationState = !localVoiceSimulationState
                            }
                            localVoiceSimulationState
                        } else {
                            localVoiceSimulationState = false
                            false
                        }
                        
                        // Remote voice: only show when connected and simulate intermittent activity
                        val remoteVoiceActive = if (isConnected) {
                            // Random chance to toggle remote voice state
                            if (Math.random() > 0.85) { // 15% chance to change state
                                remoteVoiceSimulationState = !remoteVoiceSimulationState
                            }
                            remoteVoiceSimulationState
                        } else {
                            remoteVoiceSimulationState = false
                            false
                        }
                        
                        audioLevelCallback?.invoke(localVoiceActive, remoteVoiceActive)
                        
                        if (!isDisposed) {
                            voiceDetectionHandler?.postDelayed(this, 800) // Check every 800ms for more natural timing
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in voice detection", e)
                    }
                }
            }
            voiceDetectionHandler?.post(voiceDetectionRunnable!!)
            Log.d(TAG, "Voice detection started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting voice detection", e)
        }
    }
    
    private fun stopVoiceDetection() {
        try {
            voiceDetectionRunnable?.let { runnable ->
                voiceDetectionHandler?.removeCallbacks(runnable)
            }
            voiceDetectionHandler = null
            voiceDetectionRunnable = null
            audioLevelCallback = null
            Log.d(TAG, "Voice detection stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping voice detection", e)
        }
    }
    
    fun isDisposed(): Boolean {
        return isDisposed
    }
    
    private fun checkNotDisposed(): Boolean {
        if (isDisposed) {
            Log.w(TAG, "WebRTCManager is disposed, ignoring operation")
            return false
        }
        return true
    }
    
    fun dispose() {
        if (isDisposed) return // Prevent double disposal
        
        Log.d(TAG, "dispose: Starting WebRTC cleanup")
        isDisposed = true
        
        try {
            // Stop voice detection first
            stopVoiceDetection()
            
            // Stop camera capture
            videoCapturer?.let { capturer ->
                try {
                    Log.d(TAG, "dispose: Stopping video capturer")
                    capturer.stopCapture()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error stopping video capturer", e)
                }
            }
            
            // Remove video sinks to prevent rendering to disposed surfaces
            localVideoTrack?.let { track ->
                try {
                    Log.d(TAG, "dispose: Removing local video sinks")
                    localVideoSink?.let { track.removeSink(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error removing local video sink", e)
                }
            }
            
            // Close peer connection before disposing tracks
            peerConnection?.let { pc ->
                try {
                    Log.d(TAG, "dispose: Closing peer connection")
                    pc.close()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error closing peer connection", e)
                }
            }
            
            // Wait a bit for peer connection to close cleanly
            Thread.sleep(100)
            
            // Dispose video tracks
            localVideoTrack?.let { track ->
                try {
                    Log.d(TAG, "dispose: Disposing local video track")
                    track.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error disposing local video track", e)
                }
            }
            
            // Dispose audio tracks
            localAudioTrack?.let { track ->
                try {
                    Log.d(TAG, "dispose: Disposing local audio track")
                    track.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error disposing local audio track", e)
                }
            }
            
            // Dispose video source
            videoSource?.let { source ->
                try {
                    Log.d(TAG, "dispose: Disposing video source")
                    source.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error disposing video source", e)
                }
            }
            
            // Dispose video capturer
            videoCapturer?.let { capturer ->
                try {
                    Log.d(TAG, "dispose: Disposing video capturer")
                    capturer.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error disposing video capturer", e)
                }
            }
            
            // Dispose surface texture helper
            surfaceTextureHelper?.let { helper ->
                try {
                    Log.d(TAG, "dispose: Disposing surface texture helper")
                    helper.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error disposing surface texture helper", e)
                }
            }
            
            // Dispose peer connection factory last
            peerConnectionFactory?.let { factory ->
                try {
                    Log.d(TAG, "dispose: Disposing peer connection factory")
                    factory.dispose()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error disposing peer connection factory", e)
                }
            }
            
            // Release EGL base
            eglBase?.let { egl ->
                try {
                    Log.d(TAG, "dispose: Releasing EGL base")
                    egl.release()
                } catch (e: Exception) {
                    Log.e(TAG, "dispose: Error releasing EGL base", e)
                }
            }
            
            // Shutdown executor
            try {
                Log.d(TAG, "dispose: Shutting down executor")
                executor.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "dispose: Error shutting down executor", e)
            }
            
            // Clear references
            videoCapturer = null
            localVideoTrack = null
            localAudioTrack = null
            videoSource = null
            peerConnection = null
            peerConnectionFactory = null
            surfaceTextureHelper = null
            localVideoSink = null
            remoteVideoSink = null
            eglBase = null
            
            Log.d(TAG, "dispose: WebRTC cleanup completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "dispose: Fatal error during cleanup", e)
        }
    }
}