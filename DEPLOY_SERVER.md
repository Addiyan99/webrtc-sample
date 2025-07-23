# 🚀 Deploy Your Signaling Server

You have several FREE options to deploy the signaling server for multi-device testing:

## Option 1: Railway (Recommended - FREE)

1. **Go to [Railway.app](https://railway.app)**
2. **Sign up with GitHub**
3. **Create New Project** → **Deploy from GitHub repo**
4. **Connect** your repository
5. **Select** the `signaling-server` folder
6. **Deploy** - Railway will automatically detect the Dockerfile
7. **Copy** the deployed URL (e.g., `https://your-app.railway.app`)

## Option 2: Render (FREE)

1. **Go to [Render.com](https://render.com)**
2. **Sign up** and connect GitHub
3. **New Web Service** → Connect repository
4. **Root Directory**: `signaling-server`
5. **Build Command**: `npm install`
6. **Start Command**: `npm start`
7. **Deploy** and copy the URL

## Option 3: Cyclic (FREE)

1. **Go to [Cyclic.sh](https://cyclic.sh)**
2. **Deploy** → Link GitHub repository
3. **Select** the signaling-server folder
4. **Deploy** automatically

## Option 4: Local Network (No deployment needed)

If both devices are on the same WiFi:

1. **On your computer**, navigate to `signaling-server` folder
2. **Run**: `npm install && npm start`
3. **Find your IP**: `ipconfig` (Windows) or `ifconfig` (Mac/Linux)
4. **Use**: `http://YOUR_IP:3000` as the server URL

## Update the App

Once deployed, update the SignalingClient.kt:

```kotlin
private const val SERVER_URL = "https://your-deployed-server.com"
private const val SIMULATION_MODE = false
```

## Test Multi-Device

1. **Device A**: Set User ID "alice123"
2. **Device B**: Set User ID "bob456"  
3. **Device A**: Call "bob456"
4. **Device B**: Receives incoming call!

The server supports unlimited users and handles all the signaling automatically.

## Server Features

✅ Real-time user registration  
✅ Call routing between devices  
✅ Answer/decline handling  
✅ ICE candidate exchange  
✅ User presence tracking  
✅ Error handling  

Choose any option above - they're all free and will work for testing!