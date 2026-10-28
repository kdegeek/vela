# Vela launcher spike (throwaway)

**Do not extend this.** It exists to judge the look and the remote-driven focus model of the
Vela home screen. Keeping any of it is a separate decision.

## Run

```sh
python3 -m http.server 8765   # from this folder
```

Open http://localhost:8765 in Chromium at 1920x1080 (or fullscreen). Drive it with
arrow keys, Enter, and Esc/Backspace. Gamepad input (d-pad/left stick, A, B) is wired but untested;
no controller was available. Layout is sized in `rem` derived from the viewport, so it scales to 4K
without reflowing, but the backdrops are 1920px wide and will look soft at 4K.

## What it proves

- Cinematic hero: full-bleed art crossfades on focus, with a slow drift and a per-title tint that
  colors the focus glow, progress bars, and the bottom-left wash.
- Spatial focus: rows pin the focused tile to the left edge, rows above slide away, and moving
  between rows picks the nearest tile.
- App row: Plex, YouTube, Jellyfin, Crunchyroll, Steam, and Home each preview something from
  inside that app. Enter shows a launch transition (no real app launch).

Screenshots from headless Chromium at 1920x1080 are in `screenshots/`.

## Not in scope

Kiosk shell, real app launching, Home Assistant, the OS image, and live Plex/Jellyfin data.
App glyphs are stand-ins, not the official logos. Pairing films with apps is arbitrary mock data.

## Art and font credits

Fonts: Instrument Serif and Manrope (SIL Open Font License), via Fontsource.

All film art is from Blender open movies, via Wikimedia Commons. All are CC BY except Cosmos
Laundromat's backdrop, which is CC BY-SA:

- `bd-bbb.jpg` - CC BY 3.0, Blender Foundation / Project Peach - https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_-_forest.jpg
- `bd-cam.jpg` - CC BY 3.0, Francesco Siddi, Pablo Vazquez - https://commons.wikimedia.org/wiki/File:Caminandes_gran_dillama.png
- `bd-charge.jpg` - CC BY 4.0, Blender Studio - https://commons.wikimedia.org/wiki/File:Charge_-_Blender_Open_Movie-YouTube_thumbnail.jpg
- `bd-cosmos.jpg` - CC BY-SA 4.0, Blender Foundation - https://commons.wikimedia.org/wiki/File:CosmosLaundromat-final-scene.jpg
- `bd-ed.jpg` - CC BY 2.5, Ton Roosendaal et al. - https://commons.wikimedia.org/wiki/File:Elephants_Dream_s5_both.jpg
- `bd-sintel.jpg` - CC BY 3.0, David REVOY - https://commons.wikimedia.org/wiki/File:Durian_-_Sintel-wallpaper-sintel.jpg
- `bd-sintel2.jpg` - CC BY 3.0, © copyright Blender Foundation www.sintel.org - https://commons.wikimedia.org/wiki/File:Sintel-screenshot-4.jpg
- `bd-spring.jpg` - CC BY 4.0, Andy Goralczyk / Blender Foundation - https://commons.wikimedia.org/wiki/File:Spring_-_Blender_Open_Movie.webm
- `bd-sprite.jpg` - CC BY 4.0, Blender Studio - https://commons.wikimedia.org/wiki/File:Sprite_Fright_-_Open_Movie_by_Blender_Studio.webm
- `bd-tos.jpg` - CC BY 3.0, (CC) Blender Foundation | Project Mango - https://commons.wikimedia.org/wiki/File:Tears_of_Steel_frame_01_2a.jpg
- `bd-tos2.jpg` - CC BY 3.0, (CC) Blender Foundation | Project Mango - https://commons.wikimedia.org/wiki/File:Tears_of_Steel_frame_09_1a.jpg
- `p-bbb.jpg` - CC BY 3.0, (c) copyright Blender Foundation | peach.blender.org - https://commons.wikimedia.org/wiki/File:Big_buck_bunny_poster_big.jpg
- `p-charge.jpg` - CC BY 4.0, Blender Studio - https://commons.wikimedia.org/wiki/File:Charge-movie_poster.jpg
- `p-cosmos.jpg` - CC BY 4.0, Andy Goralczyk and the Blender Foundation - https://commons.wikimedia.org/wiki/File:CosmosLaundromatPoster.jpg
- `p-sintel.jpg` - CC BY 3.0, (c) copyright Blender Foundation | durian.blender.org - https://commons.wikimedia.org/wiki/File:Sintel_poster.jpg
- `p-sprite.jpg` - CC BY 4.0, Blender Studio - https://commons.wikimedia.org/wiki/File:Sprite_Fright-movie_poster.jpg
- `p-tos.jpg` - CC BY 3.0, (CC) Blender Foundation | Project Mango - https://commons.wikimedia.org/wiki/File:Tos-poster.png
