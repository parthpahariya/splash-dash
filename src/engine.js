export const LANES = [211, 337, 463, 589];
export const PLAYER_Y = 665;
export const MAX_HEALTH = 100;
export const JUMP_DURATION = 1.15;
export const SPAWN_GAP = 120;
export const PIRATE_GAP = 220;

export const PIRATES = Object.freeze({
  scout: { name: 'Scout', hp: 1, color: '#e9cf80', unlock: 1, reward: 125 },
  gunner: { name: 'Gunner', hp: 2, color: '#b85c52', unlock: 1, reward: 250 },
  burst: { name: 'Burst Raider', hp: 3, color: '#b990d4', unlock: 2, reward: 350 },
  scatter: { name: 'Scattershot', hp: 3, color: '#e69a53', unlock: 2, reward: 350 },
  sniper: { name: 'Sharpshooter', hp: 2, color: '#df6472', unlock: 3, reward: 300 },
  rammer: { name: 'Rammer', hp: 4, color: '#c6403e', unlock: 3, reward: 400 },
  minelayer: { name: 'Minelayer', hp: 3, color: '#86b879', unlock: 3, reward: 350 },
  ironclad: { name: 'Ironclad', hp: 6, color: '#9ab6c3', unlock: 4, reward: 600 },
  captain: { name: 'Pirate Captain', hp: 10, color: '#f3c75f', unlock: 5, reward: 1500 },
});

export class Game {
  constructor({ random = Math.random, onEvent = () => {} } = {}) {
    this.random = random;
    this.onEvent = onEvent;
    this.reset();
    this.status = 'ready';
  }

  reset() {
    this.status = 'playing';
    this.health = 100;
    this.lane = 1;
    this.x = LANES[this.lane];
    this.tilt = 0;
    this.jumps = 2;
    this.jumpTime = 0;
    this.invincible = 0;
    this.fireCooldown = 0;
    this.firing = false;
    this.distance = 0;
    this.bonus = 0;
    this.elapsed = 0;
    this.scroll = 0;
    this.level = 1;
    this.piratesSunk = 0;
    this.heartsCollected = 0;
    this.entities = [];
    this.bullets = [];
    this.particles = [];
    this.spawnTimer = 1.3;
    this.pickupTimer = 4.8;
    this.pirateTimer = 5;
    this.id = 0;
    this.lastCaptainDistance = -900;
    this.onEvent({ type: 'start' });
  }

  get score() { return Math.floor(this.distance * 2) + this.bonus; }
  get speed() { return 125 + Math.min(145, (this.level - 1) * 17); }
  get jumping() { return this.jumpTime > 0; }

  move(direction) {
    if (this.status !== 'playing') return;
    this.lane = Math.max(0, Math.min(3, this.lane + Math.sign(direction)));
  }

  jump() {
    if (this.status !== 'playing' || this.jumping || this.jumps === 0) return false;
    this.jumps--;
    this.jumpTime = JUMP_DURATION;
    this.onEvent({ type: 'jump' });
    return true;
  }

  fire(target = null) {
    if (this.status !== 'playing' || this.fireCooldown > 0) return false;
    if (target && (target.dead || !(target.type === 'pirate' && this.entities.includes(target) || target.mine && this.bullets.includes(target)))) return false;
    const x = this.x;
    const y = PLAYER_Y - 49;
    let vx = 0;
    let vy = -650;
    if (target) {
      const dx = target.x - x;
      const dy = target.y - y;
      const distance = Math.hypot(dx, dy);
      if (distance > 0) { vx = dx / distance * 650; vy = dy / distance * 650; }
    }
    this.bullets.push({ x, y, vx, vy, enemy: false, dead: false });
    this.fireCooldown = .25;
    this.onEvent({ type: 'fire' });
    return true;
  }

  pause() {
    if (this.status === 'playing') { this.status = 'paused'; this.firing = false; }
    else if (this.status === 'paused') this.status = 'playing';
  }

