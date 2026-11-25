import { Game } from './engine.js';
import { Renderer } from './renderer.js';

const $ = id => document.getElementById(id);
const canvas = $('game');
const overlay = $('overlay');
const card = $('start-card');
const announcer = $('announcer');
const help = $('help-dialog');
const renderer = new Renderer(canvas);
let best = 0;
let sound = false;
let audio;
let noticeUntil = 0;
let resumeAfterHelp = false;
let previous = performance.now();
let hudElapsed = 0;
let directionTime = 0;
try { best = Number(localStorage.getItem('wake-runner-best')) || 0; sound = localStorage.getItem('wake-runner-sound') === 'true'; } catch {}
$('best-score').textContent = best.toLocaleString();

function tone(frequency, duration = .1, shape = 'sine', volume = .06, endFrequency) {
  if (!sound) return;
  try {
    audio ||= new (window.AudioContext || window.webkitAudioContext)();
    if (audio.state === 'suspended') audio.resume().catch(() => {});
    const oscillator = audio.createOscillator();
    const gain = audio.createGain();
    oscillator.type = shape;
    oscillator.frequency.setValueAtTime(frequency, audio.currentTime);
    if (endFrequency) oscillator.frequency.exponentialRampToValueAtTime(endFrequency, audio.currentTime + duration);
    gain.gain.setValueAtTime(volume, audio.currentTime);
    gain.gain.exponentialRampToValueAtTime(.001, audio.currentTime + duration);
    oscillator.connect(gain); gain.connect(audio.destination);
    oscillator.start(); oscillator.stop(audio.currentTime + duration);
    oscillator.onended = () => { oscillator.disconnect(); gain.disconnect(); };
  } catch { /* Audio may be unavailable; the visual game remains playable. */ }
}

function notice(text) {
  $('game-notice').textContent = text;
  announcer.textContent = text;
  noticeUntil = performance.now() + 1500;
}

const game = new Game({ onEvent(event) {
  switch (event.type) {
    case 'fire': tone(190, .07, 'triangle', .025, 75); break;
    case 'damage': tone(110, .22, 'sawtooth', .055, 45); notice(`HULL HIT  −${event.amount}`); break;
    case 'heart': tone(620, .18, 'sine', .07, 940); notice('A LITTLE LOVE  +10 HEALTH'); break;
    case 'jump': tone(240, .3, 'sine', .05, 720); notice('CATCHING SOME AIR'); break;
    case 'hit': tone(260, .08, 'square', .025, 120); break;
    case 'sink': tone(90, .28, 'sawtooth', .06, 40); notice(`${event.name || 'PIRATE'} SUNK  +${event.reward || 250}`); break;
    case 'level': tone(450, .2, 'triangle', .06, 800); notice(`LEVEL ${event.level}  ·  ROUGHER WATERS`); break;
    case 'over': endRun(); break;
  }
}});

function renderSound() {
  $('sound-button').innerHTML = `<svg><use href="#i-${sound ? 'sound' : 'mute'}"/></svg>`;
  $('sound-button').setAttribute('aria-label', `Turn sound ${sound ? 'off' : 'on'}`);
  $('sound-button').setAttribute('aria-pressed', String(sound));
}
renderSound();
$('sound-button').addEventListener('click', () => {
  sound = !sound;
  renderSound();
  try { localStorage.setItem('wake-runner-sound', String(sound)); } catch {}
  if (sound) tone(650, .12);
});

function updateHUD() {
  $('score').textContent = String(game.score).padStart(6, '0');
  $('distance').textContent = Math.floor(game.distance).toLocaleString();
  $('health-number').innerHTML = `${game.health}<span>%</span>`;
  $('health-bar').setAttribute('aria-valuenow', game.health);
  $('health-bar').querySelectorAll('i').forEach((segment, i) => {
    const fraction = Math.max(0, Math.min(1, (game.health - i * 20) / 20));
    const color = game.health <= 25 ? '#ff8e9f' : game.health <= 50 ? '#ffbd80' : '#7cddd0';
    segment.style.background = `linear-gradient(90deg,${color} ${fraction * 100}%,#304b52 ${fraction * 100}%)`;
  });
  $('jump-count').textContent = game.jumps;
  $('pirates-sunk').textContent = game.piratesSunk;
  $('hearts-collected').textContent = game.heartsCollected;
  $('wave-pill').innerHTML = `${game.level < 3 ? 'CALM WATERS' : game.level < 6 ? 'CHOPPY WATERS' : 'HIGH SEAS'} <span>LEVEL ${String(game.level).padStart(2, '0')}</span>`;
  const playing = game.status === 'playing';
  $('pause-button').disabled = game.status === 'ready' || game.status === 'over';
  $('pause-button').setAttribute('aria-label', playing ? 'Pause game' : 'Resume game');
  $('pause-button').innerHTML = `<svg><use href="#i-${game.status === 'paused' ? 'play' : 'pause'}"/></svg>`;
  $('jump-button').disabled = !playing || !game.jumps || game.jumping;
  $('fire-button').disabled = !playing;
  $('left-button').disabled = !playing;
  $('right-button').disabled = !playing;
}

