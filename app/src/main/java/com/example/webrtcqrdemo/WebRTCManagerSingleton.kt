package com.example.webrtcqrdemo

object WebRTCManagerSingleton {
    private var instance: WebRTCManager? = null
    
    fun getInstance(): WebRTCManager? {
        return instance
    }
    
    fun setInstance(manager: WebRTCManager) {
        instance = manager
    }
    
    fun clearInstance() {
        instance = null
    }
}