  damage(amount) {
    if (this.status !== 'playing' || this.invincible > 0 || this.jumping) return false;
    this.health = Math.max(0, this.health - amount);
    this.invincible = 1.15;
    this.burst(this.x, PLAYER_Y, '#ffb28b', 16);
    this.onEvent({ type: 'damage', amount });
    if (this.health === 0) {
      this.status = 'over';
      this.firing = false;
      this.onEvent({ type: 'over', score: this.score });
    }
    return true;
  }

  spawn(type, lane = Math.floor(this.random() * 4), y = -80, pirateType = 'gunner') {
    if (!PIRATES[pirateType]) return null;
    if (!['rock', 'pirate', 'heart'].includes(type)) return null;
    if (type === 'rock' || type === 'heart') {
      // Rocks and pickups move faster than pirates. Reserve occupied lanes
      // until the pirate leaves so faster objects cannot catch its hull.
      const availableLane = Array.from({ length: 4 }, (_, i) => (lane + i) % 4)
        .find(candidate => !this.entities.some(e => !e.dead && e.lane === candidate && e.type === 'pirate'));
      if (availableLane === undefined) return null;
      lane = availableLane;
    }
    // Keep consecutive hazards far enough apart to leave a navigable route.
    if ((type === 'rock' || type === 'pirate') && this.entities.some(e => !e.dead && (e.type === 'rock' || e.type === 'pirate') && e.y < 60 && Math.abs(e.lane - lane) < 1)) return null;
    const neighbors = this.entities.filter(e => !e.dead && e.lane === lane);
    if (type === 'pirate') {
      // Rammers need the lane to themselves, regardless of spawn order.
      if (neighbors.some(e => e.type === 'pirate' && (pirateType === 'rammer' || e.pirateType === 'rammer'))) return null;
      // A new pirate stays above all faster objects already in its lane.
      // Their separation increases as they move down the waterway.
      for (const e of neighbors) if (e.type === 'rock' || e.type === 'heart') y = Math.min(y, e.y - SPAWN_GAP);
    }
    // Place a new object above anything occupying its position. Rocks and
    // pickups share a speed, so this gap stays intact as they scroll.
    for (const neighbor of neighbors.sort((a, b) => b.y - a.y)) {
      const gap = type === 'pirate' && neighbor.type === 'pirate' ? PIRATE_GAP : SPAWN_GAP;
      if (Math.abs(neighbor.y - y) < gap) y = neighbor.y - gap;
    }
    const entity = { id: this.id++, type, lane, x: LANES[lane], y, pirateType, maxHp: PIRATES[pirateType].hp, hp: PIRATES[pirateType].hp, warning: 0, charging: false, burstLeft: 0, burstTimer: 0, attackStep: 0, aimX: this.x, cooldown: 1.5 + this.random(), dead: false, seed: this.random() * 10, hit: 0, flash: 0 };
    this.entities.push(entity);
    return entity;
  }

  choosePirate() {
    const pool = ['gunner', 'gunner', 'gunner', 'gunner', 'scout', 'scout', 'scout'];
    for (const [type, stats] of Object.entries(PIRATES)) {
      if (stats.unlock > this.level || ['scout', 'gunner'].includes(type)) continue;
      if (type === 'captain' && (this.distance - this.lastCaptainDistance < 900 || this.entities.some(e => !e.dead && e.type === 'pirate' && e.pirateType === 'captain'))) continue;
      pool.push(type);
      if (type !== 'captain') pool.push(type);
    }
    return pool[Math.min(pool.length - 1, Math.floor(this.random() * pool.length))];
  }

  enemyShot(e, { angle = 0, speed = 265 + this.level * 8, damage = 15, offset = 0, targetX = this.x } = {}) {
    const x = e.x + offset, y = e.y + 43;
    const direction = Math.atan2(PLAYER_Y - y, targetX - x) + angle;
    this.bullets.push({ x, y, vx: Math.cos(direction) * speed, vy: Math.sin(direction) * speed, enemy: true, damage, radius: damage > 15 ? 11 : 8, dead: false });
    e.flash = .12;
    this.onEvent({ type: 'enemyFire' });
  }

