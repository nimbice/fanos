# Changelog

What changed in each release of Fanos, newest first. The newest is always on the
[releases page](https://github.com/nimbice/fanos/releases/latest).

## 0.1.8 (2026-11-03)

- Reader: brightness is now a thick bar with the sun in it, like the phone's own, at the top of the controls, with the text size and colours between it and the slider through the chapter, so the two are hard to mix up.
- Reader: the chapter buttons beside that slider are plain back and forward arrows, and auto-scroll is a play button.
- Fixed: in page mode, opening a chapter while the ones before it loaded in could still set the reader asking for chapter after chapter. Now only the chapter just before or after the one you're reading is asked for.
- Library updates check one novel at a time from each site, several sites side by side, so one busy site no longer holds up the rest. Once a site wants a bot check or a sign-in, its other novels are marked the same way without asking it again.

## 0.1.7 (2026-11-02)

- Fixed: in page mode the reader could load chapter after chapter by itself until it ran out of memory and closed, sometimes seeming to freeze on a page turn first. It now never loads more than two chapters either side of the one you're reading.
- Novels with a regular schedule are checked for their next chapter from a minute after it's due, several times over the next two hours, so it turns up soon after it's out. Checks close to the regular library update are left to that.
- For sites that give no release times, Fanos narrows down the time with a few extra checks a day, and checks a release window from its start and again from its end.
- This week and a novel's page say how many hours away the next chapter is when it's due within a day ("about 5 hours from now").
- A novel's chapter list shows how long ago each chapter came out ("Oct 12, 2026 · 16 days ago").
- Library updates can read just a novel's newest chapters, with extensions that support it, instead of its whole chapter list, so long novels update faster and ask less of the site.
- Bot-check pages that some sites send as ordinary pages are recognised, and open in the browser to be passed.
- The in-app browser no longer tells sites that it's Fanos.
- Settings › Updates: an available update's buttons sit under its version instead of squeezed beside it.
- If Fanos ever uses far more memory than it should, it notes what was holding it and lets go of what it can. Settings › About keeps a short report to share, and a crash, or Android closing Fanos for not responding, leaves one there too. Nothing leaves your phone unless you share it.

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
