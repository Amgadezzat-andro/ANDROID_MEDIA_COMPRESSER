# <img src="docs/screenshots/icon.png" height="32"> Nothing Media Compressor

Make photos and videos much smaller on your Android phone and get your storage back. A Nothing OS styled fork of [FFShare](https://github.com/caydey/ffshare).

<p align="left">
  <img src="docs/screenshots/1_home.png" width="200">
  <img src="docs/screenshots/2_save_folder.png" width="200">
  <img src="docs/screenshots/4_progress.png" width="200">
  <img src="docs/screenshots/5_results.png" width="200">
</p>

## What it does

- **Pick photos and videos together** with the system photo picker, or share them straight from the gallery ("Compress & save" or "Compress & share"). "Browse files" handles audio and more than 100 files at once.
- **Swipe a quality card**: Ultra, Smallest, Balanced (recommended), Best quality or Custom. Every card shows a typical result.
- **Photo format**: WebP for the smallest files, or keep the original format (a JPEG stays a JPEG).
- **Saves to a folder you choose**, then offers to move the originals to the trash (kept there 30 days, with Undo). Works for files shared from Google Photos.
- **Keeps what matters**: the date taken and location, upright rotation, transparency in PNGs, and animation in GIFs. A file that would only get bigger stays as it is.
- **Big batches**: photos are compressed several at a time while videos take their own lane. It keeps running with the screen off, shows progress in a notification and stops cleanly on Cancel.
- **Fast video on real phones**: uses the phone's hardware encoder when it has one, and falls back to normal encoding automatically if the encoder fails or hangs.

## Looks

Monochrome with one red accent, dot-matrix headlines ([Doto](https://fonts.google.com/specimen/Doto)) and monospace labels ([Space Mono](https://fonts.google.com/specimen/Space+Mono)), light and dark. The launcher icon has a monochrome layer for themed icons.

<p align="left">
  <img src="docs/screenshots/3_dark.png" width="200">
  <img src="docs/screenshots/6_setup.png" width="200">
</p>

## Building

The FFmpeg library is prebuilt in `app/libs` (arm64 with MediaCodec), so a normal Android build works:

```sh
./gradlew :app:assembleDebug
```

To rebuild FFmpeg itself, run `./build_ffmpegkit.sh` (needs Nix).

The app id is `com.caydey.ffshare.plus`, so it installs next to the original FFShare.

## Credits and license

- [FFShare](https://github.com/caydey/ffshare) by caydey, the app this is built on
- [Scrambled-Exif](https://gitlab.com/juanitobananas/scrambled-exif) by juanitobananas, which inspired the original
- [ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next) by arthenica, the FFmpeg library
- [Timber](https://github.com/JakeWharton/timber)
- Fonts Doto and Space Mono, SIL Open Font License

GPL-3.0, like FFShare. See [LICENSE](LICENSE). Not affiliated with Nothing Technology.
