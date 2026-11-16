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


## Release safety gate (mandatory)

Do **not** distribute or install an APK from this branch yet. The build currently inherits
`APP_PACKAGE=com.exteraless.app` and its Firebase `google-services.json` client.
Changing only the package name breaks Google Services processing; leaving it unchanged
can replace an installed exteraless build if the signatures match, or cause an install
conflict if they do not. Neither outcome is acceptable for a parallel test build.

Before testing on a phone:
1. Create an independent application ID and resolve Firebase/Google Services integration
   for that ID (or explicitly disable unsupported services for the independent build).
2. Audit AndroidManifest authorities, custom permissions, and URI/deep-link routing.
3. Verify the signed APK's final package name using `aapt dump badging` or `apkanalyzer`.
4. Install next to a working Telegram client, never over it; verify both launch separately.
5. Test fresh login, notifications, media, navigation, and dark/light appearance.

## Implemented versus pending

Implemented in opt-in iOS navigation mode: wider floating capsule geometry, animated
selection capsule, layered translucent gradient and highlight rim, theme-adaptive
semi-transparent scrim and shadow. The project already has a blur provider, but this
work has **not** established that the final screen renders continuous real-time blur.

Pending: verified real-time blur, refraction/highlights, chat list redesign, message
composer, bubbles, settings, accessibility, performance, and independent signed APK.
No build or on-device validation has been completed for these latest commits.
