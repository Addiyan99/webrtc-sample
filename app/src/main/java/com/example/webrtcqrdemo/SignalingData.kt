package com.example.webrtcqrdemo

data class SignalingData(
    val type: String, // "offer" or "answer"
    val sdp: String,
    val iceCandidates: List<IceCandidate> = emptyList()
)

data class IceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
)