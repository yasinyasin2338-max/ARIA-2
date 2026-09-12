import express from 'express';
import crypto from 'crypto';

const app = express();
const port = process.env.PORT || 3000;
const maxAgeMs = 24 * 60 * 60 * 1000;
const sessions = new Map();

app.disable('x-powered-by');
app.use(express.json({ limit: '512kb' }));

function validToken(token) {
  return typeof token === 'string' && /^[A-Za-z0-9_-]{32,160}$/.test(token);
}

function sessionFor(token) {
  let s = sessions.get(token);
  if (!s) {
    s = { token, createdAt: Date.now(), heartbeat: null, command: null, result: null };
    sessions.set(token, s);
  }
  s.lastSeenAt = Date.now();
  return s;
}

function validCommand(body) {
  if (!body || typeof body !== 'object') return false;
  if (typeof body.id !== 'string' || body.id.length < 8 || body.id.length > 120) return false;
  const allowed = new Set(['ping', 'global_action', 'tap', 'swipe', 'get_ui', 'type_text']);
  if (!allowed.has(body.type)) return false;
  return true;
}

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'aria-android-bridge-relay', version: '0.2.0' });
});

app.post('/v1/heartbeat/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });
  const s = sessionFor(token);
  s.heartbeat = { receivedAt: new Date().toISOString(), body: req.body ?? null };
  res.json({ ok: true, serverTime: new Date().toISOString() });
});

app.get('/v1/status/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });
  const s = sessions.get(token);
  if (!s) return res.status(404).json({ ok: false, error: 'unknown session' });
  res.set('Cache-Control', 'no-store');
  res.json({
    ok: true,
    heartbeat: s.heartbeat,
    commandPending: Boolean(s.command),
    result: s.result
  });
});

app.post('/v1/command/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });
  if (!validCommand(req.body)) return res.status(400).json({ ok: false, error: 'invalid command' });
  const s = sessionFor(token);
  s.command = { ...req.body, queuedAt: new Date().toISOString() };
  s.result = null;
  res.json({ ok: true, queued: s.command.id });
});

app.get('/v1/command/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });
  const s = sessionFor(token);
  res.set('Cache-Control', 'no-store');
  if (!s.command) return res.status(204).end();
  const command = s.command;
  s.command = null;
  res.json({ ok: true, command });
});

app.post('/v1/result/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });
  const s = sessionFor(token);
  s.result = {
    receivedAt: new Date().toISOString(),
    body: req.body ?? null
  };
  res.json({ ok: true });
});

app.get('/v1/result/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });
  const s = sessions.get(token);
  if (!s || !s.result) return res.status(404).json({ ok: false, error: 'no result yet' });
  res.set('Cache-Control', 'no-store');
  res.json({ ok: true, ...s.result });
});

setInterval(() => {
  const now = Date.now();
  for (const [token, s] of sessions.entries()) {
    if (now - (s.lastSeenAt ?? s.createdAt) > maxAgeMs) sessions.delete(token);
  }
}, 60 * 60 * 1000).unref();

app.listen(port, '0.0.0.0', () => {
  console.log(`ARIA bridge relay listening on ${port}`);
});
