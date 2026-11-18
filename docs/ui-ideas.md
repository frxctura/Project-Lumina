# Lumina UI: what to improve

Written 2026-11-18. The backend is solid, so this is only about how the app looks, reads and works for people who
aren't using a mouse with perfect eyesight. Nothing here is committed or implemented, it's a list to pick from.

How I got these: I read all five stylesheets and the views, **measured the real color tokens** against WCAG, **tabbed
through the running app** and pixel-diffed the screenshots, checked the code for accessibility hooks, and read up on
Windows 11 / Fluent, WCAG 2.2, dark-mode and motion guidance (sources at the bottom).

## Status

Everything in sections 1 to 7 is implemented (after 1.5), and checked in the running app:

- **Focus**: a 2px ring that only shows while the keyboard is in use; theme toggle and GitHub link are reachable. Tabbing
  through Home now changes the screen on every press (pixel-diffed), it used to change nothing for four presses.
- **Contrast**: every token pair passes WCAG AA, fields/checkboxes/progress/scroll bars have a 3:1 edge, light cards have
  a border. `ContrastTest` reads `styles.css` and fails the build if a pair drops below.
- **Screen readers**: icon buttons, window buttons, tiles, steps and toasts have names; field labels are linked; the log
  is reachable; game tiles act as checkboxes with their patch state in the name.
- **Wording and type**: sentence-case buttons, "Patch game" everywhere, 14px body / 13px secondary / 12px captions,
  less jargon on Home, a fallback error that says what to do.
- **Layout**: Settings section list (and Reset moved into App data), History "More" menu, Home info panel hidden until a
  game is open, a result card after patch/restore/failure (it takes focus), Library filter + sort, arrow keys / Space /
  Enter in the grid, two-line titles, Undo for Forget and Clear queue.
- **Icons**: Fluent UI System Icons as shapes (`ui/components/Icon.java`), notice in `THIRD_PARTY_NOTICES.md`.
- **Motion**: Settings > Animations (Follow Windows / On / Reduced). "Follow Windows" reads the real Windows setting
  through `SystemParametersInfo`, so no JavaFX upgrade was needed. Reduced keeps fades, drops slides, pops, pulses,
  count-ups and the tile lift.

Still open, from section 8: a Narrator pass, a look under a Windows contrast theme, the custom title bar's snap
behaviour, and 125 to 200% display scaling. None of those can be checked without changing system settings.

## What's already good (keep it)

- Color is never the only signal. Badges say PATCHED/ORIGINAL in words, steps have ✓/✕, log errors have ✖.
- A real token system (`-text`, `-surface-2`, `-accent`...) with dark, light and follow-Windows themes.
- Spacing is a clean 4/8/12/16/24 scale and radii are consistent (6 controls, 10 surfaces).
- Dialogs follow the Windows layout (24px padding, button band at the bottom), toasts pause on hover and last 5 to 14 seconds.
- Dark surfaces are lifted greys (#121212 to #2c2c2c), not pure black, which is what the dark-mode guidance recommends.
- Grayscale font smoothing is set on purpose (JavaFX's default colored fringes look bad).

## 1. Keyboard focus is invisible (biggest problem)

I launched the app and pressed Tab five times. After four presses the window was **pixel-identical** to the idle one. On
the fifth, a 3px strip changed at the edge of a recent-games card. So you can't tell where you are with a keyboard.

Why: `styles.css` replaces every button's background (`-fx-background-color: -control`) and then has
`.button:focused { -fx-background-insets: 0; }`, which wipes JavaFX's built-in focus ring. Only text fields get a ring.
Nav buttons, chips, segments, checkboxes, recent cards and game tiles get nothing.

Also: the theme toggle and the "frxctura" link are `focusTraversable="false"`, so keyboard users can't reach them at all.

Fix: one shared rule for `:focused` on every interactive thing, a 2px ring in a color that has 3:1 against both the
thing and what's behind it (accent on dark works, 3.9:1 on the base surface; use the lighter `-accent-text` on dark
controls). Make the two toggles focusable. WCAG 2.4.7 (AA) needs it to be visible, 2.4.13 (AAA) says how big and how
contrasty. Verify with the same Tab + pixel-diff test, it can live in `ScreenshotsIT`.

## 2. Contrast: the numbers

Measured from the actual hex values (text needs 4.5:1, UI boundaries 3:1). Disabled controls are exempt from WCAG, so
I left those out.

| Where | Dark | Light | Suggested |
|---|---|---|---|
| `-text-faint` (step names, recent paths, version, placeholders) | 2.9 to 4.1 | 2.7 to 3.4 | dark `#939393`, light `#646870` |
| Placeholder text on the field fill | 2.94 | 2.71 | dark `#9b9b9b`, light `#62656e` |
| White on `-accent` buttons / selected chips | 4.35 | ok (5.3) | dark accent `#7859f7` |
| White on green `-success-fill` | 3.41 | 3.58 | `#28864e` dark, `#25864d` light |
| White on red in the "failed" step dot | 2.78 | n/a | use dark text `#2a0f0f` (6.5:1) like the green one |
| Danger text on its tint | 4.41 | 3.87 | dark `#ff6f6f`, light `#bf3939` |
| Light badges: ok / warn text on tint | n/a | 3.12 / 3.45 | `#187b42` / `#96610a` |

Side effect worth knowing: raised `-text-faint` lands very close to `-text-dim` (`#939393` vs `#a8a8a8`). I'd drop the
third text tier and use two, dim and strong. Fewer greys, clearer hierarchy.

**Non-text contrast (3:1)** fails almost everywhere because controls are defined by fill alone:

- text fields, combo boxes, checkbox boxes: 1.2 to 1.35:1 (dark), 1.17 to 1.26:1 (light)
- progress track, idle step dots, drop-zone dashed border: 1.2 to 1.6:1
- light theme cards: white on `#f5f6f8` is 1.08:1, they barely separate

Fix: a 1px stroke on inputs and checkboxes (dark `#6e6e6e`, light `#8c8e92` just clears 3:1), a visible track on the
progress bar, and a 1px border or soft shadow on light cards. This is what Windows 11's own controls do (a stroke, not
just a fill), so it also looks more native.