function start() {
  game.reset();
  overlay.hidden = true;
  $('game-notice').textContent = '';
  noticeUntil = 0;
  updateHUD();
  canvas.focus({ preventScroll: true });
  tone(330, .2, 'triangle', .05, 650);
  announcer.textContent = 'Set sail! 100 health and two jumps. Steer with left and right arrows, fire with Space, jump with Up Arrow.';
}
$('start-button').addEventListener('click', start);

function endRun() {
  const isBest = game.score > best;
  if (isBest) {
    best = game.score;
    try { localStorage.setItem('wake-runner-best', String(best)); } catch {}
    $('best-score').textContent = best.toLocaleString();
  }
  card.innerHTML = `<div class="card-kicker${isBest ? ' new-best' : ''}"><span></span>${isBest ? 'A NEW PERSONAL BEST' : 'EVERY CAPTAIN HAS THEIR DAY'}</div><h1 class="result-title">WHAT A<br><span>VOYAGE.</span></h1><div class="result-label">FINAL SCORE</div><div class="result-score">${game.score.toLocaleString()}</div><div class="result-details"><span>${Math.floor(game.distance)} m sailed</span><span>${game.piratesSunk} pirates sunk</span></div><button id="restart-button" class="primary-button"><svg><use href="#i-play"/></svg> ONE MORE VOYAGE</button><div class="start-hint">PRESS <kbd>ENTER</kbd> TO TRY AGAIN</div>`;
  overlay.hidden = false;
  $('restart-button').addEventListener('click', start);
  $('restart-button').focus({ preventScroll: true });
  updateHUD();
  announcer.textContent = `Voyage complete. Score ${game.score}. ${Math.floor(game.distance)} meters sailed. Press Enter to try again.`;
}

function togglePause() {
  if (game.status !== 'playing' && game.status !== 'paused') return;
  game.pause();
  if (game.status === 'paused') {
    card.innerHTML = '<div class="card-kicker"><span></span>THE OCEAN CAN WAIT</div><h1 class="paused-title">TAKE A<br><span>BREATHER.</span></h1><p class="pause-copy">Your adventure is right where you left it.</p><button class="primary-button" id="resume-button"><svg><use href="#i-play"/></svg> BACK TO THE WATER</button><div class="start-hint">PRESS <kbd>P</kbd> TO RESUME</div>';
    overlay.hidden = false;
    $('resume-button').addEventListener('click', togglePause);
    $('resume-button').focus({ preventScroll: true });
    announcer.textContent = 'Game paused.';
  } else {
    overlay.hidden = true;
    canvas.focus({ preventScroll: true });
    announcer.textContent = 'Game resumed.';
  }
  updateHUD();
}
$('pause-button').addEventListener('click', togglePause);

$('help-button').addEventListener('click', () => {
  resumeAfterHelp = game.status === 'playing';
  if (resumeAfterHelp) togglePause();
  help.showModal();
});
$('close-help').addEventListener('click', () => help.close());
$('got-it').addEventListener('click', () => help.close());
help.addEventListener('click', event => { if (event.target === help) { const rect = help.getBoundingClientRect(); if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) help.close(); } });
help.addEventListener('close', () => { if (resumeAfterHelp && game.status === 'paused') togglePause(); resumeAfterHelp = false; });

