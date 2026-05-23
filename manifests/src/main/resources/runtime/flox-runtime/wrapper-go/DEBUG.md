# Debugging the Flox NRI Plugin

The NRI plugin runs as a child process of containerd and can be difficult to debug. This guide covers debugging strategies.

## Log File

The plugin logs to `/var/log/flox-nri-plugin.log` on the node.

Check logs:
```bash
incus exec master -- tail -f /var/log/flox-nri-plugin.log
```

## Startup Debug Mode

To pause plugin execution at startup and attach a debugger:

### 1. Enable Debug Mode

Create systemd override to set environment variables:

```bash
incus exec master -- mkdir -p /etc/systemd/system/rke2-server.service.d/

incus exec master -- tee /etc/systemd/system/rke2-server.service.d/nri-debug.conf <<'EOF'
[Service]
Environment="FLOX_NRI_DEBUG_SUSPEND=true"
Environment="FLOX_NRI_DEBUG_PORT=2345"
EOF

incus exec master -- systemctl daemon-reload
incus exec master -- systemctl restart rke2-server
```

### 2. Find Plugin PID

```bash
incus exec master -- pgrep -f "10-flox"
```

### 3. Attach Debugger

```bash
incus exec master -- dlv attach <pid>
```

In dlv:
- Plugin will be paused at `runtime.Breakpoint()`
- Type `continue` to proceed past the breakpoint
- Use `break`, `step`, `next` to debug
- Type `help` for full command list

### 4. Disable Debug Mode

```bash
incus exec master -- rm /etc/systemd/system/rke2-server.service.d/nri-debug.conf
incus exec master -- systemctl daemon-reload
incus exec master -- systemctl restart rke2-server
```

## Per-Pod Debug Mode

To debug specific pod container creation:

Add annotations to the pod:
```yaml
metadata:
  annotations:
    flox.dev/debug: "true"
    flox.dev/debug-port: "2345"  # optional, default 2345
```

Note: This logs debug messages but doesn't pause execution. The startup debug mode is needed for actual debugging.

## Common Issues

### Plugin is Defunct/Zombie

Check `/var/log/flox-nri-plugin.log` for crash reason. Common causes:
- Missing dependencies
- Permission issues
- NRI protocol version mismatch
- Panic during initialization

### No Logs Generated

- Verify plugin binary exists: `/opt/nri/plugins/10-flox`
- Check containerd NRI config: `containerd config dump | grep -A 10 nri`
- Verify NRI is enabled: `disable = false`
- Check plugin process: `pgrep -af 10-flox`

### Container Creation Not Triggering Plugin

- Verify pod has `flox.dev/environment` annotation
- Check plugin subscribed to events: look for "configured" in logs
- Ensure plugin isn't crashed (not defunct)
