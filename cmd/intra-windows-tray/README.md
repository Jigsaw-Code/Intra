# Intra Windows Tray Prototype

This command is a lightweight Windows tray controller for the existing
`IntraTunnel` service. It does not run the tunnel itself and does not change the
tunnel engine, route/DNS logic, or Android behavior.

Build:

```powershell
go build -o D:\intra\.go-build\intra-windows-tray.exe ./cmd/intra-windows-tray
```

Run after installing the service backend:

```powershell
D:\intra\.go-build\intra-windows-tray.exe
```

Tray menu:

- Status
- Start Intra
- Stop Intra
- Open diagnostics
- Exit tray

The diagnostics item opens `%ProgramData%\Intra`, where the service journal is
stored while the tunnel is active.
