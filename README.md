# Signal Lite Chat

A minimal Kotlin Android chat app and Node.js WebSocket server for text-only messaging over mobile data.

## Included
- Android app using Kotlin and OkHttp WebSocket
- Locally queued outgoing messages while offline
- Automatic reconnect with exponential backoff (2 to 60 seconds)
- Server-side SQLite storage for messages until recipient delivery
- Delivery acknowledgement
- Text-only protocol with a 2,000-character limit

## Important limitations
This is a learning/demo starter, not a production-secure messenger. Usernames are not authenticated, so someone could impersonate another username. The server does not encrypt message contents end-to-end. Use only with trusted test users until proper authentication and end-to-end encryption are implemented. For deployment, use `wss://` behind a TLS reverse proxy and disable public plain `ws://` access.

## Android Studio
1. Open Android Studio.
2. Choose **Open** and select this `SignalLiteChat` folder.
3. Use JDK 17 if prompted, then allow Gradle sync.
4. The app uses `compileSdk = 34`; install Android SDK Platform 34 and Build-Tools through SDK Manager if required.
5. Run on an emulator or Android phone.

### Server URL
- Android emulator connecting to a server running on the same computer: `ws://10.0.2.2:8080`
- Physical phone on the same Wi-Fi as your computer: use your computer's LAN IP, e.g. `ws://192.168.1.10:8080`
- Phone using mobile data from anywhere: deploy the server on a public host and use `wss://your-domain.example/ws` with TLS configured. The sample server itself listens on `/` and plain HTTP/WebSocket; a TLS reverse proxy must forward WebSocket connections to port 8080. Update the URL to match the proxy's WebSocket path.

The server must be reachable over the internet for people in different locations. `localhost` on a phone means the phone itself, not your computer.

## Start the server
Install Node.js 20 or newer, then run:

```bash
cd server
npm install
npm start
```

The database is stored in `server/messages.db` by default. Set `PORT` or `DB_PATH` environment variables to change these.

## Test with two clients
1. Start the server on your computer.
2. Connect client 1 with username `alice` and client 2 with username `bob`.
3. For an emulator, use `ws://10.0.2.2:8080`. For a physical phone on the same Wi-Fi, use your computer's LAN IP.
4. On Alice's phone, set recipient to `bob`; on Bob's phone, set recipient to `alice`.
5. Send text messages in either direction.

## Build an APK
1. In Android Studio, select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
2. Wait for the build to finish.
3. Select **locate** in the notification to find the APK. Usually it is at `app/build/outputs/apk/debug/app-debug.apk`.

## Low-data and weak-signal notes
- Text only; no media auto-downloads.
- WebSocket ping interval is 20 seconds.
- Reconnect delay grows from 2 seconds up to 60 seconds to avoid wasting data/battery.
- Messages are queued locally until the server accepts them.
- Server persists messages until the recipient acknowledges delivery.

This sample deliberately keeps the app simple. For a public release, add account authentication, abuse/rate limits, TLS, privacy controls, and end-to-end encryption.
