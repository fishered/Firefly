# Firefly Admin UI

Independent Node-based Admin UI for the Firefly scheduler center.

The UI runs as its own local Node service and proxies `/api/*` to the Java Admin HTTP API. Java keeps JSON endpoints in `apis/admin-http`; page markup, styles, and browser logic stay in `ui/admin`.

## Start

Start Firefly server first:

```powershell
cd /d E:\workSpace\firefly
.\gradlew.bat :server:launcher:run --args="--firefly.config=config/firefly-server.properties"
```

Then start the UI:

```powershell
cd /d E:\workSpace\firefly\ui\admin
npm start
```

If PowerShell blocks `npm.ps1`, use:

```powershell
npm.cmd start
```

Open:

```text
http://127.0.0.1:9720
```

## Configuration

Defaults:

- `FIREFLY_ADMIN_UI_HOST=127.0.0.1`
- `FIREFLY_ADMIN_UI_PORT=9720`
- `FIREFLY_ADMIN_API=http://127.0.0.1:9710`
- `FIREFLY_ADMIN_API_TIMEOUT_MS=5000`

Override example:

```powershell
$env:FIREFLY_ADMIN_UI_PORT="9720"
$env:FIREFLY_ADMIN_API="http://127.0.0.1:9710"
npm start
```

## Local Check

```powershell
npm run check
```

PowerShell fallback:

```powershell
npm.cmd run check
```

## API Boundary

The UI calls these proxied endpoints:

- `/api/health`
- `/api/overview`
- `/api/jobs`
- `/api/executors`
- `/api/nodes`

The Node service also exposes `/ui/config` so the browser can display the active proxy target.
