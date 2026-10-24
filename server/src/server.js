import express from "express";
import http from "node:http";
import crypto from "node:crypto";
import Database from "better-sqlite3";
import { WebSocketServer, WebSocket } from "ws";

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "64kb" }));
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
const db = new Database(process.env.DB_PATH || "./vaynax.sqlite");
db.pragma("journal_mode = WAL");
db.exec(`
CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, owner_id TEXT NOT NULL, expires_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, sender_id TEXT NOT NULL, recipient_id TEXT NOT NULL, ciphertext TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient_id, created_at);
`);
const online = new Map();
const hash = value => crypto.createHash("sha256").update(value).digest("hex");
const json = (res, status, body) => res.status(status).json(body);
const auth = (req, res, next) => {
  const token = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  if (!token) return json(res, 401, { error: "Authentication required" });
  const user = db.prepare("SELECT id, name FROM users WHERE token_hash = ?").get(hash(token));
  if (!user) return json(res, 401, { error: "Invalid token" });
  req.user = user; next();
};
app.get("/health", (_req, res) => json(res, 200, { ok: true, service: "VAYNAX", time: Date.now() }));

app.post("/v1/register", (req, res) => {
  const name = typeof req.body?.name === "string" ? req.body.name.trim().slice(0, 40) : "";
  if (!name) return json(res, 400, { error: "Name is required" });
  const id = crypto.randomUUID();
  const token = crypto.randomBytes(32).toString("base64url");
  db.prepare("INSERT INTO users (id,name,token_hash,created_at) VALUES (?,?,?,?)").run(id, name, hash(token), Date.now());
  return json(res, 201, { id, name, token });
});

app.get("/v1/me", auth, (req, res) => json(res, 200, req.user));
app.post("/v1/invites", auth, (req, res) => {
  const code = crypto.randomBytes(5).toString("hex").toUpperCase();
  const expiresAt = Date.now() + 7 * 24 * 60 * 60 * 1000;
  db.prepare("INSERT INTO invites(code,owner_id,expires_at) VALUES(?,?,?)").run(code, req.user.id, expiresAt);
  json(res, 201, { code, expiresAt });
});
app.post("/v1/invites/redeem", auth, (req, res) => {
  const code = typeof req.body?.code === "string" ? req.body.code.trim().toUpperCase() : "";
  const invite = db.prepare("SELECT owner_id FROM invites WHERE code=? AND expires_at>?").get(code, Date.now());
  if (!invite) return json(res, 404, { error: "Invite not found or expired" });
  if (invite.owner_id === req.user.id) return json(res, 400, { error: "Cannot add yourself" });
  const peer = db.prepare("SELECT id,name FROM users WHERE id=?").get(invite.owner_id);
  json(res, 200, { id: peer.id, name: peer.name });
});
app.get("/v1/messages/pending", auth, (req, res) => {
  const rows = db.prepare("SELECT id,sender_id AS senderId,ciphertext,created_at AS createdAt FROM messages WHERE recipient_id=? ORDER BY created_at LIMIT 200").all(req.user.id);
  json(res, 200, { messages: rows });
});
app.post("/v1/messages/ack", auth, (req, res) => {
  const ids = Array.isArray(req.body?.ids) ? req.body.ids.filter(x => typeof x === "string").slice(0, 200) : [];
  const remove = db.prepare("DELETE FROM messages WHERE id=? AND recipient_id=?");
  const transaction = db.transaction(() => ids.forEach(id => remove.run(id, req.user.id)));
  transaction();
  json(res, 200, { acknowledged: ids.length });
});

function deliver(userId, packet) {
  const sockets = online.get(userId);
  if (!sockets) return false;
  const data = JSON.stringify(packet);
  for (const ws of sockets) if (ws.readyState === WebSocket.OPEN) ws.send(data);
  return sockets.size > 0;
}
server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname !== "/v1/socket") return socket.destroy();
  const token = url.searchParams.get("token") || "";
  const user = db.prepare("SELECT id,name FROM users WHERE token_hash=?").get(hash(token));
  if (!user) return socket.destroy();
  wss.handleUpgrade(req, socket, head, ws => {
    ws.user = user;
    wss.emit("connection", ws);
  });
});
wss.on("connection", ws => {
  const id = ws.user.id;
  if (!online.has(id)) online.set(id, new Set());
  online.get(id).add(ws);
  ws.send(JSON.stringify({ type: "connected" }));
  ws.on("message", raw => {
    let packet;
    try { packet = JSON.parse(raw.toString()); } catch { return; }
    if (packet.type !== "message") return;
    const recipientId = typeof packet.recipientId === "string" ? packet.recipientId : "";
    const ciphertext = typeof packet.ciphertext === "string" ? packet.ciphertext : "";
    if (!recipientId || !ciphertext || ciphertext.length > 48000) return;
    if (!db.prepare("SELECT id FROM users WHERE id=?").get(recipientId)) {
      ws.send(JSON.stringify({ type: "error", error: "Recipient not found" })); return;
    }
    const message = { type: "message", id: crypto.randomUUID(), senderId: id, ciphertext, createdAt: Date.now() };
    db.prepare("INSERT INTO messages(id,sender_id,recipient_id,ciphertext,created_at) VALUES(?,?,?,?,?)")
      .run(message.id, id, recipientId, ciphertext, message.createdAt);
    deliver(recipientId, message);
    ws.send(JSON.stringify({ type: "accepted", id: message.id }));
  });
  ws.on("close", () => {
    const set = online.get(id);
    if (set) { set.delete(ws); if (!set.size) online.delete(id); }
  });
});
const port = Number(process.env.PORT || 8080);
server.listen(port, "0.0.0.0", () => console.log(`VAYNAX server listening on ${port}`));