  updatePirate(e, dt) {
    const type = e.pirateType;
    if (e.charging) return;
    if (e.burstLeft > 0) {
      e.burstTimer -= dt;
      if (e.burstTimer <= 0) { this.enemyShot(e); e.burstLeft--; e.burstTimer = .18; }
      return;
    }
    if (e.warning > 0) {
      e.warning = Math.max(0, e.warning - dt);
      if (e.warning > 0) return;
      if (type === 'rammer') { e.charging = true; return; }
      if (type === 'sniper') { this.enemyShot(e, { speed: 480, damage: 25, targetX: e.aimX }); e.cooldown = 3.4; return; }
      if (type === 'captain') {
        if (e.attackStep % 2 === 0) { this.enemyShot(e); e.burstLeft = 2; e.burstTimer = .18; }
        else for (const angle of [-.26, 0, .26]) this.enemyShot(e, { angle });
        e.attackStep++;
        e.cooldown = e.hp <= 5 ? 1.6 : 2.7;
        return;
      }
    }
    e.cooldown -= dt;
    if (e.cooldown > 0) return;
    switch (type) {
      case 'sniper': e.aimX = this.x; e.warning = 1.1; break;
      case 'rammer': if (e.y < PLAYER_Y - 220) e.warning = .9; break;
      case 'captain': e.aimX = this.x; e.warning = .65; break;
      case 'minelayer':
        if (e.y < PLAYER_Y - 200) {
          this.bullets.push({ x: e.x, y: e.y + 80, vx: 0, vy: this.speed, enemy: true, mine: true, armTime: .8, damage: 25, radius: 18, dead: false });
          e.flash = .12; this.onEvent({ type: 'enemyFire' });
        }
        e.cooldown = 2.8; break;
      case 'scatter':
        for (const angle of [-.26, 0, .26]) this.enemyShot(e, { angle });
        e.cooldown = 3.2; break;
      case 'burst': this.enemyShot(e); e.burstLeft = 2; e.burstTimer = .18; e.cooldown = 3.2; break;
      case 'ironclad':
        for (const offset of [-14, 14]) this.enemyShot(e, { offset, speed: 190, damage: 25 });
        e.cooldown = 4; break;
      case 'scout': this.enemyShot(e); e.cooldown = 1.4; break;
      default: this.enemyShot(e); e.cooldown = Math.max(1.1, 2.6 - this.level * .1);
    }
  }

  burst(x, y, color, count = 10) {
    for (let i = 0; i < count; i++) {
      const a = this.random() * Math.PI * 2;
      const speed = 35 + this.random() * 95;
      this.particles.push({ x, y, vx: Math.cos(a) * speed, vy: Math.sin(a) * speed, life: .6, color, size: 2 + this.random() * 3 });
    }
  }

