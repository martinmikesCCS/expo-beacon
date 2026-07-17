# Repository guidance

## Purpose

`expo-beacon` is one npm package with two public surfaces:

- The React Native/Expo runtime module exported from `src/index.ts`.
- The bundled Expo config plugin exported from `plugin/src/index.ts` and loaded
  through `app.plugin.js`.

Preserve backwards compatibility for both surfaces. Native method signatures
are positional contracts shared by TypeScript, Swift, and Kotlin. Prefer adding
JavaScript helpers over changing those contracts in place.

## Repository map

- `src/`: public TypeScript API, web fallback, hooks, and object-based helpers.
- `android/src/main/`: Android native module and service implementation.
- `ios/`: iOS native module and delegates.
- `plugin/src/`: Expo config plugin and generated native bridge templates.
- `docs/`: focused consumer documentation; keep each page task-oriented.
- `example/`: development application.
- `build/` and `plugin/build/`: generated output; do not edit by hand.

## Commands

- Install exactly from the lockfile: `npm ci`
- Build runtime and plugin: `npm run build`
- Run tests: `npm test -- --runInBand`
- Type-check consumer imports: `npm run test:types`
- Lint: `npm run lint`
- Regenerate API Markdown: `npm run docs:api`
- Verify generated API Markdown is current: `npm run docs:check`
- Inspect the published tarball: `npm pack --dry-run`

## Public API rules

- Keep the default `ExpoBeacon` export compatible with existing releases.
- Prefer named exports and options objects for new high-level APIs.
- Use unit suffixes in new option names, such as `durationMs` and
  `timeoutSeconds`.
- Document defaults, units, platform differences, sentinel values, emitted
  events, and error codes in TSDoc on the exported declaration.
- Add new event names to `ExpoBeaconModuleEvents`, `BeaconEventName`, the native
  event declarations on both platforms, and `docs/errors.md` when applicable.
- Changes to runtime behavior normally require equivalent Android, iOS, web,
  type, and documentation updates.

## Config plugin rules

- Config mods must be idempotent: repeated prebuilds must not duplicate code.
- Enabling and disabling optional integrations must both be tested.
- Generated native files must be deterministic and removable.
- Keep `BeaconPluginProps` and platform option types exported from
  `expo-beacon/plugin`.
- Never assume `react-native-background-geolocation` is installed unless its
  corresponding option is enabled; fail with an actionable message when it is
  required but missing.

## Documentation rules

- `docs/getting-started.md` is the canonical new-install path.
- `llms.txt` is a concise map, not a copy of the full documentation.
- Prefer one canonical example per task. Put compatibility alternatives after
  the canonical example rather than beside it.
- Type-check code examples through `tests/consumer/index.ts` or a dedicated
  fixture when adding a new public pattern.
- Run `npm run docs:api` after changing exported declarations or TSDoc.

## Worktree safety

The worktree may contain user changes. Do not overwrite unrelated changes or
edit generated build output directly. Review `git status` before and after work.
