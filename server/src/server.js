import express from "express";
import http from "node:http";
import crypto from "node:crypto";
import Database from "better-sqlite3";
import { WebSocketServer, WebSocket } from "ws";

const app = express();
app.disable("x-powered-by");
app.use(express.json({ limit: "64kb", strict: true }));
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
const db = new Database(process.env.DB_PATH || "./vaynax.sqlite");
db.pragma("journal_mode = WAL");
db.pragma("foreign_keys = ON");
db.exec(`
CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS invites (code TEXT PRIMARY KEY, owner_id TEXT NOT NULL, expires_at INTEGER NOT NULL, used_by TEXT);
CREATE TABLE IF NOT EXISTS contacts (user_id TEXT NOT NULL, contact_id TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(user_id, contact_id));
CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, sender_id TEXT NOT NULL, recipient_id TEXT NOT NULL, ciphertext TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS messages_recipient ON messages(recipient_id, created_at);
CREATE INDEX IF NOT EXISTS invites_expiry ON invites(expires_at, used_by);
`);
// Upgrade databases created by earlier versions without losing stored invites.
const inviteColumns = db.pragma("table_info(invites)").map(column => column.name);
if (!inviteColumns.includes("used_by")) db.exec("ALTER TABLE invites ADD COLUMN used_by TEXT");

const online = new Map();
const hash = value => crypto.createHash("sha256").update(value).digest("hex");
const json = (res, status, body) => res.status(status).json(body);

// Small per-process rate limiter. Put the service behind a trusted TLS proxy in production.
const rateBuckets = new Map();
function rateLimit({ windowMs = 60_000, max = 60 } = {}) {
  return (req, res, next) => {
    const key = `${req.ip}:${req.path}`;
    const now = Date.now();
    let bucket = rateBuckets.get(key);
    if (!bucket || now - bucket.startedAt >= windowMs) {
      bucket = { startedAt: now, count: 0 };
      rateBuckets.set(key, bucket);
    }
    bucket.count += 1;
    if (bucket.count > max) {
      res.set("Retry-After", String(Math.ceil((windowMs - (now - bucket.startedAt)) / 1000)));
      return json(res, 429, { error: "Too many requests. Try again shortly." });
    }
    if (rateBuckets.size > 10_000) {
      for (const [bucketKey, value] of rateBuckets) {
        if (now - value.startedAt >= windowMs) rateBuckets.delete(bucketKey);
      }
    }
    next();
  };
}
const registrationLimit = rateLimit({ windowMs: 60_000, max: 10 });
const inviteLimit = rateLimit({ windowMs: 60_000, max: 20 });

const auth = (req, res, next) => {
  const header = req.headers.authorization || "";
  const match = /^Bearer ([A-Za-z0-9_-]{20,})$/.exec(header);
  if (!match) return json(res, 401, { error: "Authentication required" });
  const user = db.prepare("SELECT id, name FROM users WHERE token_hash = ?").get(hash(match[1]));
  if (!user) return json(res, 401, { error: "Invalid token" });
  req.user = user;
  next();
};

app.get("/health", (_req, res) => json(res, 200, { ok: true, service: "VAYNAX", time: Date.now() }));
app.post("/v1/register", registrationLimit, (req, res) => {
  const name = typeof req.body?.name === "string" ? req.body.name.trim() : "";
  if (!name || name.length > 40) return json(res, 400, { error: "Name must be 1–40 characters" });
  const id = crypto.randomUUID();
  const token = crypto.randomBytes(32).toString("base64url");
  db.prepare("INSERT INTO users (id,name,token_hash,created_at) VALUES (?,?,?,?)").run(id, name, hash(token), Date.now());
  return json(res, 201, { id, name, token });
});

