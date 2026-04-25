# Intra Windows Service Prototype

This command is the first Windows full-tunnel backend milestone. It creates a
Wintun adapter, routes IPv4 traffic into it, sets the adapter DNS server to
Intra's fake DNS address, and connects the adapter to the existing Go Intra
tunnel.

Run from an elevated shell:

```powershell
go build ./cmd/intra-windows-service
.\intra-windows-service.exe
```

The process keeps the tunnel active until Ctrl+C.

The backend currently targets IPv4 full-tunnel behavior only. IPv6 routing,
native IP Helper API route/DNS management, a named-pipe control API, and a tray
UI are intentionally left for later hardening phases.
