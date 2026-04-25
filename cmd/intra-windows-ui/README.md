# Intra Windows UI Shell

This is the Phase 1 Windows desktop UI shell. It controls the existing
`IntraTunnel` service and does not change the tunnel engine, route/DNS logic,
tray controller, or Android behavior.

Build:

```powershell
go build -o D:\intra\.go-build\intra-windows-ui.exe ./cmd/intra-windows-ui
```

Run:

```powershell
D:\intra\.go-build\intra-windows-ui.exe
```

The UI expects the Windows service to already be installed. The first shell
shows protection status, service state, journal state, Wintun DLL state, and log
path. It can start/stop `IntraTunnel` and open `%ProgramData%\Intra`.
