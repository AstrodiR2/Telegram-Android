# AstrodiR Telegram — AI-Enhanced Fork

Форк DrKLO/Telegram с клиентской AI интеграцией.
Разработчик: @AstrodiR (ID: 7678968081)

## Что сделано

### AiManager.java
- [x] Role 0: Квас — дефолтная персона, анти-инъекции, тёплый стиль
- [x] Role 1: Assistant — краткость
- [x] Role 2: Summarizer
- [x] Role 3: Proofreader — только исправление, без комментариев
- [x] Role 4: Квас-агент — групповой агент
- [x] Веб-поиск через OpenRouter (цитаты подавлены)
- [x] YouTube поиск (YouTube Data API v3) — триггер "квас найди [текст]"
- [x] Классификация тона → реакции (👍😂👎💩🤡)
- [x] max_tokens: /ai → 500, Квас-агент → 1200
- [x] TTS через Google Translate → mp3

### CommandHandler.java
- [x] /ai [текст] — запрос к ИИ
- [x] /ai user — автоответ на @Oposut / слово "квас" / реплай на сообщения юзера
- [x] /ai clean mem — очистка истории диалога
- [x] /log — лог событий (триггеры, ошибки, кулдауны)
- [x] Кулдаун 10с per-chat
- [x] Кэш последних 100 message_id юзера per-chat

### MessagesController.java
- [x] Хук входящих сообщений для /ai user
- [x] Автоответ с реплай-контекстом (имя автора + текст)

### Квас-агент (Role 4)
- [x] Новый системный промпт (создатель @Astrodir, анти-имперсонация)
- [x] Долгая память (триггер "запомни [что-то]") → SharedPreferences
- [x] /ai clean mem очищает долгую память агента
- [x] Расширенный контекст 60 сообщений
- [x] Красивый /ai help (┌─── Квас · Help ───┐)
- [x] Форматирование ответа (───「 вопрос 」───)
- [x] Реплай контекст с именем автора
- [x] getGroupHistory(long, int) — чтение истории группы
- [x] Квас знает о своих возможностях

## Что нужно сделать

### Следующий приоритет
- [ ] DuckDuckGo поиск (Java, триггер "квас найди [запрос]") + логи в /log
- [ ] Музыка → mp3 файл (триггер "квас музыка [текст]")
- [ ] Foreground Service 24/7 (отложено)

### Квас-агент — дальше
- [ ] Расширенный контекст по запросу (60 сообщений, "могу прочитать выше")
- [ ] Менее шаблонные ответы (убрать 🚀 и шаблонщину)
- [ ] YouTube ссылка → yt-dlp → отправить как файл
- [ ] ИИ различает свои сообщения vs сообщения юзера

## Build
- Gradle: assembleRelease, флейвор afat
- APK: **/intermediates/apk/afat/release/*.apk
- Подпись: gradle.properties через sed из secrets, keystore → TMessagesProj/config/release.keystore
- CI: GitHub Actions

## Архитектура
TMessagesProj/src/main/java/org/telegram/messenger/
├── AiManager.java       # AI вызовы, роли, поиск, реакции
├── CommandHandler.java  # Роутинг команд, кэш, кулдауны, лог
└── MessagesController.java  # Хук входящих, автоответ
