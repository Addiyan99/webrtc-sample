# WebRTC Signaling Server

A simple Socket.IO-based signaling server for WebRTC video calling.

## Features

- User registration and management
- Call initiation and routing
- Answer/decline call handling
- ICE candidate exchange
- Real-time user presence

## API Endpoints

- `GET /` - Health check
- `GET /users` - List connected users

## Socket.IO Events

### Client to Server:
- `register_user` - Register user with ID
- `make_call` - Initiate call to another user
- `answer_call` - Answer incoming call
- `decline_call` - Decline incoming call
- `ice_candidate` - Exchange ICE candidates

### Server to Client:
- `user_registered` - Confirm user registration
- `incoming_call` - Notify of incoming call
- `call_answered` - Notify call was answered
- `call_declined` - Notify call was declined
- `call_failed` - Notify call failed (user offline)

## Deployment

### Railway (Recommended)
1. Connect GitHub repo to Railway
2. Deploy automatically with Dockerfile

### Vercel
1. Install Vercel CLI: `npm i -g vercel`
2. Run: `vercel --prod`

### Local Testing
```bash
npm install
npm start
```

Server runs on port 3000 by default.

## Environment Variables

- `PORT` - Server port (default: 3000)

## CORS

Configured to allow all origins for development. Restrict in production.