  update(dt) {
    if (this.status !== 'playing') return;
    dt = Math.min(.05, Math.max(0, dt));
    this.elapsed += dt;
    this.distance += dt * this.speed * .12;
    this.scroll += dt * this.speed;
    const nextLevel = 1 + Math.floor(this.distance / 300);
    if (nextLevel !== this.level) { this.level = nextLevel; this.onEvent({ type: 'level', level: nextLevel }); }
    const dx = LANES[this.lane] - this.x;
    this.x += dx * (1 - Math.exp(-15 * dt));
    this.tilt += (Math.max(-.23, Math.min(.23, dx / 170)) - this.tilt) * Math.min(1, dt * 12);
    this.jumpTime = Math.max(0, this.jumpTime - dt);
    this.invincible = Math.max(0, this.invincible - dt);
    this.fireCooldown = Math.max(0, this.fireCooldown - dt);
    if (this.firing) this.fire();

    this.spawnTimer -= dt;
    this.pickupTimer -= dt;
    this.pirateTimer -= dt;
    if (this.spawnTimer <= 0) { this.spawn('rock'); this.spawnTimer = Math.max(.9, 1.8 - this.level * .07) + this.random() * .5; }
    if (this.pickupTimer <= 0) { if (this.random() < .58) this.spawn('heart'); this.pickupTimer = 4 + this.random() * 3; }
    if (this.pirateTimer <= 0) { const type = this.choosePirate(); const pirate = this.spawn('pirate', undefined, undefined, type); if (pirate && type === 'captain') this.lastCaptainDistance = this.distance; this.pirateTimer = Math.max(3, 7 - this.level * .35) + this.random() * 2; }

    for (const e of this.entities) {
      let nextY = e.y + this.speed * dt * (e.type === 'pirate' ? (e.charging ? 2.8 : e.pirateType === 'captain' ? .32 : .44) : 1);
      if (e.type === 'pirate') for (const ahead of this.entities) {
        if (ahead.dead || ahead.lane !== e.lane || ahead.y <= e.y) continue;
        if (ahead.type === 'pirate') nextY = Math.min(nextY, ahead.y - PIRATE_GAP);
        else if (e.charging) nextY = Math.min(nextY, ahead.y - SPAWN_GAP);
      }
      e.y = Math.max(e.y, nextY);
      e.hit = Math.max(0, e.hit - dt);
      e.flash = Math.max(0, e.flash - dt);
      if (e.type === 'pirate' && e.y > 45 && !e.dead && (e.y < PLAYER_Y - 90 || e.warning > 0 || e.burstLeft > 0)) this.updatePirate(e, dt);
      if (!e.dead && Math.abs(e.x - this.x) < 43 && Math.abs(e.y - PLAYER_Y) < (e.type === 'pirate' ? 64 : 42)) {
        if (e.type === 'heart') {
          this.health = Math.min(MAX_HEALTH, this.health + 10);
          this.heartsCollected++;
          this.bonus += 50;
          this.burst(e.x, e.y, '#ffabc0');
          this.onEvent({ type: 'heart' });
          e.dead = true;
        } else if (!this.jumping && this.damage(e.type === 'rock' ? 25 : e.pirateType === 'rammer' && e.charging ? 40 : 30)) e.dead = true;
        if (this.status === 'over') return;
      }
      if (e.y > 900) e.dead = true;
    }

    for (const b of this.bullets) {
      if (b.dead) continue;
      if (b.mine) b.armTime = Math.max(0, b.armTime - dt);
      b.x += (b.vx || 0) * dt;
      b.y += b.vy * dt;
      if (b.enemy) {
        // Airborne boats clear the shot. Keep it moving so it remains visible
        // beneath the jump and continues out of the waterway.
        if (!this.jumping && (!b.mine || b.armTime === 0) && Math.abs(b.x - this.x) < 20 + (b.radius || 8) && Math.abs(b.y - PLAYER_Y) < 34 + (b.radius || 8)) {
          this.damage(b.damage || 15);
          b.dead = true;
          if (this.status === 'over') return;
        }
      } else {
        const mine = this.bullets.find(m => m.mine && !m.dead && Math.abs(m.x - b.x) < 24 && Math.abs(m.y - b.y) < 24);
        if (mine) { mine.dead = true; b.dead = true; this.burst(mine.x, mine.y, '#a6db92', 8); this.onEvent({ type: 'hit' }); continue; }
        for (const e of this.entities) {
          if (e.type !== 'pirate' || e.dead || Math.abs(e.x - b.x) > 34 || Math.abs(e.y - b.y) > 52) continue;
          e.hp--;
          e.hit = .13;
          b.dead = true;
          this.burst(e.x, b.y, '#ffdc9e', 5);
          if (e.hp <= 0) {
            e.dead = true;
            this.piratesSunk++;
            this.bonus += PIRATES[e.pirateType].reward;
            this.burst(e.x, e.y, '#ffc183', 22);
            this.onEvent({ type: 'sink', reward: PIRATES[e.pirateType].reward, name: PIRATES[e.pirateType].name });
          } else this.onEvent({ type: 'hit' });
          break;
        }
      }
      if (b.x < -30 || b.x > 830 || b.y < -30 || b.y > 850) b.dead = true;
    }
    this.entities = this.entities.filter(e => !e.dead);
    this.bullets = this.bullets.filter(b => !b.dead);
    for (const p of this.particles) { p.x += p.vx * dt; p.y += (p.vy + this.speed * .3) * dt; p.life -= dt; }
    this.particles = this.particles.filter(p => p.life > 0);
  }
}
