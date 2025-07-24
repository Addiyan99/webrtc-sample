package com.example.webrtcqrdemo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.app.NotificationManager

class CallActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_ACCEPT_CALL = "com.example.webrtcqrdemo.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.example.webrtcqrdemo.DECLINE_CALL"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")
        
        when (intent.action) {
            ACTION_ACCEPT_CALL -> {
                val callerId = intent.getStringExtra(IncomingCallActivity.EXTRA_CALLER_ID)
                val offer = intent.getStringExtra(IncomingCallActivity.EXTRA_OFFER)
                Log.d(TAG, "Accept call from notification: $callerId")
                
                // Clear the notification
                clearNotification(context)
                
                // Start IncomingCallActivity with accept action
                val acceptIntent = Intent(context, IncomingCallActivity::class.java).apply {
                    putExtra("action", "accept")
                    putExtra(IncomingCallActivity.EXTRA_CALLER_ID, callerId)
                    putExtra(IncomingCallActivity.EXTRA_OFFER, offer)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                           Intent.FLAG_ACTIVITY_CLEAR_TOP or
                           Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(acceptIntent)
            }
            
            ACTION_DECLINE_CALL -> {
                val callerId = intent.getStringExtra(IncomingCallActivity.EXTRA_CALLER_ID)
                Log.d(TAG, "Decline call from notification: $callerId")
                
                // Clear the notification
                clearNotification(context)
                
                // Handle decline - send decline signal
                val signalingClient = SignalingClientSingleton.getInstance()
                callerId?.let { 
                    signalingClient?.declineCall(it)
                }
            }
        }
    }
    
    private fun clearNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(1001) // Same ID used in MainActivity
        Log.d(TAG, "Notification cleared")
    }
}