app.get("/v1/me", auth, (req, res) => json(res, 200, req.user));
app.post("/v1/invites", auth, inviteLimit, (req, res) => {
  const code = crypto.randomBytes(8).toString("hex").toUpperCase();
  const expiresAt = Date.now() + 7 * 24 * 60 * 60 * 1000;
  db.prepare("INSERT INTO invites(code,owner_id,expires_at) VALUES(?,?,?)").run(code, req.user.id, expiresAt);
  json(res, 201, { code, expiresAt });
});
app.post("/v1/invites/redeem", auth, inviteLimit, (req, res) => {
  const code = typeof req.body?.code === "string" ? req.body.code.trim().toUpperCase() : "";
  if (!/^[A-F0-9]{16}$/.test(code)) return json(res, 400, { error: "Invalid invite code format" });
  const invite = db.prepare("SELECT owner_id FROM invites WHERE code=? AND expires_at>? AND used_by IS NULL").get(code, Date.now());
  if (!invite) return json(res, 404, { error: "Invite not found or expired" });
  if (invite.owner_id === req.user.id) return json(res, 400, { error: "Cannot add yourself" });
  const peer = db.prepare("SELECT id,name FROM users WHERE id=?").get(invite.owner_id);
  if (!peer) return json(res, 404, { error: "Invite owner no longer exists" });
  const now = Date.now();
  const transaction = db.transaction(() => {
    const claimed = db.prepare("UPDATE invites SET used_by=? WHERE code=? AND used_by IS NULL AND expires_at>?").run(req.user.id, code, now);
    if (!claimed.changes) return false;
    db.prepare("INSERT OR IGNORE INTO contacts(user_id,contact_id,created_at) VALUES(?,?,?)").run(req.user.id, peer.id, now);
    db.prepare("INSERT OR IGNORE INTO contacts(user_id,contact_id,created_at) VALUES(?,?,?)").run(peer.id, req.user.id, now);
    return true;
  });
  if (!transaction()) return json(res, 409, { error: "Invite already used" });
  json(res, 200, { id: peer.id, name: peer.name });
});
app.get("/v1/contacts", auth, (req, res) => {
  const contacts = db.prepare("SELECT u.id,u.name,c.created_at AS addedAt FROM contacts c JOIN users u ON u.id=c.contact_id WHERE c.user_id=? ORDER BY c.created_at DESC").all(req.user.id);
  json(res, 200, { contacts });
});
app.post("/v1/messages/send", auth, rateLimit({ windowMs: 60_000, max: 120 }), (req, res) => {
  const recipientId = typeof req.body?.recipientId === "string" ? req.body.recipientId : "";
  const ciphertext = typeof req.body?.ciphertext === "string" ? req.body.ciphertext : "";
  if (!/^[0-9a-f-]{36}$/i.test(recipientId) || !ciphertext || ciphertext.length > 48_000) {
    return json(res, 400, { error: "Invalid message payload" });
  }
  if (recipientId === req.user.id) return json(res, 400, { error: "Cannot message yourself" });
  if (!db.prepare("SELECT 1 FROM contacts WHERE user_id=? AND contact_id=?").get(req.user.id, recipientId)) {
    return json(res, 403, { error: "Recipient is not an approved contact" });
  }
  const message = { id: crypto.randomUUID(), senderId: req.user.id, ciphertext, createdAt: Date.now() };
  db.prepare("INSERT INTO messages(id,sender_id,recipient_id,ciphertext,created_at) VALUES(?,?,?,?,?)")
    .run(message.id, req.user.id, recipientId, ciphertext, message.createdAt);
  deliver(recipientId, { type: "message", ...message });
  return json(res, 201, { id: message.id, accepted: true });
});
app.get("/v1/messages/pending", auth, (req, res) => {
  // Expire old queued payloads to prevent unbounded storage growth.
  db.prepare("DELETE FROM messages WHERE created_at < ?").run(Date.now() - 30 * 24 * 60 * 60 * 1000);
  const rows = db.prepare("SELECT id,sender_id AS senderId,ciphertext,created_at AS createdAt FROM messages WHERE recipient_id=? ORDER BY created_at LIMIT 200").all(req.user.id);
  json(res, 200, { messages: rows });
});
app.post("/v1/messages/ack", auth, (req, res) => {
  const ids = Array.isArray(req.body?.ids) ? [...new Set(req.body.ids.filter(x => typeof x === "string" && x.length <= 80))].slice(0, 200) : [];
  const remove = db.prepare("DELETE FROM messages WHERE id=? AND recipient_id=?");
  const transaction = db.transaction(() => ids.forEach(id => remove.run(id, req.user.id)));
  transaction();
  json(res, 200, { acknowledged: ids.length });
});

function deliver(userId, packet) {
  const sockets = online.get(userId);
  if (!sockets) return false;
  const data = JSON.stringify(packet);
  for (const ws of sockets) {
    if (ws.readyState === WebSocket.OPEN) ws.send(data);
  }
  return sockets.size > 0;
}
server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname !== "/v1/socket") return socket.destroy();
  const token = url.searchParams.get("token") || "";
  if (!/^[A-Za-z0-9_-]{20,}$/.test(token)) return socket.destroy();
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
    try { packet = JSON.parse(raw.toString()); } catch {
      ws.send(JSON.stringify({ type: "error", error: "Invalid JSON packet" }));
      return;
    }
    if (packet?.type !== "message") return;
    const recipientId = typeof packet.recipientId === "string" ? packet.recipientId : "";
    const ciphertext = typeof packet.ciphertext === "string" ? packet.ciphertext : "";
    if (!/^[0-9a-f-]{36}$/i.test(recipientId) || !ciphertext || ciphertext.length > 48_000) {
      ws.send(JSON.stringify({ type: "error", error: "Invalid message payload" }));
      return;
    }
    if (!db.prepare("SELECT 1 FROM contacts WHERE user_id=? AND contact_id=?").get(id, recipientId)) {
      ws.send(JSON.stringify({ type: "error", error: "Recipient is not an approved contact" }));
      return;
    }
    const message = { type: "message", id: crypto.randomUUID(), senderId: id, ciphertext, createdAt: Date.now() };
    db.prepare("INSERT INTO messages(id,sender_id,recipient_id,ciphertext,created_at) VALUES(?,?,?,?,?)")
      .run(message.id, id, recipientId, ciphertext, message.createdAt);
    deliver(recipientId, message);
    ws.send(JSON.stringify({ type: "accepted", id: message.id }));
  });
  ws.on("error", () => {});
  ws.on("close", () => {
    const set = online.get(id);
    if (set) { set.delete(ws); if (!set.size) online.delete(id); }
  });
});

// Return consistent JSON for malformed bodies and unexpected route errors.
app.use((err, _req, res, _next) => {
  if (err instanceof SyntaxError && "body" in err) return json(res, 400, { error: "Invalid JSON body" });
  if (err?.type === "entity.too.large") return json(res, 413, { error: "Request body too large" });
  console.error("Request failed:", err?.message || "unknown error");
  return json(res, 500, { error: "Internal server error" });
});

const port = Number(process.env.PORT || 8080);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT must be an integer from 1 to 65535");
server.listen(port, "0.0.0.0", () => console.log(`VAYNAX server listening on ${port}`));
