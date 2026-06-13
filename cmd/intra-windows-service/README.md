# Intra Windows Service Prototype

This command hosts and controls the Windows full-tunnel backend. It creates a
Wintun adapter, routes IPv4 traffic into it, sets the adapter DNS server to
Intra's fake DNS address, and connects the adapter to the existing Go Intra
tunnel.

Build from the repo:

```powershell
go build ./cmd/intra-windows-service
```

Copy the amd64 `wintun.dll` beside the built exe. For local development, an
existing WireGuard-compatible install may already provide one, or use the
official Wintun release from https://www.wintun.net/.

Development foreground mode, from an elevated shell:

```powershell
.\intra-windows-service.exe run-debug
```

Service control commands, from an elevated shell:

```powershell
.\intra-windows-service.exe install
.\intra-windows-service.exe start
.\intra-windows-service.exe status
.\intra-windows-service.exe stop
.\intra-windows-service.exe uninstall
```

The service runs as LocalSystem. On start it writes a journal under
`%ProgramData%\Intra\windows-tunnel-journal.json` before modifying routes or
DNS. If the previous run crashed while marked active, the next service/debug
start restores the journaled route/DNS state before applying a new tunnel
configuration. Clean stop removes the journal after route/DNS restore succeeds.

The install command also grants authenticated interactive users enough service
rights for the tray app to query, start, and stop `IntraTunnel` without asking
for full service-control access. Reinstall the service after rebuilding the exe
to pick up permission or service-host changes.

Service lifecycle logs are written to `%ProgramData%\Intra\windows-service.log`.

## Limitations

The backend currently targets IPv4 full-tunnel behavior only. IPv6 routing,
native IP Helper API route/DNS management, a named-pipe control API, and a tray
UI are intentionally left for later hardening phases.

Per-app exclusion is not implemented on Windows. A WFP feasibility spike
confirmed that user-mode WFP can identify and block apps by executable path, but
user-mode WFP cannot provide Android-like bypass around the Wintun full-tunnel
route. Real Excluded apps support likely requires a signed WFP redirect/callout
driver.
