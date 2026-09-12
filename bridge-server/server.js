import express from 'express';

const app = express();
const port = process.env.PORT || 3000;
const maxAgeMs = 24 * 60 * 60 * 1000;
const results = new Map();

app.disable('x-powered-by');
app.use(express.json({ limit: '2mb' }));

function validToken(token) {
  return typeof token === 'string' && /^[A-Za-z0-9_-]{32,160}$/.test(token);
}

app.get('/health', (_req, res) => {
  res.json({ ok: true, service: 'aria-android-bridge-relay' });
});

app.post('/v1/result/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });

  const payload = {
    receivedAt: new Date().toISOString(),
    body: req.body ?? null
  };
  results.set(token, payload);
  res.json({ ok: true });
});

app.get('/v1/result/:token', (req, res) => {
  const { token } = req.params;
  if (!validToken(token)) return res.status(400).json({ ok: false, error: 'invalid token' });

  const item = results.get(token);
  if (!item) return res.status(404).json({ ok: false, error: 'no result yet' });
  res.set('Cache-Control', 'no-store');
  res.json({ ok: true, ...item });
});

setInterval(() => {
  const now = Date.now();
  for (const [token, item] of results.entries()) {
    const time = Date.parse(item.receivedAt);
    if (!Number.isNaN(time) && now - time > maxAgeMs) results.delete(token);
  }
}, 60 * 60 * 1000).unref();

app.listen(port, '0.0.0.0', () => {
  console.log(`ARIA bridge relay listening on ${port}`);
});