$('left-button').addEventListener('click', () => game.move(-1));
$('right-button').addEventListener('click', () => game.move(1));
$('jump-button').addEventListener('click', () => { game.jump(); updateHUD(); });
$('fire-button').addEventListener('pointerdown', event => {
  if (game.status !== 'playing') return;
  event.preventDefault();
  $('fire-button').setPointerCapture(event.pointerId);
  game.firing = true;
  game.fire();
  $('fire-button').classList.add('is-pressed');
});
function stopFiring() { game.firing = false; $('fire-button').classList.remove('is-pressed'); }
$('fire-button').addEventListener('pointerup', stopFiring);
$('fire-button').addEventListener('pointercancel', stopFiring);
$('fire-button').addEventListener('lostpointercapture', stopFiring);
$('fire-button').addEventListener('click', event => { if (event.detail === 0) game.fire(); });

document.addEventListener('keydown', event => {
  if (help.open || event.ctrlKey || event.metaKey || event.altKey) return;
  const code = event.code;
  if (code === 'Enter' && !event.repeat && ['ready', 'over'].includes(game.status)) { event.preventDefault(); start(); return; }
  if (['KeyP', 'Escape'].includes(code) && !event.repeat) { event.preventDefault(); togglePause(); return; }
  if (game.status !== 'playing') return;
  // Let Space activate other focused native buttons without firing the cannon.
  if (event.target instanceof HTMLButtonElement && event.target.id !== 'fire-button' && code === 'Space') return;
  if (['ArrowLeft', 'KeyA', 'ArrowRight', 'KeyD'].includes(code)) {
    event.preventDefault();
    if (!event.repeat || performance.now() - directionTime > 160) { game.move(['ArrowLeft', 'KeyA'].includes(code) ? -1 : 1); directionTime = performance.now(); }
  } else if (code === 'Space') { event.preventDefault(); game.firing = true; game.fire(); }
  else if (code === 'ArrowUp') {
    event.preventDefault();
    if (!event.repeat) { game.jump(); updateHUD(); }
  }
});
document.addEventListener('keyup', event => { if (event.code === 'Space') stopFiring(); });
document.addEventListener('visibilitychange', () => { if (document.hidden && game.status === 'playing') togglePause(); });
window.addEventListener('blur', () => { stopFiring(); if (game.status === 'playing') togglePause(); });

function pirateAtPointer(event) {
  const rect = canvas.getBoundingClientRect();
  const x = (event.clientX - rect.left) / rect.width * 800;
  const y = (event.clientY - rect.top) / rect.height * 800;
  return [...game.entities].reverse().find(e => e.type === 'pirate' && !e.dead && Math.abs(e.x - x) <= 37 && Math.abs(e.y - y) <= 63) || game.bullets.find(b => b.mine && !b.dead && Math.abs(b.x - x) <= 25 && Math.abs(b.y - y) <= 25);
}

let gesture = null;
canvas.addEventListener('pointerdown', event => {
  if (game.status !== 'playing' || !event.isPrimary || event.button !== 0 || gesture) return;
  gesture = { id: event.pointerId, x: event.clientX, y: event.clientY, pirate: pirateAtPointer(event) };
  canvas.setPointerCapture(event.pointerId);
});
canvas.addEventListener('pointerup', event => {
  if (!gesture || gesture.id !== event.pointerId) return;
  const start = gesture;
  gesture = null;
  const dx = event.clientX - start.x;
  const dy = event.clientY - start.y;
  if (event.pointerType === 'touch' && Math.abs(dx) > 22 && Math.abs(dx) > Math.abs(dy)) game.move(Math.sign(dx));
  else if (Math.hypot(dx, dy) <= 12 && start.pirate && pirateAtPointer(event) === start.pirate) game.fire(start.pirate);
});
canvas.addEventListener('pointermove', event => {
  canvas.style.cursor = game.status === 'playing' && pirateAtPointer(event) ? 'crosshair' : 'default';
});
function cancelGesture(event) { if (gesture?.id === event.pointerId) gesture = null; }
canvas.addEventListener('pointercancel', cancelGesture);
canvas.addEventListener('lostpointercapture', cancelGesture);
canvas.addEventListener('pointerleave', () => {
  canvas.style.cursor = 'default';
});

function frame(now) {
  const dt = Math.min((now - previous) / 1000, .05);
  previous = now;
  game.update(dt);
  renderer.draw(game, now / 1000);
  hudElapsed += dt;
  if (hudElapsed > .08) { updateHUD(); hudElapsed = 0; }
  if (noticeUntil && now > noticeUntil) { $('game-notice').textContent = ''; noticeUntil = 0; }
  requestAnimationFrame(frame);
}
updateHUD();
requestAnimationFrame(frame);
