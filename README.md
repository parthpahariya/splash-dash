# Wake Runner

A responsive, top-down four-lane boat arcade game, built with Canvas 2D and vanilla JavaScript. No runtime dependencies.

Native mobile editions are in [`mobile/`](mobile/README.md): **Kotlin for Android** and **Swift for iPhone and iPad**, with offline gameplay, touch controls, and platform-specific build instructions.

## Run locally

Requires Node.js 20 or later.

```sh
npm run dev
```

Open http://localhost:5173. Run `npm run build` for the static `dist/` build, or `npm start -- --dist` to serve it. Run `npm test` for gameplay tests.

## Controls

| Action | Keys |
| --- | --- |
| Steer between four lanes | Left / Right or A / D |
| Fire cannon (hold for continuous fire) | Space |
| Aim and fire at a pirate | Click / tap the pirate ship |
| Jump | Up Arrow |
| Pause / resume | P or Escape |
| Start / retry | Enter |

Touch controls are below the game; swipe across the water to steer, or hold Fire to keep shooting. Sound is optional and defaults to off. Runs pause when the tab loses focus. Your personal best and sound preference are saved in this browser when local storage is available.

The camera stays steady during collisions. Background and effect animations freeze when paused or at game over.

On phones, the game fits the available screen height with large touch controls, a compact score strip, and support for notches and home indicators. Landscape moves the controls beside the water. Tablets and desktops retain the wider layout and keyboard hints.

## Rules

- Start with 100 hull health and two jumps.
- Rock collisions deal 25 damage, pirate cannonballs deal 15, and pirate collisions deal 30.
- Pirates aim each cannonball at your boat's position when they fire. Change lanes or press Up Arrow to jump over incoming shots.
- Cannon launches flash and leave a visible trail. During a jump, cannonballs pass underneath the raised boat and continue offscreen without dealing damage.
- Damage gives a short grace period so one collision cannot drain the whole hull.
- Hearts restore 25 health, up to 100, and award 50 points.
- Each voyage has two jumps. Jumps last 1.15 seconds and clear hazards; pickups do not replenish jump charges.
- Hearts stay separated from each other and from rocks. A pickup can appear above a rock in the same lane, with a clear gap.
- Pirate ships never overlap rocks: rocks use lanes without pirates, and new pirates enter above existing rocks with a safe gap that grows as they scroll.
- Two cannon hits sink a pirate for 250 points.
- Click or tap a pirate ship to aim a shot at it from any lane. Shots travel in a straight line; swiping across a pirate still steers instead of firing.
- Sail for two points per meter. The game speeds up every 300 meters.

The game engine is independent of rendering in `src/engine.js`, with deterministic tests in `tests/engine.test.mjs`. The illustrated water, boats, islands, pickups, and effects are drawn in Canvas; no image files or network assets are needed for gameplay. Google Fonts are optional and have local fallbacks.
