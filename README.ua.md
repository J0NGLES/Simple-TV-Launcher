# Simple TV Launcher

[English](README.md) | [Русский](README.ru.md) | **Українська**

Легкий лаунчер для Android TV у стилі tvOS: «матове скло», плитки застосунків, погода, швидкі налаштування. Без AndroidX, близько 175 КБ.

<img width="1920" height="1080" alt="photo_1_2026-11-20_23-49-09" src="https://github.com/user-attachments/assets/bdc50171-cd1f-4d2b-9eb2-0d3adc442961" />
<img width="1920" height="1080" alt="photo_2_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/55f39c73-cec2-42e0-9300-83f936d00d95" />

<details>

<summary>Ще скриншоти</summary>

<img width="1920" height="1080" alt="photo_3_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/dd6abf04-b92a-4f22-80e2-b3933fec77cb" />
<img width="1920" height="1080" alt="photo_4_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/cbd59b69-8d66-411f-a8ce-77eab5dc9fac" />
<img width="1920" height="1080" alt="photo_5_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/b92c3e6d-cc80-463a-970e-f8d062f7575a" />
<img width="1920" height="1080" alt="photo_6_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/1f921875-2e83-44a5-94fa-093b2381037d" />
<img width="1920" height="1080" alt="photo_7_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/10f5dbf4-75b2-475e-ae87-c7f4a16534d4" />
<img width="1920" height="1080" alt="photo_8_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/62825014-bfab-4925-b096-0b91cf0dc675" />
<img width="1920" height="1080" alt="photo_9_2026-11-20_23-49-10" src="https://github.com/user-attachments/assets/ec2aaceb-6579-4fc5-a386-59ba31de55d1" />
</details>

**Завантажити:** [остання версія в Releases](https://github.com/J0NGLES/Simple-TV-Launcher/releases/latest) · **Обговорення (російською):** [4PDA](https://4pda.to/forum/index.php?showtopic=1129772)

## Можливості
- Плитки застосунків, сортування, приховування, обране та налаштовувана сітка: розмір плиток (від «Авто» до «Величезних»), положення сітки (угорі / по центру / внизу), вирівнювання плиток по ширині або по центру, назви та підписи ліворуч / по центру / праворуч, плитки-банери або квадратні значки, за бажанням без підкладки під застосунками, опціональне циклічне прокручування по ряду
- Шпалери (зображення, відео, градієнт), налаштовувані розмиття, затемнення та насиченість, опціональне світіння навколо вибраної плитки, опціональні контрастні написи
- Онлайн-каталог шпалер wallhaven.cc з темами; автозміна з локальної теки або онлайн-каталогу (не частіше ніж раз на 5 хвилин), обмеження кількості збережених шпалер і захист уподобаних від видалення
- Кнопка «Додому» повертає виділення на плитку застосунку, з якого ви щойно вийшли
- Три рівні ефекту скла, до 15 квадратних значків у ряду та анімації в стилі tvOS (типово вимкнені): скло з пружним рухом на головному екрані, в меню, погоді та налаштуваннях
- Погода (MET Norway, 7Timer, Bright Sky), одиниці: метричні / США / Велика Британія
- Індикатор мережі з перекресленою піктограмою, якщо немає доступу до інтернету
- Голосовий пошук через вибраний застосунок, вибір VPN- і DPI-клієнтів
- Батьківський контроль (PIN) і «Режим для бабусь»
- Запуск застосунку або повернення на головний екран при виході з сну, запуск застосунку при ввімкненні пристрою
- Розмір шрифту (4 варіанти), 12- і 24-годинний формат часу
- Резервна копія та відновлення налаштувань
- Російська, англійська та українська мови (за мовою системи)

## Шпалери
Добірка шпалер для головного екрана: [Google Диск](https://drive.google.com/file/d/1ou_BwechTGbiR9tA5E9WUOuZsPeq6Zew/). Колір і фактура шпалер впливають на вигляд усього лаунчера, тож спробуйте поєднувати їх із розмиттям, затемненням і насиченістю. Відеофони можна взяти, наприклад, [тут](https://ihaqdonate.com/resources/backgrounds); краще конвертувати їх у mp4: так вони менше важать, і лаунчер їх точно побачить. Шпалери та відеофони достатньо покласти у внутрішню пам'ять, застосунок знайде їх сам.

## Збірка
Потрібні Android Studio (або JDK 17+ і Android SDK) та Gradle 9.x.

```
gradle assembleRelease
```

Без файлу `keystore.properties` apk підписується налагоджувальним ключем. Для власного підпису створіть у корені проєкту `keystore.properties`:

```
storeFile=шлях/до/ключа.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Цей файл і `.jks` перелічені в `.gitignore` і не повинні потрапляти до репозиторію.

Тестувалося на: RockTek G2, Mecool KM2 Deluxe, Xiaomi TV A Pro 2026, Philips 50PUS8506/60. minSdk 24.

## Ліцензія
MIT, див. [LICENSE](LICENSE).

Анімації в стилі tvOS засновані на коді, перенесеному з проєкту [CleanYourTV](https://github.com/Gathin23/cleanyourtv) (MIT, © 2026 Gathin), текст ліцензії: [licenses.txt](app/src/main/assets/licenses.txt).
