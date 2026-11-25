import test from 'node:test';
import assert from 'node:assert/strict';
import { Game, LANES, PLAYER_Y, SPAWN_GAP } from '../src/engine.js';

function fresh() { const g = new Game({ random: () => .4 }); g.reset(); g.spawnTimer = g.pickupTimer = g.pirateTimer = 1000; return g; }
function advance(game, seconds) { for (let i = 0; i < Math.ceil(seconds / .01); i++) game.update(.01); }

test('steering is bounded to four lanes and moves smoothly', () => {
  const g = fresh();
  for (let i = 0; i < 8; i++) g.move(1);
  advance(g, 1);
  assert.equal(g.lane, 3); assert.ok(Math.abs(g.x - LANES[3]) < .1);
  for (let i = 0; i < 8; i++) g.move(-1);
  advance(g, 1);
  assert.equal(g.lane, 0); assert.ok(Math.abs(g.x - LANES[0]) < .1);
});
test('a rock costs 25 health and grace period prevents stacked damage', () => {
  const g = fresh(); g.spawn('rock', 1, PLAYER_Y); g.update(.01);
  assert.equal(g.health, 75);
  assert.equal(g.damage(25), false); assert.equal(g.health, 75);
  advance(g, 1.2); g.damage(25); assert.equal(g.health, 50);
});
test('pirate shots damage the boat and jumping avoids rocks and shots', () => {
  const g = fresh(); g.bullets.push({ x: g.x, y: PLAYER_Y, vy: 0, enemy: true }); g.update(.01);
  assert.equal(g.health, 85);
  const jumping = fresh(); assert.equal(jumping.jump(), true);
  jumping.spawn('rock', 1, PLAYER_Y); jumping.bullets.push({ x: jumping.x, y: PLAYER_Y, vy: 0, enemy: true });
  jumping.update(.01); assert.equal(jumping.health, 100); assert.equal(jumping.jumps, 1);
  assert.equal(jumping.jump(), false); advance(jumping, 1.2);
  assert.equal(jumping.jumping, false); assert.equal(jumping.jump(), true); advance(jumping, 1.2);
  assert.equal(jumping.jump(), false);
});
test('heart pickups restore 10 health without exceeding 100', () => {
  for (const health of [60, 95, 100]) {
    const g = fresh(); g.health = health; g.spawn('heart', 1, PLAYER_Y); g.update(.01);
    assert.equal(g.health, Math.min(100, health + 10)); assert.equal(g.heartsCollected, 1); assert.equal(g.bonus, 50);
  }
});
test('automatic pickups are hearts only and never replenish jump charges', () => {
  for (const roll of [.1, .9]) {
    const g = new Game({ random: () => roll }); g.reset(); g.invincible = 100;
    assert.equal(g.jump(), true);
    advance(g, 8);
    assert.equal(g.jumps, 1);
    assert.equal(g.spawn('jump', 1, PLAYER_Y), null);
    assert.equal(g.entities.some(e => e.type === 'heart'), roll < .58);
    assert.ok(g.entities.every(e => ['rock', 'pirate', 'heart'].includes(e.type)));
  }
});
test('two cannon hits sink a pirate and award 250 points', () => {
  const g = fresh(); const p = g.spawn('pirate', 1, 300);
  g.bullets.push({ x: p.x, y: p.y, vy: 0, enemy: false }); g.update(.01);
  assert.equal(p.hp, 1); assert.equal(g.piratesSunk, 0);
  g.bullets.push({ x: p.x, y: p.y, vy: 0, enemy: false }); g.update(.01);
  assert.equal(g.piratesSunk, 1); assert.equal(g.bonus, 250); assert.equal(g.entities.length, 0);
});
test('pirates fire toward the player and contact damages the hull', () => {
  const g = fresh(); const p = g.spawn('pirate', 1, 120); p.cooldown = 0; g.update(.01);
  assert.ok(g.bullets.some(b => b.enemy && b.vy > 0));
  g.spawn('pirate', 1, PLAYER_Y); g.update(.01); assert.equal(g.health, 70);
});
test('pause freezes the run, death stops play, and reset gives a clean run', () => {
  const g = fresh(); g.pause(); advance(g, 2); assert.equal(g.distance, 0); assert.equal(g.jump(), false);
  g.pause(); advance(g, 1); assert.ok(g.distance > 0);
  g.health = 15; g.damage(15); assert.equal(g.status, 'over');
  const distance = g.distance; advance(g, 1); assert.equal(g.distance, distance);
  g.reset(); assert.equal(g.health, 100); assert.equal(g.jumps, 2); assert.equal(g.distance, 0); assert.equal(g.status, 'playing');
});
test('sailing increases score and raises difficulty every 300 meters', () => {
  const g = fresh(); g.distance = 299.9; advance(g, .1);
  assert.equal(g.level, 2); assert.ok(g.speed > 125); assert.ok(g.score >= 600);
});

