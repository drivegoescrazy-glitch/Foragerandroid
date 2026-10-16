# Forager Android Companion

This project currently provides:
- A button to launch the official Android edition of Forager if it is installed.
- If it is not installed, the same button opens its Google Play listing.
- A folder scanner for a Linux Forager installation that reports likely game files and ELF executables.

## Important limitation

The Linux files are **not converted into an Android APK** by this app. Forager's GameMaker YYC/native build includes platform-specific machine code. Merely repackaging the Linux x86_64 executable as an APK will not make it run on Android/ARM64. The open-source Butterscotch runner does not support Forager's YYC build. A true custom port would require an Android-compiled game build or a full Linux execution/emulation stack with graphics/audio integration and testing on the target handheld.

## Current use

1. Install the app.
2. Tap **Open / Install Forager for Android** to open the native Android version if present, or the Play Store listing if not.
3. Tap **Analyze Linux Forager folder** to inspect a local Linux copy. This scan is diagnostic only; it does not modify your files or build a game APK.

The native Android edition is separate from the Linux Steam files. This project does not bundle game content.
