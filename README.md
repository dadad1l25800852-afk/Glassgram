# YouFree + Happ-совместимые подписки · версия 0.2

> **Статус:** исходный код Android-приложения и рабочего Go-backend. Kotlin-парсер
> и Go-сервер проверены локально; Android APK, воспроизведение YouTube и работу
> на сети российского провайдера **не удалось проверить** в этой среде.

## Что изменилось по запросу

В YouFree появился **встроенный сетевой модуль на открытом XTLS/libXray**.
Приложение **не является модификацией или копией проприетарного Happ**, но
умеет импортировать часть тех же открытых подписок и серверов.
Ни отдельный Happ, ни Android VPN, ни root для этого варианта не нужны.
Сеть переключается только внутри WebView приложения, **не в отдельном
официальном приложении YouTube на телефоне**.

**Как пользоваться после успешной сборки и установки APK:**

1. Открой YouFree → шестерёнка → **Подключение Happ**.
2. Вставь **обычную HTTPS-ссылку подписки** либо `vless://`, `trojan://`,
   `ss://`, `socks://`, `hysteria2://`, `hy2://` или совместимую `vmess://` ссылку.
   Поддерживаются простые `happ://add/` обёртки.
3. Нажми **«Импортировать и выбрать сервер»**. Приложение получает открытые
   конфигурации, проверяет соединение к YouTube `generate_204` через Xray
   и выбирает сервер с минимальной измеренной задержкой.
4. После успешной проверки откроется **обычная мобильная страница YouTube**
   `https://m.youtube.com/` в WebView. Это YouTube как сайт, а не
   самодельный плеер. Для этого пути не нужен свой API-сервер.
5. Кнопка обновления рядом с названием сервера пробует следующий. При
   ошибке загрузки основной страницы WebView есть до двух попыток смены
   сервера автоматически.

**Ограничения:**

- `happ://crypt…` (`crypt`, `crypt2`, `crypt3`, `crypt4`, `crypt5` и т.п.)
  — **проприетарные зашифрованные ссылки Happ**, не импортируются. Попроси
  своего провайдера дать обычную HTTPS-подписку или отдельную открытую
  конфигурацию. Не нужно сообщать нам пароль или саму подписку.
- XTLS/libXray v26.11.9 не поддерживает legacy VMess QR (`vmess://` с
  Base64(JSON)), Clash/Mihomo YAML и некоторые нестандартные транспорты.
- Встроенный WebView может не воспроизводить отдельные YouTube-видео из-за
  политики YouTube, браузерных кодеков, авторизации или DRM; подтвердить
  универсальную совместимость нельзя. Измерение задержки не проверяет
  передачу видеосегментов и пропускную способность.
- Переключение не переносит активное TLS-соединение: видео или страница
  могут перезагрузиться, и текущее место просмотра не обязательно сохранится.
- Без подписки/доступного сервера нет гарантированного обхода блокировки.
  Бесплатные общедоступные прокси в проекте не встроены.
- Видеолента, поиск и локальные подписки из исходного **нативного** YouFree
  сохранены, но для их метаданных по-прежнему требуется свой API-backend.
  Обычный YouTube открыт отдельной кнопкой с глобусом.
- Приложение **не перехватывает трафик других Android-приложений**; официальное
  приложение YouTube, установленное отдельно, продолжит использовать обычную сеть.

## Сборка нового APK на GitHub с телефона

1. Создай публичный или приватный репозиторий GitHub и загрузи **содержимое**
   папки `YouFree` (включая скрытую `.github/workflows/android-apk.yml`).
2. Перейди на GitHub во вкладку **Actions → Build YouFree APK → Run workflow**.
3. Workflow запускает тесты Go, скачивает **только с официального релиза**
   `XTLS/libXray` нативную Android-библиотеку v26.11.9 и собирает debug APK
   с Gradle 8.11.1, JDK 17, Android SDK 35.
4. Если сборка прошла, открой запуск → **Artifacts → YouFree-Android-APK**,
   скачай ZIP и извлеки `YouFree.apk` на телефон.
