#!/bin/bash

echo "🚀 Starting WebRTC Signaling Server..."
echo ""
echo "Make sure you have Node.js installed!"
echo "Download from: https://nodejs.org"
echo ""

if [ ! -d "node_modules" ]; then
    echo "📦 Installing dependencies..."
    npm install
    echo ""
fi

echo "🌐 Starting server on port 3000..."
echo ""
echo "📱 Update your Android app SignalingClient.kt:"
echo "private const val SERVER_URL = \"http://YOUR_IP:3000\""
echo ""
echo "🔍 Find your IP address:"
echo "Mac/Linux: ifconfig | grep inet"
echo "Windows: ipconfig"
echo ""

npm start