Xray-core binary (optional)
===========================

To enable real traffic proxying, put a Linux/Android xray binary named "xray"
into this assets folder, OR place libxray.so under:

  app/src/main/jniLibs/<abi>/libxray.so

Recommended sources:
  - https://github.com/XTLS/Xray-core/releases (android binaries / cross-build)
  - https://github.com/2dust/AndroidLibXrayLite
  - https://github.com/lancerxh/AndroidLibXrayLite

Without the native core the app still:
  - parses VLESS / subscriptions
  - generates full Xray JSON config (filesDir/xray_config.json)
  - runs Foreground Service + UI connection flow
  - opens tg://proxy for Telegram SOCKS5 127.0.0.1:10808
