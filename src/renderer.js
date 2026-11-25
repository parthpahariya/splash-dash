import { LANES, PLAYER_Y, JUMP_DURATION } from './engine.js';

const TAU = Math.PI * 2;
function ellipse(c, x, y, rx, ry, color) { c.fillStyle = color; c.beginPath(); c.ellipse(x, y, rx, ry, 0, 0, TAU); c.fill(); }
function path(c, points, fill, stroke, width = 1) { c.beginPath(); points.forEach(([x, y], i) => i ? c.lineTo(x, y) : c.moveTo(x, y)); c.closePath(); if (fill) { c.fillStyle = fill; c.fill(); } if (stroke) { c.strokeStyle = stroke; c.lineWidth = width; c.stroke(); } }
function line(c, points, color, width = 1) { c.beginPath(); points.forEach(([x, y], i) => i ? c.lineTo(x, y) : c.moveTo(x, y)); c.strokeStyle = color; c.lineWidth = width; c.stroke(); }
function roundRect(c, x, y, w, h, r, color) { c.fillStyle = color; c.beginPath(); c.roundRect(x, y, w, h, r); c.fill(); }
function rand(n) { const v = Math.sin(n * 78.233 + 12.9898) * 43758.5453; return v - Math.floor(v); }

export class Renderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.c = canvas.getContext('2d');
    this.reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(canvas);
    this.resize();
  }
  resize() {
    const rect = this.canvas.getBoundingClientRect();
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    this.canvas.width = Math.round(rect.width * ratio);
    this.canvas.height = Math.round(rect.height * ratio);
  }
  draw(game, time) {
    const c = this.c;
    // Run effects on simulation time so pauses and game over freeze the scene.
    if (game.status !== 'ready') time = game.elapsed;
    c.setTransform(this.canvas.width / 800, 0, 0, this.canvas.height / 800, 0, 0);
    const scroll = game.status === 'ready' ? (this.reduced ? 0 : time * 14) : game.scroll;
    const water = c.createLinearGradient(0, 0, 800, 800);
    water.addColorStop(0, '#318e94'); water.addColorStop(.5, '#278b92'); water.addColorStop(1, '#277f89');
    c.fillStyle = water; c.fillRect(0, 0, 800, 800);
    c.save();
    this.water(c, scroll, time);
    this.shore(c, scroll, time);
    c.save();
    c.setLineDash([7, 13]);
    for (let i = 0; i < 5; i++) {
      const x = 148 + i * 126;
      line(c, [[x, 0], [x, 800]], i === 0 || i === 4 ? '#c3eadb16' : '#ccf9e72b', 1);
    }
    c.restore();
    if (game.status === 'ready') {
      this.rock(c, LANES[0], 228, .8, 2);
      this.rock(c, LANES[3], 541, .9, 4);
      this.rock(c, LANES[2], 55, .6, 5);
      this.pirate(c, LANES[3], 171, time);
      this.heart(c, LANES[0], 481, time, 0);
    }
    for (const e of game.entities) {
      if (e.type === 'rock') this.rock(c, e.x, e.y, 1, e.seed);
      else if (e.type === 'pirate') this.pirate(c, e.x, e.y, time, e.hit, e.hp, game.x, e.flash);
      else this.heart(c, e.x, e.y, time, e.seed);
    }
    for (const b of game.bullets) {
      if (b.enemy) {
        const speed = Math.hypot(b.vx || 0, b.vy) || 1;
        const tailX = b.x - (b.vx || 0) / speed * 36;
        const tailY = b.y - b.vy / speed * 36;
        line(c, [[tailX, tailY], [b.x, b.y]], '#f5ac713b', 7);
        line(c, [[tailX, tailY], [b.x, b.y]], '#ffd09caa', 3);
        ellipse(c, b.x, b.y, 13, 15, '#ffc08933');
        ellipse(c, b.x, b.y, 8, 9, '#e9af78');
        ellipse(c, b.x, b.y, 6.5, 7.5, '#262e36');
        ellipse(c, b.x - 2, b.y - 3, 2.5, 2.5, '#ffe4b9');
      } else {
        c.save();
        c.translate(b.x, b.y);
        c.rotate(Math.atan2(b.vy, b.vx || 0) + Math.PI / 2);
        line(c, [[0, 20], [0, 0]], '#fee4b966', 3);
        roundRect(c, -3, -7, 6, 15, 3, '#fff0c6');
        c.restore();
      }
    }
    for (const p of game.particles) {
      c.globalAlpha = Math.min(1, p.life * 2);
      ellipse(c, p.x, p.y, p.size, p.size, p.color);
    }
    c.globalAlpha = 1;
    const jumpHeight = game.jumping ? Math.sin((1 - game.jumpTime / JUMP_DURATION) * Math.PI) : 0;
    if (game.status !== 'over') this.boat(c, game.x, PLAYER_Y, game.tilt, jumpHeight, game.invincible, time);
    else {
      ellipse(c, game.x, PLAYER_Y, 45, 27, '#c3f3df44');
      line(c, [[game.x - 20, PLAYER_Y], [game.x + 10, PLAYER_Y + 12]], '#eecaa0', 6);
      line(c, [[game.x + 15, PLAYER_Y - 15], [game.x + 28, PLAYER_Y + 5]], '#f6b27b', 5);
    }
    c.restore();
    const vignette = c.createRadialGradient(400, 350, 200, 400, 400, 560);
    vignette.addColorStop(0, '#06373e00'); vignette.addColorStop(1, '#06373e22');
    c.fillStyle = vignette; c.fillRect(0, 0, 800, 800);
  }

  water(c, scroll, time) {
    for (let i = 0; i < 85; i++) {
      const x = 20 + rand(i + 10) * 760;
      const y = (rand(i + 80) * 840 + scroll * .65) % 840 - 20;
      const w = 6 + rand(i + 36) * 17;
      const alpha = .09 + rand(i + 2) * .12;
      line(c, [[x - w, y], [x, y + 2], [x + w, y]], `rgba(193,242,228,${alpha})`, 1.7);
      if (i % 4 === 0) line(c, [[x - w + 3, y + 7], [x + w - 4, y + 7]], '#b8e3d21a', 1);
    }
    if (!this.reduced) {
      for (let i = 0; i < 14; i++) {
        const x = 160 + rand(i + 83) * 480;
        const y = (rand(i + 99) * 900 + scroll * .8) % 900 - 50;
        const a = Math.max(0, Math.sin(time * 1.4 + i * 3)) * .16;
        line(c, [[x - 2, y], [x + 2, y]], `rgba(234,255,226,${a})`, 2);
      }
    }
  }

  shore(c, scroll, time) {
    // Repeating island clusters live outside the four navigable lanes.
    for (let i = -1; i < 3; i++) {
      const y = i * 510 + scroll * .55 % 510;
      this.island(c, -13, y + 45, 1, 1);
      this.island(c, 817, y + 290, -1, 4);
    }
    // Gentle foam at the outer edges gives the channel some depth.
    for (let i = 0; i < 8; i++) {
      const y = (i * 147 + scroll * .62) % 1050 - 120;
      ellipse(c, 43, y, 12, 5, '#d2f2d126');
      ellipse(c, 769, y + 70, 10, 5, '#d2f2d122');
    }
  }

  island(c, x, y, mirror, seed) {
    c.save(); c.translate(x, y); c.scale(mirror, 1);
    const shape = [[-70, -145], [37, -153], [69, -119], [88, -63], [112, -26], [105, 15], [131, 57], [105, 99], [91, 147], [44, 175], [-70, 169]];
    c.save(); c.translate(5, 8); path(c, shape, '#77c8b966'); c.restore();
    c.save(); c.translate(0, 2); c.scale(1.05, 1.035); path(c, shape, '#bae4c77a'); c.restore();
    path(c, shape, '#e5d39b', '#eee0ad', 3);
    c.save(); c.scale(.82, .92); path(c, shape, '#819c68'); c.restore();
    c.save(); c.translate(-8, -3); c.scale(.75, .85); path(c, shape, '#739565'); c.restore();
    for (let j = 0; j < 16; j++) {
      const px = rand(j + seed * 100) * 78 - 5, py = rand(j + seed * 111) * 245 - 120;
      ellipse(c, px, py, 4 + rand(j + 20) * 6, 3, j % 2 ? '#8da373' : '#63895e');
    }
    this.palm(c, 38, -77, .85, -.2);
    this.palm(c, 47, 80, 1, .3);
    this.palm(c, 4, 11, .73, -.7);
    this.smallRock(c, 89, 50, .45);
    this.smallRock(c, 52, 148, .3);
    ellipse(c, 85, -54, 3, 2, '#b3af7d'); ellipse(c, 96, 87, 4, 2, '#b3af7d');
    c.restore();
  }

  palm(c, x, y, scale, angle) {
    c.save(); c.translate(x, y); c.rotate(angle); c.scale(scale, scale);
    ellipse(c, 10, 16, 31, 15, '#294c4438');
    line(c, [[1, 20], [-3, 5], [0, -14]], '#665c40', 6);
    line(c, [[1, 18], [-1, 3], [2, -13]], '#b49b5e', 3);
    for (let i = 0; i < 7; i++) {
      c.save(); c.translate(0, -12); c.rotate(i * TAU / 7);
      c.beginPath(); c.moveTo(0, 0); c.bezierCurveTo(4, -20, 19, -26, 31, -28); c.bezierCurveTo(13, -17, 15, -7, 0, 0); c.fillStyle = i % 2 ? '#386e55' : '#477f56'; c.fill();
      line(c, [[0, 0], [15, -15], [28, -26]], '#86a66a77', 1);
      c.restore();
    }
    ellipse(c, 0, -12, 4, 4, '#9b8351'); c.restore();
  }

  smallRock(c, x, y, scale) { this.rock(c, x, y, scale, 1); }

  rock(c, x, y, scale = 1, seed = 0) {
    c.save(); c.translate(x, y); c.scale(scale, scale);
    ellipse(c, 1, 16, 45, 27, '#a6e4d733');
    ellipse(c, 0, 13, 38, 23, '#104e586b');
    c.save(); c.setLineDash([20, 8]); c.strokeStyle = '#bce9dba3'; c.lineWidth = 2; c.beginPath(); c.ellipse(0, 13, 43, 26, .1, 0, TAU); c.stroke(); c.restore();
    const shape = [[-32, 13], [-29, -12], [-11, -33], [14, -29], [34, -6], [37, 20], [11, 30], [-16, 26]];
    path(c, shape, '#6c817c', '#506c6c', 2);
    path(c, [[-29, -12], [-11, -33], [14, -29], [5, -5], [-12, 3]], '#adba9f');
    path(c, [[14, -29], [34, -6], [37, 20], [10, 12], [5, -5]], '#8d9d8e');
    path(c, [[-32, 13], [-12, 3], [5, -5], [10, 12], [-16, 26]], '#83988b');
    line(c, [[-9, -27], [-20, -10], [-17, -6]], '#d1d5b8', 2);
    ellipse(c, 11, 13, 9, 4, '#617e70');
    if (seed > 2) { ellipse(c, -12, -8, 7, 4, '#91a273'); ellipse(c, -17, -10, 5, 3, '#a5b589'); }
    c.restore();
  }

  boat(c, x, y, tilt, jumpHeight, invincible, time) {
    const wake = this.reduced ? 0 : Math.sin(time * 13) * 3;
    if (jumpHeight < .2) {
      path(c, [[x - 17, y + 30], [x - 42, y + 132], [x, y + 108], [x + 42, y + 132], [x + 17, y + 30]], '#d5f8e820');
      line(c, [[x - 23, y + 46], [x - 31 - wake, y + 91], [x - 41, y + 131]], '#d5f8e86b', 2.5);
      line(c, [[x + 23, y + 46], [x + 31 + wake, y + 91], [x + 41, y + 131]], '#d5f8e86b', 2.5);
      for (let i = 0; i < 5; i++) { const yy = (time * 120 + i * 24) % 110; ellipse(c, x + Math.sin(i * 2) * 9, y + 38 + yy, 3 + yy / 20, 3, '#d3f6e844'); }
    }
    ellipse(c, x + 6 + jumpHeight * 12, y + 13, 26 + jumpHeight * 5, 49 - jumpHeight * 8, '#08495766');
    c.save(); c.translate(x, y - jumpHeight * 80); c.rotate(tilt); c.scale(1 + jumpHeight * .19, 1 + jumpHeight * .19);
    if (invincible > 0 && Math.floor(time * 14) % 2 === 0) c.globalAlpha = .5;
    path(c, [[0, -61], [-14, -43], [-23, -7], [-24, 28], [-20, 45], [0, 53], [20, 45], [24, 28], [23, -7], [14, -43]], '#f39761', '#ffd2a1', 2);
    path(c, [[0, -50], [-12, -30], [-17, -3], [-15, 31], [0, 40], [15, 31], [17, -3], [12, -30]], '#f4e7be');
    path(c, [[-15, -10], [0, -17], [15, -10], [14, 5], [-14, 5]], '#35696b', '#d4e1bf', 1);
    line(c, [[-11, -10], [-9, 1]], '#86b6a5', 2);
    roundRect(c, -14, 5, 28, 21, 4, '#fff1d0');
    line(c, [[0, -45], [0, -24]], '#cda77a', 2);
    line(c, [[-10, 32], [10, 32]], '#d1ac7d', 2);
    roundRect(c, -8, 44, 16, 14, 3, '#193c44');
    roundRect(c, -3, -63, 6, 15, 2, '#1a4048');
    if (jumpHeight > .1) { c.strokeStyle = '#e5fcdf99'; c.lineWidth = 2; c.beginPath(); c.ellipse(0, 0, 34, 66, 0, -.6, .6); c.stroke(); }
    c.restore();
  }

  pirate(c, x, y, time, hit = 0, hp = 2, targetX = x, flash = 0) {
    c.save(); c.translate(x, y);
    ellipse(c, 5, 13, 32, 56, '#0f4e5566');
    line(c, [[-27, -20], [-36, -59]], '#d2f0df55', 2); line(c, [[27, -20], [36, -59]], '#d2f0df55', 2);
    path(c, [[0, 59], [-21, 35], [-29, -5], [-22, -45], [0, -53], [22, -45], [29, -5], [21, 35]], hit ? '#ffe1ad' : '#684b3d', '#be8a63', 2);
    path(c, [[0, 47], [-15, 28], [-20, -5], [-15, -36], [15, -36], [20, -5], [15, 28]], '#b2865b');
    for (let i = -2; i < 4; i++) line(c, [[-15, i * 10], [15, i * 10]], '#5c4e4080', 1);
    c.save(); c.translate(0, 43);
    c.rotate(Math.atan2(PLAYER_Y - y - 43, targetX - x) - Math.PI / 2);
    roundRect(c, -6, -4, 12, 21, 2, '#272f33');
    if (flash > 0) {
      ellipse(c, 0, 23, 15, 18, '#ffc36b55');
      path(c, [[0, 38], [-5, 25], [-14, 23], [-6, 18], [0, 9], [6, 18], [14, 23], [5, 25]], '#ffe5a9');
    }
    c.restore();
    line(c, [[0, -40], [0, 24]], '#523e32', 4);
    path(c, [[-30, -28], [28, -28], [22, 19], [0, 13], [-22, 19]], hit ? '#ffd1a1' : '#a04b45', '#d18469', 1.5);
    path(c, [[-30, -28], [-20, -16], [20, -16], [28, -28]], '#bd6654');
    // Tiny skull on a red sail, facing the captain.
    ellipse(c, 0, -3, 8, 8, '#f4dfba');
    roundRect(c, -5, 1, 10, 8, 1, '#f4dfba');
    ellipse(c, -3, -3, 2, 2, '#8d4841'); ellipse(c, 3, -3, 2, 2, '#8d4841');
    line(c, [[-10, 12], [10, 17]], '#f4dfba', 2); line(c, [[10, 12], [-10, 17]], '#f4dfba', 2);
    if (hp === 1) { c.strokeStyle = '#2c3735'; c.lineWidth = 2; c.beginPath(); c.moveTo(-24, -9); c.lineTo(-14, -3); c.lineTo(-21, 9); c.stroke(); }
    c.restore();
  }

  heart(c, x, y, time, seed) {
    c.save(); c.translate(x, y + (this.reduced ? 0 : Math.sin(time * 2.5 + seed) * 4));
    const color = '#ffa3b2';
    ellipse(c, 0, 13, 25, 11, '#ffb8c02a');
    c.beginPath(); c.arc(0, 0, 25, 0, TAU); c.fillStyle = '#cf758d30'; c.fill();
    c.strokeStyle = '#ffd3c67a'; c.lineWidth = 1; c.stroke();
    c.beginPath(); c.arc(0, 0, 20, 0, TAU); c.fillStyle = '#895565'; c.fill();
    c.strokeStyle = color; c.lineWidth = 1.5; c.stroke();
    c.beginPath(); c.moveTo(0, 11); c.bezierCurveTo(-23, -4, -9, -20, 0, -9); c.bezierCurveTo(9, -20, 23, -4, 0, 11); c.fillStyle = color; c.fill();
    line(c, [[-9, -6], [-6, -9]], '#ffe3cf', 2);
    c.restore();
  }
}
