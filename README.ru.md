# Simple TV Launcher

[English](README.md) | **Русский** | [Українська](README.ua.md)

Лёгкий лаунчер для Android TV в стиле tvOS: «матовое стекло», плитки приложений, погода, быстрые настройки. Без AndroidX, около 150 КБ.

<img width="1920" height="1080" alt="photo_1_2026-11-20_23-49-09" src="https://github.com/user-attachments/assets/bdc50171-cd1f-4d2b-9eb2-0d3adc442961" />
<img width="1920" height="1080" alt="photo_2_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/55f39c73-cec2-42e0-9300-83f936d00d95" />

<details>

<summary>Ещё скриншоты</summary>

<img width="1920" height="1080" alt="photo_3_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/dd6abf04-b92a-4f22-80e2-b3933fec77cb" />
<img width="1920" height="1080" alt="photo_4_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/cbd59b69-8d66-411f-a8ce-77eab5dc9fac" />
<img width="1920" height="1080" alt="photo_5_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/b92c3e6d-cc80-463a-970e-f8d062f7575a" />
<img width="1920" height="1080" alt="photo_6_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/1f921875-2e83-44a5-94fa-093b2381037d" />
<img width="1920" height="1080" alt="photo_7_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/10f5dbf4-75b2-475e-ae87-c7f4a16534d4" />
<img width="1920" height="1080" alt="photo_8_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/62825014-bfab-4925-b096-0b91cf0dc675" />
<img width="1920" height="1080" alt="photo_9_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/ec2aaceb-6579-4fc5-a386-59ba31de55d1" />
</details>

**Скачать:** [последняя версия в Releases](https://github.com/J0NGLES/Simple-TV-Launcher/releases/latest) · **Обсуждение:** [4PDA](https://4pda.to/forum/index.php?showtopic=1129772)

## Возможности
- Плитки приложений, сортировка, скрытие, избранное и настраиваемая сетка: размер плиток (от «Авто» до «Огромных»), положение сетки (сверху / по центру / снизу), выравнивание по левому краю или по центру, опциональная циклическая прокрутка по ряду
- Обои (фото, видео, градиент), настраиваемые размытие, затемнение и насыщенность, опциональное свечение вокруг выбранной плитки
- Погода (MET Norway, 7Timer, Bright Sky), единицы: метрические / США / Великобритания
- Индикатор сети с перечёркнутым значком, если нет доступа в интернет
- Голосовой поиск через выбранное приложение, выбор VPN- и DPI-клиентов
- Родительский контроль (PIN) и «Режим для бабушек»
- Запуск приложения или возврат на главный экран при выходе из сна, запуск приложения при включении устройства
- Размер шрифта (4 варианта), 12- и 24-часовой формат времени
- Резервная копия и восстановление настроек
- Русский, английский и украинский язык (по языку системы)
## Обои
Подборка обоев для главного экрана: [Google Диск](https://drive.google.com/file/d/1ou_BwechTGbiR9tA5E9WUOuZsPeq6Zew/). Цвет и фактура обоев влияют на внешний вид всего лаунчера, так что попробуйте сочетать их с размытием, затемнением и насыщенностью. Видеофоны можно взять, например, [здесь](https://ihaqdonate.com/resources/backgrounds); лучше конвертировать их в mp4: так они меньше весят, и лаунчер их точно увидит. Обои и видеофоны достаточно положить во внутреннюю память, приложение найдёт их само.

## Сборка
Нужны Android Studio (или JDK 17+ и Android SDK) и Gradle 9.x.

```
gradle assembleRelease
```

Без файла `keystore.properties` apk подписывается отладочным ключом. Для своей подписи создайте в корне проекта `keystore.properties`:

```
storeFile=путь/к/ключу.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Этот файл и `.jks` в `.gitignore` и не должны попадать в репозиторий.

Тестировалось на: RockTek G2, Mecool KM2 Deluxe, Xiaomi TV A Pro 2026, Philips 50PUS8506/60. minSdk 24.

## Лицензия
MIT, см. [LICENSE](LICENSE).
