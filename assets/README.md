# Assets

`feed-icon.svg` is the standard web feed icon, created for Mozilla Firefox and offered under a choice of the Mozilla Public License 1.1, the GNU GPL 2.0 or the GNU LGPL 2.1. Algofeed uses it under the MPL 1.1 (<https://www.mozilla.org/MPL/1.1/>). Source: <https://en.wikipedia.org/wiki/File:Feed-icon.svg>.

Files derived from it, also under the MPL 1.1:

- `shared/src/commonMain/kotlin/algofeed/ui/AppIcon.kt`: the same drawing as a Compose `ImageVector`.
- `desktopApp/icon.png`: a 512 px render, made with `rsvg-convert -w 512 -h 512 assets/feed-icon.svg -o desktopApp/icon.png`.
- `androidApp/src/main/res/drawable/ic_launcher_*.xml`: the Android adaptive launcher icon. The background is the gradient, the foreground and monochrome layers are the glyph scaled by 0.25 into the 66 dp safe zone.
