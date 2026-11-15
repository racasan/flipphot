# Flipphot

A minimal, offline photo frame app for Android, built to run well on old tablets.

Flipphot turns a tablet into a digital photo frame: it shows the photos stored
on the device as a full-screen, cross-fading slideshow. It was written for a
Huawei MediaPad T3 10 (Android 7.0), and works on Android 7.0 and newer.

## Features

- Full-screen slideshow of the photos on the device (internal storage and SD card)
- Choose one folder (album) or show everything
- Shuffle or oldest-first order
- Time per photo from 5 seconds to 1 hour
- On-screen warning when the battery drops below 10% and the charger is unplugged
- Keeps the screen on while the app is open

## What it deliberately leaves out

- No internet access: the app does not request the network permission
- No audio
- No ads, accounts, or tracking
- No third-party libraries: plain Android framework code only

The only permission it asks for is reading the photos on the device.

## Light on resources

- The APK is about 17 KB
- Each photo is scaled down to screen size before it is shown, and at most two
  are held in memory
- Nothing runs between slides except a single timer
- Measured on the MediaPad T3 10: about 40 MB of memory, 0% CPU between slides

## Using it

Tap the screen to show the controls: Previous, Pause, Next, and Settings.

Photos come from the device's media index, so anything the tablet's own Gallery
app can see will appear. If photos you just copied over are missing, restart
the tablet so it re-indexes them.

## Building

You need the Android SDK (platform 36) and JDK 17 or newer.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. The
release build is signed with the debug key, which is fine for installing on
your own devices but not for publishing to an app store.

Install it over USB with:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

## License

[MIT](LICENSE)
