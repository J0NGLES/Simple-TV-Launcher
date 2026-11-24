# Simple TV Launcher

**English** | [Русский](README.ru.md) | [Українська](README.ua.md)

A lightweight tvOS-style Android TV launcher with a frosted-glass look: app tiles, weather, quick settings. No AndroidX, about 150 KB.

<img width="1920" height="1080" alt="photo_1_2026-11-20_23-49-09" src="https://github.com/user-attachments/assets/bdc50171-cd1f-4d2b-9eb2-0d3adc442961" />
<img width="1920" height="1080" alt="photo_2_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/55f39c73-cec2-42e0-9300-83f936d00d95" />

<details>

<summary>More screenshots</summary>

<img width="1920" height="1080" alt="photo_3_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/dd6abf04-b92a-4f22-80e2-b3933fec77cb" />
<img width="1920" height="1080" alt="photo_4_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/cbd59b69-8d66-411f-a8ce-77eab5dc9fac" />
<img width="1920" height="1080" alt="photo_5_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/b92c3e6d-cc80-463a-970e-f8d062f7575a" />
<img width="1920" height="1080" alt="photo_6_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/1f921875-2e83-44a5-94fa-093b2381037d" />
<img width="1920" height="1080" alt="photo_7_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/10f5dbf4-75b2-475e-ae87-c7f4a16534d4" />
<img width="1920" height="1080" alt="photo_8_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/62825014-bfab-4925-b096-0b91cf0dc675" />
<img width="1920" height="1080" alt="photo_9_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/ec2aaceb-6579-4fc5-a386-59ba31de55d1" />
</details>

**Download:** [latest release](https://github.com/J0NGLES/Simple-TV-Launcher/releases/latest) · **Discussion (Russian):** [4PDA](https://4pda.to/forum/index.php?showtopic=1129772)

## Features
- App tiles with sorting, hiding, favorites and a configurable grid: tile size (Auto to Huge), grid position (top / center / bottom), left or center alignment, optional cyclic navigation within a row
- Wallpapers (image, video, gradient), adjustable blur, dimming and saturation, optional glow around the selected tile
- Weather (MET Norway, 7Timer, Bright Sky); metric / US / UK units
- Network indicator with a crossed-out icon when there is no internet access
- Voice search via a chosen app, VPN and DPI client picker
- Parental control (PIN) and "Granny mode"
- Launch an app or return to the home screen when the device wakes from sleep; launch an app at boot
- Font size (4 steps), 12/24-hour clock
- Settings backup and restore
- Russian, English and Ukrainian UI (follows the system language)
## Wallpapers
A [wallpaper pack on Google Drive](https://drive.google.com/file/d/1ou_BwechTGbiR9tA5E9WUOuZsPeq6Zew/) for the home screen. Their color and texture shape the look of the whole launcher, so try them together with blur, dimming and saturation. Video backgrounds can be found, for example, [here](https://ihaqdonate.com/resources/backgrounds); converting them to mp4 keeps them small and makes sure the launcher picks them up. Just put wallpapers and video backgrounds into internal storage and the app will find them.

## Build
Requires Android Studio (or JDK 17+ and the Android SDK) and Gradle 9.x.

```
gradle assembleRelease
```

Without `keystore.properties` the apk is signed with the debug key. To sign with your own key, create `keystore.properties` in the project root:

```
storeFile=path/to/key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Never commit this file or any `.jks` file (they are listed in `.gitignore`).

Tested on RockTek G2, Mecool KM2 Deluxe, Xiaomi TV A Pro 2026, Philips 50PUS8506/60. minSdk 24.

## License
MIT, see [LICENSE](LICENSE).
