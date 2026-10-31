# Asset provenance

Downloaded 2026-10-31 for this offline design exploration. Photo crops are local; the gallery never fetches these URLs at runtime. Catalog names, years, durations, progress, synopsis and poster typography are invented examples, not actual films or claims of a catalog relationship.

| Local artwork | Exact public source | Use / edits |
| --- | --- | --- |
| `assets/north-source.jpg` | [Luca Bravo — brown house near body of water](https://unsplash.com/photos/brown-house-near-body-of-water-zAjdgNXsMeg), [original image endpoint](https://images.unsplash.com/photo-1470770841072-f978cf4d019e) | *The Quiet North*: landscape, portrait crop and Home backdrop. Source page and named photographer verified directly. |
| `assets/train-source.jpg` | [Unsplash image endpoint photo-1519608487953-e999c86e7455](https://images.unsplash.com/photo-1519608487953-e999c86e7455) | *After the Last Train*: wide street photograph. Exact source retained; photographer identity not independently confirmed, so none is guessed. |
| `assets/kyoto-source.jpg` | [Unsplash image endpoint photo-1493976040374-85c8e12f0c0e](https://images.unsplash.com/photo-1493976040374-85c8e12f0c0e) | *A Summer in Kyoto*: warm detailed human street scene. Exact source retained; photographer identity not independently confirmed. |
| `assets/table-source.jpg` | [Unsplash image endpoint photo-1414235077428-338989a2e8c0](https://images.unsplash.com/photo-1414235077428-338989a2e8c0) | *Sunday, Slowly*: restaurant photograph. Exact source retained; photographer identity not independently confirmed. |

Photos were obtained as public Unsplash images with resize/quality parameters. [Unsplash License](https://unsplash.com/license) permits downloading/modifying/using photos; no photographer endorsement, ownership transfer or production rights clearance is implied. Review attribution and any depicted-person/trademark implications before using the concept art in a shipped catalog. Original JPEG downloads are preserved for traceability.

`north.webp`, `train.webp`, `kyoto.webp`, `table.webp`: 520 × 293, WebP quality 75. `backdrop.webp`: 1000 × 563, WebP quality 76. `backdrop-soft.webp`: 240 × 135 softened cached version. `train-soft.webp` and `table-soft.webp`: 160 × 90 softened cached versions for the material comparison. `source-contact.jpg` is the preparatory photo contact sheet. ImageMagick made the crops/compression/softening; no image-generation tool was available or used.

**Font:** [Manrope variable font, Google Fonts source](https://github.com/google/fonts/tree/main/ofl/manrope), [upstream font](https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf). Original `Manrope.ttf`, the supplied `Manrope-OFL.txt` (SIL Open Font License 1.1), and a 27,140-byte Latin WOFF2 subset are included. FontTools/Brotli created the subset; the temporary tooling cache was removed. The HTML asset packer needs only Python's standard library.

**Marks / app tiles:** original inline SVG sail/control drawings and simplified concept representations of the observed Live TV / Google Play / Settings / YouTube app inventory. Recognizable app names and brand colors identify their owners; these simplified drawings are not supplied as official logo assets. The primary UI uses Manrope; Georgia/system serif appears only as invented film artwork lettering.
