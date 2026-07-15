# Telegram Proxy — Android VPN Proxy Client

Клиент локального SOCKS5-прокси для Telegram на **Kotlin + Jetpack Compose (Material 3)**.  
Парсит **VLESS** (`vless://…`), импортирует **подписки** (base64-список серверов), генерирует конфиг **Xray-core** (VLESS + WebSocket + TLS/SNI) и поднимает локальный прокси **127.0.0.1:10808**.

## Требования

- Android Studio Hedgehog+ / JDK 17
- Android SDK 34, **minSdk 24**
- (Опционально) нативная библиотека Xray для реального трафика

## Сборка APK

1. Скопируйте `local.properties.example` → `local.properties` и укажите путь к SDK:
   ```
   sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk
   ```
2. Откройте папку `TelegramProxy` в Android Studio **или**:
   ```bash
   ./gradlew assembleDebug
   ```
3. APK: `app/build/outputs/apk/debug/app-debug.apk`

## Функции

| Функция | Описание |
|--------|----------|
| Подписки | Имя + VLESS URL + HTTPS subscription URL |
| Серверы | Список: страна, адрес, протокол (VLESS/WS/TLS) |
| Xray | Генерация JSON, SOCKS5 `:10808`, HTTP `:10809` |
| Telegram | Кнопка `tg://proxy?server=127.0.0.1&port=10808` |
| Service | Foreground Service + уведомление |
| UI | Compose, Material 3, тёмная тема, статус в реальном времени |

## Xray-core

Положите бинарник/`.so`:

```
app/src/main/jniLibs/arm64-v8a/libxray.so
app/src/main/assets/xray          # альтернатива: executable
```

`XrayCore` пробует: reflection API LibXray → process `xray run -c config.json` → config-only режим (UI/конфиг без туннеля).

## Использование

1. **Подписки** → вставьте `vless://…` или URL подписки → **Добавить / Импортировать**
2. **Серверы** → выберите ноду → **Подключить**
3. **Настроить прокси в Telegram** или вручную:  
   Настройки → Данные и память → Прокси → SOCKS5 → `127.0.0.1` / `10808`

## Структура

```
TelegramProxy/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/telegramproxy/
│       │   ├── MainActivity.kt
│       │   ├── ProxyService.kt
│       │   ├── ProxyViewModel.kt
│       │   ├── SubscriptionManager.kt
│       │   ├── VlessConfig.kt
│       │   ├── XrayCore.kt
│       │   ├── TelegramProxyApp.kt
│       │   └── ui/
│       └── res/
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## Лицензия

Учебный / personal project. Xray-core — отдельная лицензия MPL 2.0 (XTLS).
