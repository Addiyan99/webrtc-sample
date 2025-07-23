@echo off
echo 🚀 Starting WebRTC Signaling Server...
echo.
echo Make sure you have Node.js installed!
echo Download from: https://nodejs.org
echo.

if not exist node_modules (
    echo 📦 Installing dependencies...
    npm install
    echo.
)

echo 🌐 Starting server on port 3000...
echo.
echo 📱 Update your Android app SignalingClient.kt:
echo private const val SERVER_URL = "http://YOUR_IP:3000"
echo.
echo 🔍 Find your IP address:
echo Windows: ipconfig
echo Mac/Linux: ifconfig
echo.

npm start