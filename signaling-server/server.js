const express = require('express');
const http = require('http');
const socketIo = require('socket.io');
const cors = require('cors');

const app = express();
const server = http.createServer(app);

// Enable CORS for all requests
app.use(cors());
app.use(express.json());

// Initialize Socket.IO with CORS enabled
const io = socketIo(server, {
  cors: {
    origin: "*",
    methods: ["GET", "POST"],
    credentials: true
  }
});

// Store connected users
const connectedUsers = new Map(); // userId -> socketId
const userSockets = new Map(); // socketId -> userId

const PORT = process.env.PORT || 3000;

// Basic health check endpoint
app.get('/', (req, res) => {
  res.json({ 
    status: 'WebRTC Signaling Server Running',
    connectedUsers: connectedUsers.size,
    timestamp: new Date().toISOString()
  });
});

app.get('/users', (req, res) => {
  res.json({
    connectedUsers: Array.from(connectedUsers.keys()),
    totalUsers: connectedUsers.size
  });
});

io.on('connection', (socket) => {
  console.log(`New connection: ${socket.id}`);
  
  // Handle user registration
  socket.on('register_user', (data) => {
    const { userId } = data;
    console.log(`Registering user: ${userId} with socket: ${socket.id}`);
    
    // Check if user is already connected
    if (connectedUsers.has(userId)) {
      console.log(`User ${userId} already connected, updating socket`);
      const oldSocketId = connectedUsers.get(userId);
      userSockets.delete(oldSocketId);
    }
    
    // Register user
    connectedUsers.set(userId, socket.id);
    userSockets.set(socket.id, userId);
    
    socket.emit('user_registered', { success: true, userId });
    console.log(`User registered successfully: ${userId}`);
    console.log(`Total connected users: ${connectedUsers.size}`);
  });
  
  // Handle call initiation
  socket.on('make_call', (data) => {
    const { to, from, offer, iceCandidates } = data;
    console.log(`Call from ${from} to ${to}`);
    
    const targetSocketId = connectedUsers.get(to);
    if (targetSocketId) {
      console.log(`Forwarding call to ${to} at socket ${targetSocketId}`);
      io.to(targetSocketId).emit('incoming_call', {
        from,
        offer,
        iceCandidates
      });
    } else {
      console.log(`User ${to} not found or not connected`);
      socket.emit('call_failed', { 
        error: 'User not found or offline',
        targetUser: to 
      });
    }
  });
  
  // Handle call answer
  socket.on('answer_call', (data) => {
    const { to, from, answer, iceCandidates } = data;
    console.log(`Answer from ${from} to ${to}`);
    
    const targetSocketId = connectedUsers.get(to);
    if (targetSocketId) {
      console.log(`Forwarding answer to ${to} at socket ${targetSocketId}`);
      io.to(targetSocketId).emit('call_answered', {
        from,
        answer,
        iceCandidates
      });
    } else {
      console.log(`User ${to} not found for answer`);
    }
  });
  
  // Handle call decline
  socket.on('decline_call', (data) => {
    const { to, from } = data;
    console.log(`Call declined by ${from}, notifying ${to}`);
    
    const targetSocketId = connectedUsers.get(to);
    if (targetSocketId) {
      io.to(targetSocketId).emit('call_declined', { from });
    }
  });
  
  // Handle ICE candidates (for ongoing call)
  socket.on('ice_candidate', (data) => {
    const { to, from, candidate } = data;
    console.log(`ICE candidate from ${from} to ${to}`);
    
    const targetSocketId = connectedUsers.get(to);
    if (targetSocketId) {
      io.to(targetSocketId).emit('ice_candidate', {
        from,
        candidate
      });
    }
  });
  
  // Handle disconnect
  socket.on('disconnect', () => {
    console.log(`Socket disconnected: ${socket.id}`);
    
    const userId = userSockets.get(socket.id);
    if (userId) {
      console.log(`Unregistering user: ${userId}`);
      connectedUsers.delete(userId);
      userSockets.delete(socket.id);
      console.log(`Total connected users: ${connectedUsers.size}`);
    }
  });
  
  // Handle manual user logout
  socket.on('logout_user', (data) => {
    const { userId } = data;
    console.log(`Manual logout for user: ${userId}`);
    
    if (connectedUsers.has(userId)) {
      connectedUsers.delete(userId);
      userSockets.delete(socket.id);
      socket.emit('logout_success', { userId });
    }
  });
});

server.listen(PORT, () => {
  console.log(`🚀 WebRTC Signaling Server running on port ${PORT}`);
  console.log(`📡 Socket.IO enabled with CORS support`);
  console.log(`🌐 Health check: http://localhost:${PORT}`);
  console.log(`👥 Users endpoint: http://localhost:${PORT}/users`);
});

// Graceful shutdown
process.on('SIGTERM', () => {
  console.log('SIGTERM received. Shutting down gracefully...');
  server.close(() => {
    console.log('Server closed.');
  });
});

process.on('SIGINT', () => {
  console.log('SIGINT received. Shutting down gracefully...');
  server.close(() => {
    console.log('Server closed.');
  });
});