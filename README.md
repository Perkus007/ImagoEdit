# ImagoEdit

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

A layer-based image editor for Android, built with Jetpack Compose.

## Features
- Layers with masks and opacity
- Brush, eraser, clone stamp, healing brush
- Selection: shapes, polygon, magic wand, selection brush; grow, blur and smooth the edge; transform
- Move, scale, rotate and flip layers
- Text with bundled and custom fonts, variable weight
- Fill and stroke
- Canvas crop, resize, rotate and flip
- Undo/redo, autosave and recovery
- Export to PNG, JPEG, WebP, AVIF with quality control
- English, Russian, Ukrainian

## Requirements
Android 11 (API 30) or newer. All ABIs: arm64-v8a, armeabi-v7a, x86_64, x86.

## Download
Get the APK from [Releases](../../releases).

## Building from source
- Android Studio with Android SDK 37, NDK and CMake 3.22.1
- The first build needs internet access: CMake downloads libjpeg-turbo 3.2.0
  and libwebp 1.6.0 (verified by SHA-256)

```
./gradlew assembleRelease
```

## Contributing
Bug reports and pull requests are welcome. By contributing you agree that
your contributions are licensed under GPL-3.0-or-later.

## Third-party
- [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) — IJG, BSD-3-Clause, zlib.
  This software is based in part on the work of the Independent JPEG Group.
- [libwebp](https://chromium.googlesource.com/webm/libwebp) — BSD-3-Clause
- [avif-coder](https://github.com/awxkee/avif-coder) — MIT (bundles libavif, dav1d — BSD-2-Clause; libyuv — BSD-3-Clause; Eigen — MPL-2.0; Rust codecs — Apache-2.0)
- AndroidX / Jetpack Compose — Apache-2.0
- Fonts: Caveat, Handjet, Tiny5 — SIL OFL 1.1; Roboto Slab — Apache-2.0

Full license texts: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## License
Copyright (C) 2026 Perkus

ImagoEdit is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for details.