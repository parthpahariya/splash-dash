export const LANES = [211, 337, 463, 589];
export const PLAYER_Y = 665;
export const MAX_HEALTH = 100;
export const JUMP_DURATION = 1.15;
export const SPAWN_GAP = 120;

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
    if (target && (target.type !== 'pirate' || target.dead || !this.entities.includes(target))) return false;
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

  spawn(type, lane = Math.floor(this.random() * 4), y = -80) {
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
      // A new pirate stays above all faster objects already in its lane.
      // Their separation increases as they move down the waterway.
      for (const e of neighbors) if (e.type === 'rock' || e.type === 'heart') y = Math.min(y, e.y - SPAWN_GAP);
    }
    // Place a new object above anything occupying its position. Rocks and
    // pickups share a speed, so this gap stays intact as they scroll.
    for (const neighbor of neighbors.sort((a, b) => b.y - a.y)) {
      if (Math.abs(neighbor.y - y) < SPAWN_GAP) y = neighbor.y - SPAWN_GAP;
    }
    const entity = { id: this.id++, type, lane, x: LANES[lane], y, hp: 2, cooldown: 1.5 + this.random(), dead: false, seed: this.random() * 10, hit: 0, flash: 0 };
    this.entities.push(entity);
    return entity;
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
    if (this.pirateTimer <= 0) { this.spawn('pirate'); this.pirateTimer = Math.max(3, 7 - this.level * .35) + this.random() * 2; }

    for (const e of this.entities) {
      e.y += this.speed * dt * (e.type === 'pirate' ? .44 : 1);
      e.hit = Math.max(0, e.hit - dt);
      e.flash = Math.max(0, e.flash - dt);
      if (e.type === 'pirate' && e.y > 45 && e.y < PLAYER_Y - 90 && !e.dead) {
        e.cooldown -= dt;
        if (e.cooldown <= 0) {
          const x = e.x;
          const y = e.y + 43;
          const dx = this.x - x;
          const dy = PLAYER_Y - y;
          const distance = Math.hypot(dx, dy);
          const speed = 265 + this.level * 8;
          this.bullets.push({ x, y, vx: dx / distance * speed, vy: dy / distance * speed, enemy: true, dead: false });
          e.flash = .12;
          e.cooldown = Math.max(1.1, 2.6 - this.level * .1);
          this.onEvent({ type: 'enemyFire' });
        }
      }
      if (!e.dead && Math.abs(e.x - this.x) < 43 && Math.abs(e.y - PLAYER_Y) < (e.type === 'pirate' ? 64 : 42)) {
        if (e.type === 'heart') {
          this.health = Math.min(MAX_HEALTH, this.health + 10);
          this.heartsCollected++;
          this.bonus += 50;
          this.burst(e.x, e.y, '#ffabc0');
          this.onEvent({ type: 'heart' });
          e.dead = true;
        } else if (!this.jumping && this.damage(e.type === 'rock' ? 25 : 30)) e.dead = true;
        if (this.status === 'over') return;
      }
      if (e.y > 900) e.dead = true;
    }

    for (const b of this.bullets) {
      b.x += (b.vx || 0) * dt;
      b.y += b.vy * dt;
      if (b.enemy) {
        // Airborne boats clear the shot. Keep it moving so it remains visible
        // beneath the jump and continues out of the waterway.
        if (!this.jumping && Math.abs(b.x - this.x) < 28 && Math.abs(b.y - PLAYER_Y) < 42) {
          this.damage(15);
          b.dead = true;
          if (this.status === 'over') return;
        }
      } else {
        for (const e of this.entities) {
          if (e.type !== 'pirate' || e.dead || Math.abs(e.x - b.x) > 34 || Math.abs(e.y - b.y) > 52) continue;
          e.hp--;
          e.hit = .13;
          b.dead = true;
          this.burst(e.x, b.y, '#ffdc9e', 5);
          if (e.hp <= 0) {
            e.dead = true;
            this.piratesSunk++;
            this.bonus += 250;
            this.burst(e.x, e.y, '#ffc183', 22);
            this.onEvent({ type: 'sink' });
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