## 3. Screen readers and labels

I searched the whole source: **zero** `setAccessibleText` / `AccessibleRole` / `labelFor`. JavaFX falls back to a
tooltip when there's no accessible text, so some things get read, but:

- icon-only buttons (⚙ settings, ✕ remove/close, ☀/☾ theme) depend on that fallback
- field labels aren't linked to their fields ("Steam App ID" is just text next to a box)
- toasts and progress changes aren't announced; JavaFX has no live regions, so a screen reader user wouldn't know a
  patch finished
- the log list is `focusTraversable=false`, so it can't be read with the keyboard

What to do: give every icon button an explicit accessible name, link labels with `labelFor`, set a role on toasts, and
read the finish/fail message through the status label (which is focusable). **I haven't tested with Narrator**
(Win+Ctrl+Enter), so first step is a 15-minute pass with it to see what it actually says. Known: JavaFX had Narrator
bugs with dialogs (JDK-8292658) and Oracle's accessibility report covers Windows and macOS only, so don't expect full parity.

## 4. Type and wording

- 12px is the workhorse (30 of ~50 font rules) and badges are 11px in a black-weight font. The Windows type ramp uses
  14px for body and 12px for captions. I'd make body copy 13 to 14px and keep 12 for captions only.
- ALL-CAPS buttons ("SELECT DIRECTORY", "RESTORE ORIGINALS", "DELETE FOLDER BACKUP") are harder to read and Windows
  apps use sentence case now. Section labels in caps at 12px black weight are fine, buttons aren't.
- The word for the main action is **"START CRACKING"** but everything else says patch (toasts, History badge,
  README). Pick one.
- Jargon to soften: "steam_api DLLs", "proxy", "_o.dll" in main UI text. Say what happens to the game, the filenames
  can sit in tooltips or the preview.
- Errors already follow the good pattern (what happened, why, what to do) in most places. The remaining "Something
  went wrong" style messages are the ones left to fix.

## 5. Layout and flow

- **Settings** is one long scroll of five cards. Give it a left list (Appearance, Backups, Emulators, DRM, App data)
  that jumps to each, or split into tabs. Reset sits at the very bottom where nobody finds it.
- **History** cards carry three or four buttons each, so a long list is a wall of buttons. Keep the two main ones
  (Open, Show backup) and move the rest into a "more" menu. Don't hide them behind hover, keyboard users can't hover.
- **Home before a game is picked** is a big empty page and an empty "Game information" panel. Hide the panel until a
  game is chosen.