test('a lethal collision ends the frame before another pickup can change the result', () => {
  const g = fresh(); g.health = 25;
  g.spawn('rock', 1, PLAYER_Y);
  // Force simultaneous contact to exercise the terminal-frame safeguard.
  g.spawn('heart', 1, PLAYER_Y).y = PLAYER_Y;
  g.update(.01);
  assert.equal(g.status, 'over'); assert.equal(g.health, 0); assert.equal(g.heartsCollected, 0);
});

test('heart pickups cannot occupy the same location', () => {
  const g = fresh();
  const heart = g.spawn('heart', 1);
  const secondHeart = g.spawn('heart', 1);
  assert.equal(heart.lane, secondHeart.lane);
  assert.equal(heart.y - secondHeart.y, SPAWN_GAP);
  advance(g, 2);
  assert.ok(Math.abs(heart.y - secondHeart.y) >= SPAWN_GAP - .001);
});

test('pickups can appear above rocks with a clear gap in either spawn order', () => {
  for (const type of ['heart']) {
    const g = fresh();
    const rock = g.spawn('rock', 1);
    const pickup = g.spawn(type, 1);
    assert.equal(rock.y - pickup.y, SPAWN_GAP);
    advance(g, 2);
    assert.ok(rock.y - pickup.y >= SPAWN_GAP - .001);

    const reverse = fresh();
    const first = reverse.spawn(type, 1);
    const second = reverse.spawn('rock', 1);
    assert.equal(first.y - second.y, SPAWN_GAP);
  }
});

test('several pickups sharing a spawn point stay separated', () => {
  const g = fresh();
  for (const type of ['heart', 'heart', 'heart', 'heart']) g.spawn(type, 2);
  for (let i = 0; i < g.entities.length; i++) {
    for (let j = i + 1; j < g.entities.length; j++) {
      assert.ok(Math.abs(g.entities[i].y - g.entities[j].y) >= SPAWN_GAP);
    }
  }
});

test('pickups use an open lane so they cannot catch up to a pirate', () => {
  const g = fresh(); g.spawn('pirate', 1, 100);
  const heart = g.spawn('heart', 1);
  assert.notEqual(heart.lane, 1);
  for (const lane of [0, 2, 3]) g.spawn('pirate', lane, 100);
  assert.equal(g.spawn('heart', 1), null);
});

test('a new pirate stays behind earlier pickups that move faster', () => {
  const g = fresh();
  const heart = g.spawn('heart', 1, -300);
  const pirate = g.spawn('pirate', 1);
  assert.ok(heart.y - pirate.y >= SPAWN_GAP);
  advance(g, 2);
  assert.ok(heart.y - pirate.y >= SPAWN_GAP);
});

