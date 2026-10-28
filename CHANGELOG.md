# Changelog

What changed in each release of Fanos, newest first. The newest is always on the
[releases page](https://github.com/nimbice/fanos/releases/latest).

## 0.1.6 (2026-10-28)

- In dark mode the library, settings, browse, downloads and the bottom bar are now pure black, made for AMOLED screens. Cards and panels keep their warm tint, so they still stand out.
- The app icon now sits on a pure black background, with a slightly larger lamp.
- This week shows the time of day a novel's chapters usually come out. For sites that give no release times, it estimates one from when new chapters turned up ("likely between 7 and 8 AM").
- This week also recognises daily novels, several chapters a day and novels with no set day better, and its rows wrap at large text sizes instead of being cut off.
- Fixed: turning a page just after opening a chapter from a new-chapter notification could slide back into the chapter before.
- Fixed: signing in with Google inside the app (for example on Patreon) went round in circles.

## 0.1.5 (2026-10-05)

- The icon at the end of the reader's brightness slider is now a button that hands brightness back to your phone, automatic brightness included. It's lit while your phone's own brightness is in use.
- Reading settings use the same button in place of the "This device's own" chip.

## 0.1.4 (2026-10-05)

- While the library checks for new chapters, the novels still to check are greyed out, and each turns back to colour once it's checked.
- A check that's taking a while says so over the novel in red, counting the seconds.
- A novel that couldn't be checked shows why in red, beside it in the list and under it in the grid, until its chapters are fetched again.
- Some sites that took too long to answer stopped the whole update; now only that novel fails.

## 0.1.3 (2026-10-03)

- Fanos checks for updates once a day, for the app and its extensions; opening Settings › Updates or Browse › Extensions uses that day's check instead of asking again.
- An update waiting to be installed has a Check again button beside Install.

## 0.1.2 (2026-10-03)

- Behind-the-scenes changes to how updates are found; nothing new to see.

## 0.1.1 (2026-10-03)

- Settings › Updates and Browse › Extensions are tidier, with fewer buttons and sections.

## 0.1.0 (2026-10-03)

- The first public release.
- A reader for web novels: your library, chapters read in pages or by scrolling, themes and fonts, a built-in dictionary, footnotes and quote cards.
- Listening with natural voices (downloaded when you choose one), downloads for reading offline, backups, and reading-list sync with Novel Updates.
- Sites come as extensions: Browse › Extensions lists Royal Road, Scribble Hub, Novel Updates and Patreon.
- For 64-bit phones with Android 8.0 or later.
