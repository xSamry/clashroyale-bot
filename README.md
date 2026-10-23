# Merge Tactics Helper (Clash Royale)

Оверлей для Android: читает экран (OCR на устройстве) и подсказывает покупки, слияния, сферы и снаряжение.
Ничего не нажимает за тебя.

## Как получить APK через GitHub
1. На github.com создай новый репозиторий (New repository).
2. Загрузи в него ВСЁ содержимое этой папки, включая скрытую папку `.github`
   (через git: `git init && git add . && git commit -m init && git branch -M main && git remote add origin <URL> && git push -u origin main`).
3. Открой вкладку **Actions** → workflow **Build APK** (запустится сам; можно нажать Run workflow).
4. Когда сборка станет зелёной, открой её → внизу **Artifacts** → скачай `MergeTacticsHelper-apk` (внутри `app-debug.apk`).
5. Установи APK на телефон (разреши установку из неизвестных источников).

## Запуск
1. Открой приложение → «Разрешить поверх других окон».
2. «Запустить помощника» → подтверди запись экрана.
3. Открой Clash Royale (язык игры — English) → режим Merge Tactics. Панель можно двигать за заголовок.
4. Кнопка 🐞 показывает, что именно распознал OCR и где (x,y в % экрана) — по ней настраивай `regions` в meta.json.

## Обновление меты без пересборки
Положи свой `meta.json` (формат как в `app/src/main/assets/meta.json`) в
`Android/data/app.mergetactics.helper/files/` на телефоне.
