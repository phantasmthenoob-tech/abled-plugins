/*
 * Joins snow.playwithbao.com:25565 as an offline-mode player, answers AuthMe's
 * register/login prompt, idles for five minutes and leaves.
 *
 *   node join.js                     # defaults from the constants below
 *   MC_MINUTES=5 node join.js        # stay time
 *   MC_PASSWORD=whatever node join.js
 *
 * Everything the server says is printed with a timestamp, so the log shows whether the
 * registration was accepted ("Successfully registered") or refused.
 */
'use strict';

const mineflayer = require('mineflayer');
const { ping } = require('minecraft-protocol');

const HOST = process.env.MC_HOST || 'snow.playwithbao.com';
const PORT = Number(process.env.MC_PORT || 25565);
const USERNAME = process.env.MC_USERNAME || 'freebuff';
// Anything works for a fresh AuthMe account; fixed so the log states what was used.
const PASSWORD = process.env.MC_PASSWORD || 'Freebuff-9x31Qa';
const STAY_MS = Number(process.env.MC_MINUTES || 5) * 60 * 1000;

const startedAt = Date.now();
const log = (...parts) => console.log(`[${new Date().toISOString()}]`, ...parts);
const elapsed = () => `${Math.round((Date.now() - startedAt) / 1000)}s`;

async function detectVersion() {
  try {
    const info = await ping({ host: HOST, port: PORT, closeTimeout: 15000 });
    const version = info && info.version && info.version.name;
    log(`status ping ok: ${version || 'unknown version'} | motd: ${JSON.stringify(info && info.description)}`
      + ` | players ${info && info.players ? info.players.online : '?'}`);
    return version;
  } catch (error) {
    log(`status ping failed (${error.code || error.message}) - letting mineflayer pick the version`);
    return undefined;
  }
}

(async () => {
  log(`joining ${HOST}:${PORT} as "${USERNAME}" for ${STAY_MS / 60000} minute(s)`);
  const detected = await detectVersion();

  const bot = mineflayer.createBot({
    host: HOST,
    port: PORT,
    username: USERNAME,
    auth: 'offline',
    version: detected,
    hideErrors: false,
  });

  // AuthMe accepts /register <pw> <pw> for a new name and /login <pw> afterwards. Both are sent
  // once per prompt so a repeated hint cannot flood the server with commands.
  const answered = new Set();
  const answer = (command) => {
    if (answered.has(command)) return;
    answered.add(command);
    log(`sending ${command}`);
    bot.chat(command);
  };

  const handleText = (text) => {
    const flat = String(text || '').replace(/\s+/g, ' ').trim();
    if (!flat) return;
    log('chat:', flat);
    const lower = flat.toLowerCase();
    if (lower.includes('/register') || lower.includes('please register') || lower.includes('register with')) {
      answer(`/register ${PASSWORD}`);
    } else if (lower.includes('/login') || lower.includes('please log in') || lower.includes('please login')) {
      answer(`/login ${PASSWORD}`);
    }
  };

  bot.on('messagestr', handleText);
  bot.on('message', (json) => {
    // Raw components catch text a plugin sent as a title/actionbar rather than chat.
    try {
      const text = JSON.stringify(json);
      if (/register|login/i.test(text)) log('raw message:', text.slice(0, 400));
    } catch { /* ignore unprintable components */ }
  });

  // 1.21.6+ servers can ask for credentials in a dialog instead of chat; mineflayer has no
  // dialog support, so the packet is dumped for the operator rather than silently ignored.
  bot._client.on('packet', (data, meta) => {
    if (/dialog/i.test(meta.name)) {
      log(`dialog packet ${meta.name}:`, JSON.stringify(data).slice(0, 600));
      log('this server asks for the password in a dialog; mineflayer cannot answer it - '
        + 'run /register in game once, then relog with MC_PASSWORD set to that password');
    }
  });

  bot.once('spawn', () => {
    log(`spawned at ${JSON.stringify(bot.entity && bot.entity.position)} on ${bot.version}`);
    // Staying exactly five minutes is the request: idle, log a heartbeat, then leave.
    const heartbeat = setInterval(() => {
      log(`in game ${elapsed()} | health ${bot.health} | food ${bot.food} | position `
        + `${JSON.stringify(bot.entity && bot.entity.position && bot.entity.position.floored())}`);
    }, 60000);
    setTimeout(() => {
      clearInterval(heartbeat);
      log(`5 minute stay done (${elapsed()} in game) - leaving`);
      try { bot.quit('stay finished'); } catch { /* already gone */ }
      setTimeout(() => process.exit(0), 2000);
    }, STAY_MS);
  });

  bot.on('kicked', (reason, loggedIn) => {
    log(`kicked (loggedIn=${loggedIn}):`, JSON.stringify(reason).slice(0, 500));
  });
  bot.on('error', (error) => log('error:', error.code || '', error.message));
  bot.on('end', (reason) => {
    log(`connection ended: ${reason}`);
    process.exit(answered.size > 0 ? 0 : 1);
  });
})();
