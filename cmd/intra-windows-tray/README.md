# Intra Windows Tray Prototype

This command is a lightweight Windows tray controller for the existing
`IntraTunnel` service. It does not run the tunnel itself and does not change the
tunnel engine, route/DNS logic, or Android behavior.

Build:

```powershell
go build -ldflags "-H=windowsgui" -o D:\intra\.go-build\intra-windows-tray.exe ./cmd/intra-windows-tray
```

Run after installing the service backend:

```powershell
D:\intra\.go-build\intra-windows-tray.exe
```

Tray menu:

- Open Intra
- Turn on
- Turn off
- Status
- Open diagnostics
- Exit tray

`Open Intra` launches or restores the Wails UI. The diagnostics item opens
`%ProgramData%\Intra`, where the service journal, logs, settings, and query
history are stored.
