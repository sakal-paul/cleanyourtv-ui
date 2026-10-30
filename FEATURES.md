# CleanYourTV UI, `tv-buttons` edition: features

This branch builds on [CleanYourTV UI](README.md) (an Apple TV-style home screen for Android TV). It was built and
tested on a TCL Smart TV Pro (Google TV, Android 12). Everything below is in `launcher-ui/`. Each item says what you
see on the TV, then where it lives in the code.

## The home screen

| Area | What it does |
|---|---|
| **Clock** | The time in 12-hour format ("3:11 PM"), with Kolkata's and London's times beside it in grey. `HomeView.drawLightTop`, `drawZone` |
| **Tabs** | Explore · Movies · Shows · Up Next · Watchlist · Releases · Trending. Focusing a tab selects it. `HomeView.TAB_KIND` |
| **Cards** (first five tabs) | One wide card and two tall ones. Pause 1 s on a tall card and it grows into the wide one, while the others shrink. `placeCards`, `setWide`, `GROW_MS` |
| **Poster shelf** (Releases, Trending) | Six 2:3 posters per page, like a bookshelf. `shelfTab`, `SHELF_N` |
| **Details on the focused card** | Rating ★ and genre chips, title, year · age rating · length, a three-line story, and three cast faces with names. The wide card shows all of it, a tall card or poster a short version. `CardInfo` |
| **Line under each card** | The rating first ("★ 8.6 · 8,708 watching · Drama"), in Poppins. |
| **App row** | Every installed app in one row that scrolls sideways. Your favourites come first (config `shelf`), then the rest A–Z. Tiles can have a custom logo and colour (config `app_tiles`). |
| **Round buttons** | Explore, Inputs, Light/Dark, System info (storage, RAM, internet speed test) and Settings, using Material Symbols. `SystemInfoActivity` |
| **Service pages** | Rest on Netflix, Prime Video, Apple TV, ZEE5, JioHotstar, SonyLIV or hoichoi in the app row and the top of the screen becomes that service's page: Latest · Popular · Top rated, laid out like the home cards. `ServicePanel` |

## Remote

| Press | On a card |
|---|---|
| OK | Home: opens the title's page. Service page: plays it in its app, or opens its page. |
| Hold OK 1 s, let go | Trakt menu: Add to / Remove from watchlist, Mark as watched (an Up Next show: just its next episode), Rate. `TraktMenu` |
| Hold OK 2.5 s | Opens the title's page once, while still held. Key repeats are ignored. |
| OK on Up Next or Watchlist tab | Your Trakt account: connect, or disconnect. |

## Trakt (trakt.tv)

- **Sign-in:** a code on the TV that you type at trakt.tv/activate on your phone. PKCE apps need no Client Secret. `TraktLoginActivity`, `Trakt.deviceCode/poll`
- **Up Next:** your shows, most recently watched first, each with its next unwatched episode ("Next S2 E4", plus a "6/10" progress tag).
- **Watchlist:** your Trakt watchlist.
- **Releases:** like trakt.tv's Discover → Releases. The last two weeks and the next week of films in cinemas, films and shows arriving on streaming, and new shows, the most voted first. Needs no account.
- **Trending:** like Discover → Trending. Shows and films with the most people watching right now, alternated. Needs no account.
- **Tags on the focused card:** "New episode Thu", "Next: S2 E4 · 6/10", "✓ Watched", and your own rating.

## Content refresh

- Every list and every title's details refresh every **30 minutes**. Pictures and people are kept for a week.
- **At every power-on** (the screen turning on), every tab refreshes and the picks move on to new titles.
- **Never a blank card:**
  - A title joins a tab only once its picture is on the TV.
  - Automatic changes keep the old cards until all the new pictures are ready.
  - A small preview covers the moment a full picture is still being prepared.
- **Instant at power-on:** last time's pictures show straight away, while the new ones download.
- **Automatic turns:** every 15 s the cards turn to the next page, unless you're on the cards or tabs, or pressed a key in the last 15 s. The old cards fade out over the new ones, with no slide and no empty moment.

## Look and motion

- **Fonts:**
  - Clash Display for the UI. Fontshare, ITF Free Font License, **not included**: put your own copy at `launcher-ui/app/assets/fonts/ClashDisplay-Variable.ttf`, otherwise the app falls back to Inter.
  - Poppins for the story, cast names and detail lines (SIL OFL, included).
  - Material Symbols Rounded for the icons, drawn from a small icon font (Apache 2.0, included; rebuild it with `art/make_icon_font.py`).
- **Theme:** light by day and dark after sunset, or set either one.
- **Motion:**
  - Material's emphasized easing, and spring physics for focus.
  - Focus grows a card 5% with a lift, shadow and sheen, and no border.
  - Text is laid out at its final size, so it never rewraps while a card grows.
  - Measured on the TV: a steady 60 fps (16.7 ms frames) when moving and growing cards.
- **Click sounds:** at 30% of the original level.

## Configuration (`config.json`, pushed with `launcher-ui/config.sh`)

See [`launcher-ui/config.example.json`](launcher-ui/config.example.json). The keys this edition uses:

| Key | Meaning |
|---|---|
| `tmdb_key` | TMDB API key (pictures, details, picks). Required for content. |
| `tmdb_region` | Catalogue region (`AE`, `US`...) or `auto`. |
| `trakt_client_id` | Your Trakt app's Client ID. Turns on Up Next, Watchlist, Releases and Trending. |
| `trakt_client_secret` | Only if your Trakt app shows one. Newer PKCE apps don't. |
| `shelf`, `hidden_apps` | App row order, and apps to leave out. |
| `app_tiles` | `{ "package": { "logo": "file.png or https://…", "color": "#RRGGBB" } }`. Push the PNG with `config.sh --file`. |
| `city`, `theme`, `clock`, `weather`, `show_crypto` | Weather city, light/dark/auto, 12/24 h, the weather and crypto widgets. |

Keys and tokens live only in `config.json` and on the TV. Neither is in this repository.

## Build and install

```bash
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=/path/to/android-sdk
TV_HOST=<tv ip> launcher-ui/build-and-install.sh      # builds, installs, makes it Home, pushes config
TV_HOST=<tv ip> launcher-ui/config.sh my-config.json  # settings only, no rebuild
```

The TV needs Network debugging on while you install. Switch it off afterwards. Android 9 or later.

## Network use

TMDB (lists, pictures, details), Trakt (lists and your account, only with `trakt_client_id`), Wikidata (in-app links),
Open-Meteo (weather), and Cloudflare (the speed test, only while System info is open).

## Licences

The code is MIT ([LICENSE](LICENSE)), as in CleanYourTV and CleanYourTV UI. Poppins and Inter are under the SIL Open
Font License, and Material Symbols under Apache 2.0, with their licence files in `launcher-ui/app/assets/fonts/`.
Clash Display isn't distributed here.