5. Если GitHub Actions упал, APK ещё не готов: необходимо увидеть журнал
   ошибки и исправить проект. Успешная компиляция и успешное воспроизведение
   ещё не подтверждены.

Для локальной Android Studio сборки нужна `app/libs/libXray.aar` из
[официального релиза XTLS/libXray v26.11.9](https://github.com/XTLS/libXray/releases/tag/v26.11.9),
так как бинарные файлы в этот исходный архив не включены. При сборке из
GitHub Actions библиотека скачивается автоматически. Не используй неизвестные
зеркала AAR.

### Как устроено подключение

```text
Ввод HTTPS-подписки / открытых ключей сервера
        ↓
HappSubscription: HTTPS + проверка лимита/формата
        ↓
libXray: конвертация ссылок → Xray outbound-конфигурации
        ↓
libXray pingBatch (YouTube generate_204) → оценка задержек
        ↓
Лучший доступный сервер → встроенный HTTP CONNECT прокси
        ↓
Android WebView ProxyController → https://m.youtube.com
```

На прокси Xray используется только входящий порт `127.0.0.1` телефона.
Google-трафик проходит через него по HTTPS без расшифровки Google TLS.
Прокси на localhost без отдельного пароля теоретически доступен другим
локальным приложениям; для коммерческого распространения требуется
дополнительный аудит изоляции и защиты. Секретная подписка хранится в
Keystore-зашифрованных настройках (но передаётся своему серверу подписки при
обновлении, как и в других подписочных клиентах).

**Исходные технические документы:**
- https://github.com/Flyfrog-LLC/Happ-docs/blob/main/faq/adding-configuration-subscription.md
- https://github.com/XTLS/libXray#api
- https://github.com/XTLS/libXray/releases/tag/v26.11.9
- https://developer.android.com/reference/androidx/webkit/ProxyController

---

## Сохранённый первый вариант YouFree (свой Go HTTPS relay)

Ниже описание прежнего нативного YouFree и его отдельного серверного маршрута.
Это по-прежнему доступно **опционально** и не требуется для просмотра
мобильной страницы YouTube через импортированную Happ-совместимую подписку.

# YouFree — Android + HTTPS CONNECT relay

**Status: source-code prototype; backend tests pass, Android build and Russian network playback NOT verified.**

YouFree is a native Kotlin / Jetpack Compose video discovery app (Android 10+).
It uses the official YouTube Data API v3 for metadata and the official YouTube iframe
player for video. It contains an *opt-in* local HTTP CONNECT proxy for its WebView
which connects to a personally operated HTTPS relay. No Android `VpnService` or root.
Media3/ExoPlayer handles independently licensed HLS, DASH and MP4 streams.

## What works in source / what is not implemented

| Feature | Implementation / limitation |
| --- | --- |
| Home, search, channel, thumbnails | YouTube Data API key on backend; official API quota applies |
| YouTube playback | Embedded YouTube player; not using Media3 for YouTube |
| Without Android VPN | WebView proxy → 127.0.0.1 → TLS remote relay → direct HTTPS to YouTube |
| Direct / relay choice | One Youtube connectivity probe + low-latency reachable relay selection |
| Relay failover | Retries CONNECT on configured servers, reloads iframe after prolonged buffering |
| Shorts | Vertical native pager of videos searched with `#shorts` (not official Shorts feed) |
| Read comments | Read-only via YouTube Data API; may be disabled per video |
| History, saved, subscriptions | Stored locally, NOT synchronized with Google |
| Quality, 60fps, 4K | Available only through YouTube's embedded player controls / video itself |
| PiP/fullscreen | Android PiP attempt and embedded player fullscreen support; needs device testing |
| Media3 | Separate test/authorized stream screen; HLS/DASH/MP4, HTTP Range, speed controls |
| Google login, writes (like/comment), synced playlists | Not implemented; do not store Google passwords |
| Background playback, persistent video mini-player | Not implemented |
| YouTube 120Hz performance | No measured guarantee; Compose uses device refresh rate |
| Fully automatic seamless mid-TLS stream switching | Impossible for this design; relay switches for new connections, iframe reload may occur |

## Network architecture

```
[Compose UI] --- HTTPS + Bearer ---> [Go API: YouTube Data API metadata]
      |                                 [Go API: signed-off media with Range]
[YouTube official iframe inside WebView]
      |                    DIRECT if HTTPS probe succeeds
      +---- WebView proxy override ----> localhost:ephemeral (Kotlin CONNECT proxy)
                                              | certificate-validated TLS
                                              | Proxy-Authorization: Bearer <relay token>
                                              v
                                       Self-owned Go HTTPS relay
                                              | CONNECT allowlisted :443 destinations
                                              v
                                         youtube.com / googlevideo.com / ytimg.com

Google HTTPS between WebView and Google remains encrypted end-to-end. The relay
sees destination hosts and traffic sizes, NOT Google TLS content or passwords.
```

**Why the iframe?** The YouTube Data API does not grant raw media URLs. YouTube
API policies prohibit separating or changing the audiovisual components of YouTube
content. Running a YouTube-like substitute application can also raise compliance
issues; review the latest YouTube developer policies before distribution. No claim
of Google endorsement. The relay MUST NOT rewrite the YouTube player or strip ads.

**Not guaranteed in Russia.** The server's IP/domain must be reachable from the
specific Russian ISP. WebView must support `WebViewFeature.PROXY_OVERRIDE`, and
Google must allow the embed on that device. `generate_204` is only a partial reachability
probe; CDN/media failures may be discovered later. This has not been tested on a
Russian phone. No public/free proxy server list is shipped.

## Requirements

- Android Studio (with JDK 17, Android SDK 35), Android 10+ phone
- A VPS located where official Google services are reachable
- A domain you own, pointing via DNS A/AAAA records to your VPS
- TCP/443 open from the phone to that VPS; a publicly trusted TLS certificate
- A YouTube Data API v3 key created in Google Cloud Console (quota applies)
- Docker / Docker Compose to deploy backend; optional `ffmpeg` for sample media

## Deploy your backend server

1. On the server, set the DNS A record of **your actual domain** to its VPS IP.
2. Obtain a certificate for that domain with a trusted CA, for example:

   ```sh
   sudo certbot certonly --standalone -d YOUR_REAL_DOMAIN
   ```

   Certbot typically requires TCP/80 for HTTP validation. You may use DNS
   validation instead. Adapt renewal to your CA provider.

3. Inside `backend`, copy `.env.example` to `.env`. Set a strong `YOUFREE_TOKEN`
   (`openssl rand -hex 32`) and your actual `YOUTUBE_API_KEY`.
4. Copy (do not symlink to an inaccessible directory) certificate and private key
   to `backend/certs/fullchain.pem` and `backend/certs/privkey.pem`.
   **Protect the private key:** the Docker image uses UID/GID 10001; ensure its
   group has read access with e.g. `chgrp 10001 certs/*.pem` and
   `chmod 0640 certs/*.pem`. Restrict access to `backend/certs` itself.
5. From `backend` run:

   ```sh
   docker compose up -d --build
   ```

6. Check service (replace host and token with your actual values):

   ```sh
   curl -fsS https://YOUR_REAL_DOMAIN/health
   curl -fsS -H 'Authorization: Bearer YOUR_REAL_TOKEN' \
      https://YOUR_REAL_DOMAIN/api/v1/status
   ```

7. Test **actual CONNECT through the relay** (if this gives HTTP 204, the
   server can reach a YouTube endpoint; this does not prove video playback):

   ```sh
   curl -v --proxy https://YOUR_REAL_DOMAIN:443 \
     --proxy-header 'Proxy-Authorization: Bearer YOUR_REAL_TOKEN' \
     'https://www.youtube.com/generate_204'
   ```

For redundancy, deploy **multiple independent** domains/servers, with matching
`YOUFREE_TOKEN`, and enter each actual address into YouFree settings. Do not use
untrusted public proxies. Update the relay HTTPS certificates before expiry.

## Run / build Android APK

- To build locally, download the trusted libXray Android AAR into
  `app/libs/libXray.aar` first, then open this repository folder in Android
  Studio and sync with Gradle.
- Install SDK platform 35 and configure Gradle/JDK 17. Build > Build APK(s).
- To build automatically from a phone, create a GitHub repository and push the
  **contents** of the `YouFree` folder. Open **Actions > Build YouFree APK >
  Run workflow**. After success open the run, **Artifacts > YouFree-Android-APK**,
  download the artifact ZIP on your phone, extract `YouFree.apk` and open it.
- Android may ask you to allow APK installation from that file manager. Enable
  permission for that source only if you trust the repository and its build.
- For the *optional legacy backend* functionality, open settings and enter
  `https://YOUR_REAL_DOMAIN` and the server token, adding spare domains on
  separate lines. This is **not** needed for the new Happ-compatible route.
- YouTube metadata depends on your API key and YouTube API quotas. Do not place
  `YOUTUBE_API_KEY` or Google user credentials inside the Android application.

CI uses **Gradle 8.11.1**, **Android Gradle Plugin 8.9.2**, **Kotlin 2.1.20**,
**compileSdk 35**; a checked-in Gradle wrapper JAR is not included because the
runtime environment cannot fetch binaries. GitHub Actions installs Gradle via
`gradle/actions/setup-gradle` and produces `YouFree.apk` on a successful build.

## Tests run in development environment

```sh
cd backend
 go test ./...
 go test -race ./...
```

Unit/integration tests check authentication, allowlisted CONNECT targets,
YouTube API metadata mapping (mock), and HTTP 206 Range responses.
`testmedia/playlist.m3u8` is self-generated H.264/AAC test content; you can
regenerate with `cd backend && ./generate-test-hls.sh`.

## Troubleshooting

- **No feed:** verify `YOUTUBE_API_KEY`, server status, token and API quota.
- **Relay unreachable:** DNS resolution/TLS cert/ISP filtering/firewall; use a
  separately deployed standby server that is reachable from the phone.
- **WebView error 153:** embedded player may require proper `Referer` and
  Google may reject some embed contexts. This is not fixed by proxy alone.
- **Videos stall:** the iframe attempts one/two automatic relay retries after a
  prolonged buffering state, otherwise use "Сменить маршрут". TLS connections
  cannot be transplanted between servers midstream.
- **Unsupported WebView PROXY_OVERRIDE:** update Android System WebView;
  if still unsupported, only direct embedded playback can be used.
- **Media3 sample gives 401:** configure matching token/server and enable media
  via `SAMPLE_MEDIA_DIR` (Docker compose default does this).
- **Background playback:** not supported for official YouTube iframe.

## Privacy and security

- No `VpnService`, root access, password capture or Google OAuth token logging.
- `YOUFREE_TOKEN` is encrypted at rest using Android Keystore AES-GCM.
- Upstream TLS certificate verification and hostname checking are enabled.
- The **previous Go relay and Kotlin CONNECT proxy** restrict CONNECT to
  allowed Google hosts:443. The **new Xray mode** routes WebView traffic through
  whichever user-supplied server was selected; it does not apply this allowlist.
- Self-hosting is important; the relay can log destination SNI/hosts, IPs and
  connection sizes, though it cannot decrypt Google's end-to-end HTTPS session.
- Both local proxy variants listen on random loopback TCP ports when active;
  other local apps may technically connect to them. The Happ/Xray mode has no
  local-proxy authentication, so this is a security consideration for release.
- For production, audit, pin dependency versions, add monitoring/quotas and
  test every major phone/WebView release; this is a starting codebase, not a
  certified/commercial streaming service.

Official documentation:
- https://developers.google.com/youtube/terms/developer-policies
- https://developers.google.com/youtube/iframe_api_reference
- https://developer.android.com/reference/androidx/webkit/ProxyController
- https://support.google.com/youtube/answer/171780
