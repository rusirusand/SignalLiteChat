const http = require("http");
const WebSocket = require("ws");
const Database = require("better-sqlite3");

const PORT = Number(process.env.PORT || 8080);
const DB_PATH = process.env.DB_PATH || "./messages.db";
const db = new Database(DB_PATH);

db.exec(`
  CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    sender TEXT NOT NULL,
    recipient TEXT NOT NULL,
    body TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    delivered INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS idx_pending_recipient
  ON messages(recipient, delivered);
`);

const insertMessage = db.prepare(`
  INSERT OR IGNORE INTO messages (id, sender, recipient, body, created_at, delivered)
  VALUES (?, ?, ?, ?, ?, 0)
`);
const getPending = db.prepare(`
  SELECT id, sender, body, created_at FROM messages
  WHERE recipient = ? AND delivered = 0 ORDER BY created_at ASC
`);
const markDelivered = db.prepare(`UPDATE messages SET delivered = 1 WHERE id = ? AND recipient = ?`);
const findMessage = db.prepare(`SELECT sender, recipient FROM messages WHERE id = ?`);

const online = new Map();
const server = http.createServer((_req, res) => {
  res.writeHead(200, { "Content-Type": "text/plain" });
  res.end("Signal Lite Chat WebSocket server is running.\n");
});
const wss = new WebSocket.Server({ server, maxPayload: 16 * 1024 });

function send(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function deliverPending(username, ws) {
  for (const row of getPending.all(username)) {
    send(ws, {
      type: "message",
      id: row.id,
      from: row.sender,
      text: row.body,
      timestamp: row.created_at
    });
  }
}

wss.on("connection", (ws) => {
  ws.username = null;

  ws.on("message", (raw) => {
    let packet;
    try {
      packet = JSON.parse(raw.toString());
    } catch {
      return send(ws, { type: "error", message: "Invalid JSON" });
    }

    if (packet.type === "register") {
      const username = String(packet.username || "").trim().toLowerCase();
      if (!/^[a-z0-9_]{3,24}$/.test(username)) {
        return send(ws, { type: "error", message: "Username must be 3-24 letters, numbers, or underscores" });
      }

      if (ws.username && online.get(ws.username) === ws) online.delete(ws.username);
      ws.username = username;
      online.set(username, ws);
      send(ws, { type: "registered", username });
      deliverPending(username, ws);
      return;
    }

    if (!ws.username) {
      return send(ws, { type: "error", message: "Register before sending messages" });
    }

    if (packet.type === "send") {
      const id = String(packet.id || "");
      const to = String(packet.to || "").trim().toLowerCase();
      const body = String(packet.text || "").trim();

      if (!/^[a-zA-Z0-9-]{8,80}$/.test(id)) {
        return send(ws, { type: "error", message: "Invalid message ID" });
      }
      if (!/^[a-z0-9_]{3,24}$/.test(to) || !body || body.length > 2000) {
        return send(ws, { type: "error", message: "Invalid recipient or message" });
      }

      const createdAt = Date.now();
      insertMessage.run(id, ws.username, to, body, createdAt);

      send(ws, { type: "status", id, status: "accepted" });
      const recipientSocket = online.get(to);
      if (recipientSocket && recipientSocket.readyState === WebSocket.OPEN) {
        send(recipientSocket, {
          type: "message",
          id,
          from: ws.username,
          text: body,
          timestamp: createdAt
        });
      }
      return;
    }

    if (packet.type === "delivered") {
      const id = String(packet.id || "");
      const row = findMessage.get(id);
      if (row && row.recipient === ws.username) {
        markDelivered.run(id, ws.username);
        const senderSocket = online.get(row.sender);
        if (senderSocket) send(senderSocket, { type: "status", id, status: "delivered" });
      }
    }
  });

  ws.on("close", () => {
    if (ws.username && online.get(ws.username) === ws) online.delete(ws.username);
  });
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`Signal Lite Chat server listening on port ${PORT}`);
});
