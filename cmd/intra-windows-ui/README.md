# Intra Windows UI Shell

This is the Windows desktop UI shell. It controls the existing `IntraTunnel`
service and does not change the tunnel engine, route/DNS logic, or Android
behavior.

Build:

```powershell
$env:GOCACHE="D:\intra\.go-cache"
go run ./tools/windowsresource -ico .\cmd\intra-windows-ui\assets\intra.ico -out .\cmd\intra-windows-ui\intra_windows_ui_icon_windows_amd64.syso
$env:GOOS="windows"; $env:GOARCH="amd64"; go build -tags "desktop,production" -o D:\intra\.go-build\intra-windows-ui.exe ./cmd/intra-windows-ui
```

Run:

```powershell
D:\intra\.go-build\intra-windows-ui.exe
```

The UI expects the Windows service to already be installed. It follows the
Android navigation structure where practical: Home, Settings, Support, Privacy
Policy, Terms of Service, and Source code. Home shows the protection dashboard,
system details, and Windows diagnostics.

Settings implements the Android DNS-over-HTTPS server chooser, including the
built-in providers from Android resources and custom `https://` DoH URL
validation. Saving the DNS server writes `%ProgramData%\Intra\windows-settings.json`.
If `IntraTunnel` is running, the UI restarts the service so the new server is
used. Query counters are stored in `%ProgramData%\Intra\windows-query-history.json`.
Excluded apps is not shown as a configurable Windows setting. The drawer notes
that per-app exclusion on Windows requires a WFP redirect/callout driver and is
not available in this build.

## Limitations

- IPv4 full-tunnel only; IPv6 routing is not implemented yet.
- Per-app exclusion is not implemented on Windows. A WFP feasibility spike
  confirmed that user-mode WFP can identify and block apps by executable path,
  but user-mode WFP cannot provide Android-like bypass around the Wintun
  full-tunnel route.
- Real Excluded apps support likely requires a signed WFP redirect/callout
  driver.
- The UI does not yet port Android's query history graph.
- Recent query details do not yet include DNS response destination parsing or
  geolocation flags.

Closing the UI hides it so the tray can reopen it later. Minimize keeps the UI
as a normal taskbar window because minimize-to-tray restore is unreliable in the
current Wails shell.

Logo assets are derived from the existing Android launcher icon:

```text
Android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
```

The PNG is embedded in the Wails UI header. A Windows `.ico` generated from the
same source is embedded by the tray app and linked into the Wails UI executable
through `cmd/intra-windows-ui/intra_windows_ui_icon_windows_amd64.syso`.

Package:

```powershell
New-Item -ItemType Directory -Force D:\intra\dist\Intra-Windows
Copy-Item D:\intra\.go-build\intra-windows-service.exe D:\intra\dist\Intra-Windows\
Copy-Item D:\intra\.go-build\intra-windows-tray.exe D:\intra\dist\Intra-Windows\
Copy-Item D:\intra\.go-build\intra-windows-ui.exe D:\intra\dist\Intra-Windows\
Copy-Item D:\intra\.go-build\wintun.dll D:\intra\dist\Intra-Windows\
```