- **After a patch**, the result is a toast that disappears. A small result card on Home ("Patched 2 files, 1 backup.
  Open folder · Undo") stays and is the thing a screen reader can find too.
- **Library**: arrow keys to move between tiles, Space to tick, a sort/filter row (patched / original / needs
  attention). Long titles are cut with "..." and only the tooltip has the rest, let them wrap to two lines.
- **Undo instead of confirm** for reversible things (Forget, Clear queue). A toast with Undo beats a dialog.

## 6. Icons

The ⚙ ✕ ⇩ ☀ ☾ ⌕ ∅ ⚠ glyphs come from Segoe UI Symbol, so they differ in weight and baseline and can render as boxes on
machines without that font. You already draw the window buttons and GitHub mark as SVG shapes in CSS. Do the same for
a small set (settings, close, search, refresh, folder, check). Microsoft's Fluent icons are MIT licensed. Same look
everywhere, scales with DPI, takes its color from the theme.

## 7. Motion

Every animation always runs (reveals, slides, pops, count-ups, the pulsing step dot) and they're spread over seven
files calling `Timeline` and friends directly. Guidance says to keep UI motion short (200 to 500ms), avoid anything that
bounces or flashes, and respect the system's "reduce motion" setting.

- Step 1 (cheap, safe): route everything through the existing `Ui.*` helpers and one `Motion.enabled()` switch, plus a
  "Reduce animations" checkbox in Settings.
- Step 2: follow Windows' own animation setting. We're on **JavaFX 17**, which can't read it. JavaFX 24+ adds
  `reducedMotion` (and `accentColor`, `colorScheme`) to `Platform.Preferences`. Upgrading is a real task with its own
  risk (we pin 17.0.6 and the jlink build), so treat it as a separate decision.
- When reduced: keep fades (they carry meaning), drop slides, scale-pops and count-ups.

## 8. Things I couldn't verify

- **Narrator and High Contrast mode.** Windows High Contrast themes need forced colors; JavaFX 17 has no hook for it, I
  couldn't find one in 25 either. At minimum we should open it once under a contrast theme and see.
- **The custom title bar.** The window is undecorated with our own buttons. Check that Aero Snap, the Win11 snap-layout
  flyout on the maximize button, Alt+Space, double-click-to-maximize and drag-to-edge all behave. Custom chrome often
  loses some of these.
- **Scaling.** 125% / 150% / 200% display scaling and a 1000px minimum width, I only looked at 100%.

## Suggested order

1. **Visible focus + the two toggles focusable** (section 1). One CSS rule and two attributes.
2. **Contrast tokens + input strokes** (section 2). A handful of hex values and one border rule. And add a unit test
   that parses the CSS tokens and asserts the ratios, so nobody can regress them.
3. **Accessible names and label links**, then a Narrator pass (section 3).
4. **Wording + sentence case + type sizes** (section 4). Mostly string edits.
5. **Settings nav, History actions, result card** (section 5).
6. **SVG icon set** (section 6).
7. **Motion switch**, then decide on the JavaFX upgrade (section 7).

Steps 1 and 2 are maybe half a day together and they change how every screen feels. The rest can go one at a time.

## Sources

- W3C, [What's new in WCAG 2.2](https://www.w3.org/WAI/standards-guidelines/wcag/new-in-22/): Focus Not Obscured (AA, 2.4.11), Focus Appearance (AAA, 2.4.13), Target Size Minimum (AA, 2.5.8)
- WCAG contrast rules: 4.5:1 body text, 3:1 large text and UI components (1.4.3, 1.4.11), via [WebAIM's WCAG 2.2 overview](https://webaim.org/blog/wcag-2-2-overview-and-feedback/)
- Microsoft, [Windows app design principles](https://learn.microsoft.com/en-us/windows/apps/design/design-principles) (elevation and layering, type ramp, spacing)
- [JavaFX accessibility on dev.java](https://dev.java/learn/javafx/a11y/intro/), [JDK-8292658](https://bugs.openjdk.org/browse/JDK-8292658) (Narrator and dialogs)
- [JDK-8341514](https://bugs.openjdk.org/browse/JDK-8341514): `reducedMotion` / `reducedTransparency` preferences in JavaFX 24
- Dark mode: [LogRocket](https://blog.logrocket.com/ux-design/dark-mode-ui-design-best-practices-and-examples/), [UX Planet](https://uxplanet.org/dark-mode-ui-design-best-practices-8d3a00a83924)
- Motion: [Primer](https://primer.style/accessibility/design-guidance/motion-and-animation), [web.dev](https://web.dev/learn/accessibility/motion)
- Empty states and errors: [UXPin](https://www.uxpin.com/studio/blog/ux-best-practices-designing-the-overlooked-empty-states/), [Wix](https://www.wix.com/studio/blog/error-message)

Contrast numbers and the suggested hex values were computed from `styles.css` with the WCAG relative-luminance formula,
alpha-blending the badge tints over the card color.
