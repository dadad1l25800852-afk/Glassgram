# GlassGram iOS 26 — implementation tracker

Development branch: `glassgram-ios26`. Base: exteraless 13.2.2.

## Phase 1: safe Android resources

- `@drawable/glassgram_ios26_panel`: rounded translucent navy panel with light stroke.
- `@drawable/glassgram_ios26_selected_tab`: translucent blue selected-tab background.

These are **resource assets only** and are not yet attached to existing navigation views. They do not produce real backdrop blur or optical refraction. This phase intentionally leaves the Telegram UI and plugin settings unchanged, avoiding global method hooks.

## Next steps

1. Identify the actual navigation view implementation and its theme-aware drawing code.
2. Wire these resources behind an opt-in appearance setting.
3. Verify light/dark contrast and test settings/plugin navigation.
4. Run the existing staging workflow against this branch before any release.

Do not claim pixel-identical iOS appearance: Android status bars, fonts, blur, and layout metrics differ.
