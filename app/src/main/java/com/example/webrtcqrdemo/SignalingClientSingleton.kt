package com.example.webrtcqrdemo

object SignalingClientSingleton {
    private var instance: SignalingClient? = null
    
    fun getInstance(): SignalingClient? {
        return instance
    }
    
    fun setInstance(client: SignalingClient) {
        instance = client
    }
    
    fun clearInstance() {
        instance?.disconnect()
        instance = null
    }
}