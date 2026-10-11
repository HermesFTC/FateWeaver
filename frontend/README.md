# FateWeaver dashboard frontend

This is the Vue 3 and TypeScript app behind the web dashboard at `192.168.43.1:8080/fate`. It lists the logs on the Robot Controller and lets you download them.

Gradle builds it for you. The `buildFrontend` task in the `ftc` module runs `bun install` and `bun run build`, and writes the result to `ftc/src/main/assets/web/fate`. That folder is git-ignored, so don't edit it. Install [Bun](https://bun.sh/) before you build.

To work on the app on its own, run this in `frontend/`:

```
bun run dev
```

The dev server proxies `/fate/logs` to a Control Hub at `192.168.43.1:8080`. Connect to the robot's WiFi first, or change the target in `vite.config.ts`.