test('pickups stay clear of all objects throughout a scrolling run', () => {
  let seed = 42;
  const g = new Game({ random: () => ((seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296) });
  g.reset();
  let pickupsSeen = 0;
  for (let frame = 0; frame < 6000; frame++) {
    // Isolate spawn spacing from player damage during this simulated minute.
    g.invincible = 1;
    g.update(.01);
    for (const pickup of g.entities.filter(e => e.type === 'heart')) {
      pickupsSeen++;
      for (const other of g.entities) {
        if (other !== pickup && other.lane === pickup.lane) {
          assert.ok(Math.abs(other.y - pickup.y) >= SPAWN_GAP - .001);
        }
      }
    }
  }
  assert.ok(pickupsSeen > 0);
});

test('rocks use another lane instead of catching up to a pirate', () => {
  const g = fresh();
  const pirate = g.spawn('pirate', 1, 100);
  const rock = g.spawn('rock', 1);
  assert.ok(rock);
  assert.notEqual(rock.lane, pirate.lane);
  advance(g, 3);
  assert.notEqual(rock.lane, pirate.lane);
});

test('a new pirate stays above existing rocks so their gap grows', () => {
  const g = fresh();
  const rock = g.spawn('rock', 1, 100);
  const pirate = g.spawn('pirate', 1, 500);
  assert.ok(pirate);
  assert.ok(rock.y - pirate.y >= SPAWN_GAP);
  advance(g, 3);
  assert.ok(rock.y - pirate.y > SPAWN_GAP);
});

test('rocks wait when pirates occupy every lane and resume after a pirate sinks', () => {
  const g = fresh();
  for (let lane = 0; lane < 4; lane++) g.spawn('pirate', lane, 100);
  assert.equal(g.spawn('rock', 1), null);
  g.entities[1].dead = true;
  const rock = g.spawn('rock', 1);
  assert.ok(rock);
  assert.equal(rock.lane, 1);
});

test('rocks and pirates remain separated through long runs and speed increases', () => {
  for (const initialSeed of [7, 42, 2026]) {
    let seed = initialSeed;
    const g = new Game({ random: () => ((seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0) / 4294967296) });
    g.reset();
    let pairsChecked = 0;
    for (let frame = 0; frame < 6000; frame++) {
      g.invincible = 1;
      g.update(.05);
      for (const pirate of g.entities.filter(e => e.type === 'pirate')) {
        for (const rock of g.entities.filter(e => e.type === 'rock')) {
          pairsChecked++;
          if (pirate.lane === rock.lane) {
            assert.ok(Math.abs(pirate.y - rock.y) >= SPAWN_GAP - .001,
              `Pirate overlaps rock for seed ${initialSeed} at ${g.elapsed.toFixed(2)}s`);
          }
        }
      }
    }
    assert.ok(pairsChecked > 0);
    assert.ok(g.level > 5);
  }
});

test('pirates aim at the boat across lanes and re-aim each new shot', () => {
  const g = fresh();
  g.lane = 3; g.x = LANES[3];
  const pirate = g.spawn('pirate', 1, 150);
  pirate.cooldown = 0; g.update(.01);
  const first = g.bullets.find(b => b.enemy);
  assert.ok(first.vx > 0);
  assert.ok(Math.abs(Math.hypot(first.vx, first.vy) - 273) < .001);
  const firstArrival = (PLAYER_Y - first.y) / first.vy;
  assert.ok(Math.abs(first.x + first.vx * firstArrival - LANES[3]) < .001);

  g.lane = 0; g.x = LANES[0];
  pirate.cooldown = 0; g.update(.01);
  const second = g.bullets.filter(b => b.enemy)[1];
  assert.ok(second.vx < 0);
  const secondArrival = (PLAYER_Y - second.y) / second.vy;
  assert.ok(Math.abs(second.x + second.vx * secondArrival - LANES[0]) < .001);
  assert.ok(first.vx > 0, 'An existing shot keeps its trajectory so the player can dodge it');
});

test('an aimed cannonball hits a stationary boat in another lane', () => {
  const g = fresh();
  const pirate = g.spawn('pirate', 0, 400);
  pirate.cooldown = 0;
  advance(g, 1);
  assert.equal(g.health, 85);
});

test('jumping over an aimed cannonball avoids damage until landing', () => {
  const g = fresh();
  const pirate = g.spawn('pirate', 0, 400);
  pirate.cooldown = 0; g.update(.01);
  const shot = g.bullets.find(b => b.enemy);
  while (shot.y < PLAYER_Y - 80) g.update(.01);
  assert.equal(g.health, 100);
  assert.equal(g.jump(), true);
  advance(g, .6);
  assert.equal(g.health, 100);
  assert.ok(g.bullets.includes(shot), 'Dodged cannonballs keep traveling visibly past the boat');
  assert.ok(shot.y > PLAYER_Y);
  advance(g, .6);
  assert.equal(g.jumping, false);
  assert.ok(!g.bullets.includes(shot), 'A cannonball is removed once it leaves the play area');
  g.bullets.push({ x: g.x, y: PLAYER_Y, vx: 0, vy: 1, enemy: true });
  g.update(.01);
  assert.equal(g.health, 85);
});

test('targeted player shots travel diagonally and two hits sink the selected pirate', () => {
  const g = fresh();
  g.lane = 3; g.x = LANES[3];
  const pirate = g.spawn('pirate', 0, 280);
  assert.equal(g.fire(pirate), true);
  const shot = g.bullets[0];
  assert.ok(shot.vx < 0); assert.ok(shot.vy < 0);
  assert.ok(Math.abs(Math.hypot(shot.vx, shot.vy) - 650) < .001);
  const before = shot.x; g.update(.01); assert.ok(shot.x < before);
  advance(g, .3);
  assert.equal(g.fire(pirate), true);
  advance(g, 1.2);
  assert.equal(g.piratesSunk, 1);
  assert.equal(g.bonus, 250);
});

test('targeted fire rejects vanished pirates and normal fire still shoots forward', () => {
  const g = fresh();
  const pirate = g.spawn('pirate', 0, 280); pirate.dead = true;
  assert.equal(g.fire(pirate), false);
  assert.equal(g.bullets.length, 0);
  assert.equal(g.fire(), true);
  assert.equal(g.bullets[0].vx, 0);
  assert.equal(g.bullets[0].vy, -650);
});

test('a cannonball passes under a jumping boat without impact effects', () => {
  const g = fresh();
  const events = [];
  g.onEvent = event => events.push(event);
  const shot = { x: g.x, y: PLAYER_Y - 40, vx: 0, vy: 273, enemy: true, dead: false };
  g.bullets.push(shot);
  g.jump();
  advance(g, .3);
  assert.equal(g.health, 100);
  assert.equal(g.invincible, 0);
  assert.ok(shot.y > PLAYER_Y);
  assert.ok(g.bullets.includes(shot));
  assert.ok(!events.some(e => e.type === 'damage'));
  advance(g, .6);
  assert.ok(!g.bullets.includes(shot));
});

test('a pirate flashes its cannon only briefly when launching a shot', () => {
  const g = fresh();
  const pirate = g.spawn('pirate', 0, 200);
  pirate.cooldown = 0;
  g.update(.01);
  assert.ok(pirate.flash > 0);
  assert.ok(g.bullets.some(b => b.enemy));
  advance(g, .2);
  assert.equal(pirate.flash, 0);
});

test('all nine pirate types take their advertised hits and award their own rewards', async () => {
  const { PIRATES } = await import('../src/engine.js');
  assert.deepEqual(Object.values(PIRATES).map(p => p.hp), [1, 2, 3, 3, 2, 4, 3, 6, 10]);
  for (const [type, stats] of Object.entries(PIRATES)) {
    const g = fresh(); const e = g.spawn('pirate', 1, 300, type);
    assert.equal(e.hp, stats.hp); assert.equal(e.maxHp, stats.hp);
    for (let hit = 1; hit <= stats.hp; hit++) {
      g.bullets.push({ x: e.x, y: e.y, vx: 0, vy: 0, enemy: false }); g.update(0);
      assert.equal(e.hp, stats.hp - hit);
      assert.equal(e.dead, hit === stats.hp);
    }
    assert.equal(g.bonus, stats.reward); assert.equal(g.piratesSunk, 1);
  }
  assert.equal(fresh().spawn('pirate', 1, 300, 'unknown'), null);
});

test('automatic encounters unlock the full roster gradually and limit captains', async () => {
  const { PIRATES } = await import('../src/engine.js');
  const g = fresh();
  for (let level = 1; level <= 5; level++) {
    g.level = level; g.distance = (level - 1) * 300;
    const available = new Set();
    for (let i = 0; i < 100; i++) { g.random = () => i / 100; available.add(g.choosePirate()); }
    assert.deepEqual([...available].sort(), Object.keys(PIRATES).filter(type => PIRATES[type].unlock <= level).sort());
  }
  g.random = () => .999;
  g.lastCaptainDistance = g.distance;
  assert.notEqual(g.choosePirate(), 'captain');
  g.lastCaptainDistance = -900;
  g.spawn('pirate', 0, 100, 'captain');
  assert.notEqual(g.choosePirate(), 'captain');
});

test('scouts reload faster, raiders fire three timed shots, and spreads fan out', () => {
  const scout = fresh(); const s = scout.spawn('pirate', 0, 180, 'scout'); s.cooldown = 0; scout.update(.01);
  assert.equal(scout.bullets.length, 1); assert.equal(s.cooldown, 1.4);
  const burst = fresh(); const b = burst.spawn('pirate', 0, 180, 'burst'); b.cooldown = 0; burst.update(.01);
  assert.equal(burst.bullets.length, 1);
  advance(burst, .4); assert.equal(burst.bullets.length, 3);
  advance(burst, .6); assert.equal(burst.bullets.length, 3);
  const spread = fresh(); const e = spread.spawn('pirate', 1, 180, 'scatter'); e.cooldown = 0; spread.update(.01);
  const shots = spread.bullets;
  assert.equal(shots.length, 3); assert.ok(shots[0].vx > shots[1].vx && shots[1].vx > shots[2].vx);
  assert.ok(shots.every(b => Math.abs(Math.hypot(b.vx, b.vy) - 273) < .001));
});

test('sharpshooters warn, lock their aim, and fire a fast heavy shot at the old position', () => {
  const g = fresh(); const e = g.spawn('pirate', 0, 180, 'sniper'); e.cooldown = 0; g.update(.01);
  assert.equal(e.warning, 1.1); assert.equal(g.bullets.length, 0);
  const locked = e.aimX;
  g.lane = 3; g.x = LANES[3];
  advance(g, 1.11);
  const shot = g.bullets[0];
  assert.equal(shot.damage, 25); assert.ok(Math.abs(Math.hypot(shot.vx, shot.vy) - 480) < .001);
  assert.ok(Math.abs(shot.x + shot.vx * (PLAYER_Y - shot.y) / shot.vy - locked) < .001);
});

test('rammers warn before charging, stay in their lane, and deal 40 contact damage', () => {
  const g = fresh(); const e = g.spawn('pirate', 0, 180, 'rammer'); e.cooldown = 0; g.update(.01);
  assert.equal(e.warning, .9); assert.equal(e.charging, false);
  advance(g, .91); assert.equal(e.charging, true); assert.equal(g.bullets.length, 0);
  const y = e.y; advance(g, .1); assert.ok(e.y - y > g.speed * .2); assert.equal(e.x, LANES[0]);
  e.y = PLAYER_Y; g.lane = 0; g.x = LANES[0]; g.update(0); assert.equal(g.health, 60);
});

test('charging rammers retain spacing behind rocks and hearts', () => {
  for (const type of ['rock', 'heart']) {
    const g = fresh(); const obstacle = g.spawn(type, 0, 350);
    const rammer = g.spawn('pirate', 0, 150, 'rammer'); rammer.charging = true;
    advance(g, 1);
    assert.ok(obstacle.y - rammer.y >= SPAWN_GAP);
  }
});

test('mines arm before damaging, can be targeted, and disappear when shot', () => {
  const g = fresh(); const e = g.spawn('pirate', 0, 150, 'minelayer'); e.cooldown = 0; g.update(.01);
  const mine = g.bullets[0]; assert.equal(mine.mine, true); assert.equal(mine.damage, 25);
  assert.ok(mine.armTime > 0); assert.equal(g.fire(mine), true);
  g.bullets = [mine]; g.entities = [];
  mine.x = g.x; mine.y = PLAYER_Y; mine.vy = 0;
  advance(g, .7); assert.equal(g.health, 100);
  advance(g, .12); assert.equal(g.health, 75);
  const clear = fresh(); const layer = clear.spawn('pirate', 0, 150, 'minelayer'); layer.cooldown = 0; clear.update(.01);
  const target = clear.bullets[0];
  clear.bullets.push({ x: target.x, y: target.y, vx: 0, vy: 0, enemy: false }); clear.update(0);
  assert.ok(!clear.bullets.includes(target)); assert.equal(clear.piratesSunk, 0);
  assert.equal(clear.fire(target), false);
});

test('ironclads launch two slow heavy cannonballs with a long reload', () => {
  const g = fresh(); const e = g.spawn('pirate', 0, 150, 'ironclad'); e.cooldown = 0; g.update(.01);
  assert.equal(g.bullets.length, 2); assert.equal(e.cooldown, 4);
  assert.ok(g.bullets.every(b => b.damage === 25 && Math.abs(Math.hypot(b.vx, b.vy) - 190) < .001));
});

test('captains alternate warned bursts and spreads and reload faster at half health', () => {
  const g = fresh(); const e = g.spawn('pirate', 0, 150, 'captain'); e.cooldown = 0; g.update(.01);
  assert.equal(e.warning, .65); assert.equal(g.bullets.length, 0);
  advance(g, .66); assert.equal(e.burstLeft, 2); assert.equal(e.attackStep, 1);
  advance(g, .4); assert.equal(g.bullets.length, 3);
  g.bullets = []; e.cooldown = 0; e.hp = 5; g.update(.01); advance(g, .65);
  assert.equal(g.bullets.length, 3); assert.equal(e.attackStep, 2); assert.ok(e.cooldown <= 1.6);
  assert.ok(e.cooldown > 1.5);
});

test('pausing freezes attack warnings, burst timers and mine arming; sinking cancels pending fire', () => {
  const g = fresh(); const e = g.spawn('pirate', 0, 150, 'sniper'); e.cooldown = 0; g.update(.01);
  g.pause(); advance(g, 2); assert.equal(e.warning, 1.1); g.pause();
  e.hp = 1; g.bullets.push({ x: e.x, y: e.y, vx: 0, vy: 0, enemy: false }); g.update(0);
  advance(g, 1.2); assert.equal(g.bullets.length, 0);
  const b = fresh(); const raider = b.spawn('pirate', 0, 150, 'burst'); raider.cooldown = 0; b.update(.01);
  const timer = raider.burstTimer; b.pause(); advance(b, 1); assert.equal(raider.burstTimer, timer);
  const m = fresh(); const layer = m.spawn('pirate', 0, 150, 'minelayer'); layer.cooldown = 0; m.update(.01);
  const mine = m.bullets[0], arm = mine.armTime; m.pause(); advance(m, 1); assert.equal(mine.armTime, arm);
});

test('faster pirates cannot catch a captain or overlap its health and warning labels', () => {
  const g = fresh();
  const captain = g.spawn('pirate', 0, 350, 'captain'); captain.cooldown = 1000;
  const gunner = g.spawn('pirate', 0, 200, 'gunner');
  assert.ok(captain.y - gunner.y >= 220);
  advance(g, 2);
  assert.ok(captain.y - gunner.y >= 220);
});

test('rammers cannot share a lane with any live pirate in either spawn order', async () => {
  const { PIRATES } = await import('../src/engine.js');
  for (const type of Object.keys(PIRATES)) {
    for (const [first, second] of [[type, 'rammer'], ['rammer', type]]) {
      const g = fresh();
      const existing = g.spawn('pirate', 0, 500, first);
      assert.ok(existing);
      assert.equal(g.spawn('pirate', 0, -80, second), null, `${first} must exclude ${second}`);
      assert.equal(g.entities.length, 1);
      assert.ok(g.spawn('pirate', 1, -80, second), 'Another lane remains available');
      existing.dead = true;
      assert.ok(g.spawn('pirate', 0, -80, second), 'The lane is available after its pirate dies');
    }
  }
});

test('pending attacks finish after pirates cross the player approach boundary', () => {
  for (const type of ['sniper', 'burst']) {
    const g = fresh(); g.distance = 3000;
    const e = g.spawn('pirate', 0, 550, type); e.cooldown = 0;
    g.update(.01); advance(g, type === 'sniper' ? 1.11 : .4);
    assert.equal(e.warning, 0); assert.equal(e.burstLeft, 0);
    assert.ok(g.bullets.some(b => b.enemy));
  }
});
