package com.example.webrtcqrdemo

import android.util.Log
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URISyntaxException

class SignalingClient {
    
    companion object {
        private const val TAG = "SignalingClient"
        // Your deployed Render.com signaling server
        private const val SERVER_URL = "https://webrtc-signaling-server-kb9g.onrender.com"
        private const val SIMULATION_MODE = false // Use real server for multi-device testing
        private const val CONNECTION_TIMEOUT = 5000L // 5 seconds timeout
    }
    
    private var socket: Socket? = null
    private var currentUserId: String? = null
    private val gson = Gson()
    
    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onUserRegistered(success: Boolean)
        fun onIncomingCall(fromUserId: String, offer: String, iceCandidates: List<IceCandidate>)
        fun onCallAnswered(answer: String, iceCandidates: List<IceCandidate>)
        fun onCallDeclined(fromUserId: String)
        fun onError(error: String)
    }
    
    private var listener: SignalingListener? = null
    
    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }
    
    fun connect() {
        if (SIMULATION_MODE) {
            Log.d(TAG, "Starting simulation mode")
            // Simulate successful connection
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                listener?.onConnected()
            }, 1000)
            return
        }
        
        try {
            Log.d(TAG, "Connecting to signaling server: $SERVER_URL")
            socket = IO.socket(SERVER_URL)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d(TAG, "Connected to signaling server")
                listener?.onConnected()
            }
            
            socket?.on(Socket.EVENT_DISCONNECT) {
                Log.d(TAG, "Disconnected from signaling server")
                listener?.onDisconnected()
            }
            
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                Log.e(TAG, "Connection error: ${args.getOrNull(0)}")
                listener?.onError("Connection failed")
            }
            
            // Custom events for WebRTC signaling
            socket?.on("user_registered") { args ->
                val data = args[0] as JSONObject
                val success = data.getBoolean("success")
                Log.d(TAG, "User registration result: $success")
                listener?.onUserRegistered(success)
            }
            
            socket?.on("incoming_call") { args ->
                val data = args[0] as JSONObject
                val fromUserId = data.getString("from")
                val offer = data.getString("offer")
                val iceCandidatesJson = data.getJSONArray("iceCandidates")
                
                val iceCandidates = mutableListOf<IceCandidate>()
                for (i in 0 until iceCandidatesJson.length()) {
                    val candidateJson = iceCandidatesJson.getJSONObject(i)
                    iceCandidates.add(
                        IceCandidate(
                            candidate = candidateJson.getString("candidate"),
                            sdpMid = candidateJson.getString("sdpMid"),
                            sdpMLineIndex = candidateJson.getInt("sdpMLineIndex")
                        )
                    )
                }
                
                Log.d(TAG, "Incoming call from $fromUserId")
                listener?.onIncomingCall(fromUserId, offer, iceCandidates)
            }
            
            socket?.on("call_answered") { args ->
                val data = args[0] as JSONObject
                val answer = data.getString("answer")
                val iceCandidatesJson = data.getJSONArray("iceCandidates")
                
                val iceCandidates = mutableListOf<IceCandidate>()
                for (i in 0 until iceCandidatesJson.length()) {
                    val candidateJson = iceCandidatesJson.getJSONObject(i)
                    iceCandidates.add(
                        IceCandidate(
                            candidate = candidateJson.getString("candidate"),
                            sdpMid = candidateJson.getString("sdpMid"),
                            sdpMLineIndex = candidateJson.getInt("sdpMLineIndex")
                        )
                    )
                }
                
                Log.d(TAG, "Call answered")
                listener?.onCallAnswered(answer, iceCandidates)
            }
            
            socket?.on("call_declined") { args ->
                val data = args[0] as JSONObject
                val fromUserId = data.getString("from")
                Log.d(TAG, "Call declined by $fromUserId")
                listener?.onCallDeclined(fromUserId)
            }
            
            socket?.connect()
            
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL", e)
            listener?.onError("Invalid server URL")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            listener?.onError("Connection failed: ${e.message}")
        }
    }
    
    fun disconnect() {
        socket?.disconnect()
        socket = null
        currentUserId = null
    }
    
    fun registerUser(userId: String) {
        currentUserId = userId
        
        if (SIMULATION_MODE) {
            Log.d(TAG, "Simulating user registration: $userId")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                listener?.onUserRegistered(true)
            }, 500)
            return
        }
        
        val data = JSONObject().apply {
            put("userId", userId)
        }
        socket?.emit("register_user", data)
        Log.d(TAG, "Registering user: $userId")
    }
    
    fun makeCall(targetUserId: String, offer: String, iceCandidates: List<IceCandidate>) {
        if (SIMULATION_MODE) {
            Log.d(TAG, "Simulating call to $targetUserId - showing incoming call simulation")
            // In simulation mode, immediately show the incoming call on the same device for testing
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                listener?.onIncomingCall(currentUserId ?: "TestCaller", offer, iceCandidates)
            }, 1000)
            return
        }
        
        val iceCandidatesJsonArray = org.json.JSONArray()
        iceCandidates.forEach { candidate ->
            val candidateJson = JSONObject().apply {
                put("candidate", candidate.candidate)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            iceCandidatesJsonArray.put(candidateJson)
        }
        
        val data = JSONObject().apply {
            put("to", targetUserId)
            put("from", currentUserId)
            put("offer", offer)
            put("iceCandidates", iceCandidatesJsonArray)
        }
        
        socket?.emit("make_call", data)
        Log.d(TAG, "Making call to $targetUserId")
    }
    
    fun answerCall(targetUserId: String, answer: String, iceCandidates: List<IceCandidate>) {
        if (SIMULATION_MODE) {
            Log.d(TAG, "Simulating answer call to $targetUserId")
            // In simulation mode, send the answer back to the caller
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                listener?.onCallAnswered(answer, iceCandidates)
            }, 1000)
            return
        }
        
        val iceCandidatesJsonArray = org.json.JSONArray()
        iceCandidates.forEach { candidate ->
            val candidateJson = JSONObject().apply {
                put("candidate", candidate.candidate)
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
            }
            iceCandidatesJsonArray.put(candidateJson)
        }
        
        val data = JSONObject().apply {
            put("to", targetUserId)
            put("from", currentUserId)
            put("answer", answer)
            put("iceCandidates", iceCandidatesJsonArray)
        }
        
        socket?.emit("answer_call", data)
        Log.d(TAG, "Answering call to $targetUserId")
    }
    
    fun declineCall(targetUserId: String) {
        val data = JSONObject().apply {
            put("to", targetUserId)
            put("from", currentUserId)
        }
        
        socket?.emit("decline_call", data)
        Log.d(TAG, "Declining call from $targetUserId")
    }
    
    fun isConnected(): Boolean {
        return if (SIMULATION_MODE) {
            true // Always connected in simulation mode
        } else {
            socket?.connected() == true
        }
    }
}