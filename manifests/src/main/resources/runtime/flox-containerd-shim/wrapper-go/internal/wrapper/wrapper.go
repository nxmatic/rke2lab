package wrapper

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

const (
	defaultRealShim      = "/usr/local/libexec/rke2lab/flox-shim-wrapper/containerd-shim-flox-v2.real"
	defaultSyncHelper    = "/usr/local/libexec/rke2lab/flox-shim-wrapper/flox-rootfs-sync.sh"
	defaultWrapperLog    = "/var/log/rke2lab/flox-shim-wrapper.log"
	defaultSyncLog       = "/var/log/rke2lab/flox-rootfs-sync.log"
	defaultDebugWaitFile = "/tmp/flox-shim-wrapper-continue"
	defaultJournalSocket = "/run/systemd/journal/socket"
	defaultJournalTag    = "flox-shim-wrapper"
)

type Config struct {
	RealShim          string
	RootfsSyncHelper  string
	RootfsSyncEnable  bool
	WrapperLog        string
	SyncLog           string
	DebugWait         bool
	DebugWaitFile     string
	DebugSleep        time.Duration
	JournalSocket     string
	JournalIdentifier string
}

type Wrapper struct {
	cfg Config
}

func New() *Wrapper {
	return &Wrapper{
		cfg: Config{},
	}
}

func (w *Wrapper) Run(args []string) error {
	resolvedConfig, err := resolveRuntimeConfig()
	if err != nil {
		return err
	}
	w.cfg = resolvedConfig

	logger := newLogger(w.cfg)

	if stat, err := os.Stat(w.cfg.RealShim); err != nil || stat.Mode()&0o111 == 0 {
		return fmt.Errorf("missing real shim: %s", w.cfg.RealShim)
	}

	shimNamespace := extractFlagValue("-namespace", args)
	shimID := extractFlagValue("-id", args)
	subcommand := extractSubcommand(args)

	logger.Log("argv=%s", strings.Join(args, " "))
	logger.Log("resolved namespace=%s id=%s subcommand=%s", emptyDefault(shimNamespace, "<unset>"), emptyDefault(shimID, "<unset>"), emptyDefault(subcommand, "<unset>"))

	if subcommand == "start" && shimNamespace != "" && shimID != "" {
		if err := w.launchRootfsSync(logger, shimNamespace, shimID); err != nil {
			logger.Log("rootfs sync launch failed: %v", err)
		}
	}

	if err := w.maybeWaitForDebugger(logger); err != nil {
		return err
	}

	return syscall.Exec(w.cfg.RealShim, append([]string{w.cfg.RealShim}, args...), os.Environ())
}

func (w *Wrapper) launchRootfsSync(logger *Logger, shimNamespace, shimID string) error {
	if !w.cfg.RootfsSyncEnable {
		return nil
	}

	stat, err := os.Stat(w.cfg.RootfsSyncHelper)
	if err != nil || stat.Mode()&0o111 == 0 {
		logger.Log("rootfs sync helper missing or not executable: %s", w.cfg.RootfsSyncHelper)
		return nil
	}

	cmd := exec.Command(w.cfg.RootfsSyncHelper)
	cmd.Env = append(os.Environ(),
		"FLOX_SHIM_SYNC_NAMESPACE="+shimNamespace,
		"FLOX_SHIM_SYNC_ID="+shimID,
		"FLOX_SHIM_SYNC_LOG="+w.cfg.SyncLog,
	)

	logFile, err := openAppendFile(w.cfg.WrapperLog)
	if err == nil {
		defer logFile.Close()
		cmd.Stdout = logFile
		cmd.Stderr = logFile
	}

	if err := cmd.Start(); err != nil {
		return err
	}

	logger.Log("launched rootfs sync helper pid=%d namespace=%s id=%s", cmd.Process.Pid, shimNamespace, shimID)
	return cmd.Process.Release()
}

func (w *Wrapper) maybeWaitForDebugger(logger *Logger) error {
	if w.cfg.DebugSleep > 0 {
		logger.Log("sleeping for debug attachment duration=%s", w.cfg.DebugSleep)
		time.Sleep(w.cfg.DebugSleep)
	}

	if !w.cfg.DebugWait {
		return nil
	}

	logger.Log("waiting for debug gate file=%s", w.cfg.DebugWaitFile)
	for {
		if _, err := os.Stat(w.cfg.DebugWaitFile); err == nil {
			logger.Log("debug gate opened file=%s", w.cfg.DebugWaitFile)
			return nil
		}
		time.Sleep(250 * time.Millisecond)
	}
}

func getenvDefault(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func getenvBoolDefault(key string, fallback bool) bool {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	return value == "1" || strings.EqualFold(value, "true") || strings.EqualFold(value, "yes") || strings.EqualFold(value, "on")
}

func getenvDurationDefault(key string, fallback time.Duration) time.Duration {
	value := strings.TrimSpace(os.Getenv(key))
	if value == "" {
		return fallback
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fallback
	}
	return parsed
}

func extractFlagValue(flagName string, args []string) string {
	for i := 0; i < len(args)-1; i++ {
		if args[i] == flagName {
			return args[i+1]
		}
	}
	return ""
}

func extractSubcommand(args []string) string {
	if len(args) == 0 {
		return ""
	}
	return args[len(args)-1]
}

func emptyDefault(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

type Logger struct {
	path          string
	journalSocket string
	identifier    string
}

func newLogger(cfg Config) *Logger {
	return &Logger{
		path:          ensureLogPath(cfg.WrapperLog),
		journalSocket: cfg.JournalSocket,
		identifier:    cfg.JournalIdentifier,
	}
}

func ensureLogPath(path string) string {
	if file, err := openAppendFile(path); err == nil {
		_ = file.Close()
		return path
	}

	fallback := filepath.Join(os.TempDir(), filepath.Base(path))
	if file, err := openAppendFile(fallback); err == nil {
		_ = file.Close()
		return fallback
	}
	return os.DevNull
}

func openAppendFile(path string) (*os.File, error) {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	return os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
}

func (l *Logger) Log(format string, args ...any) {
	if l == nil {
		return
	}

	message := fmt.Sprintf(format, args...)
	if l.sendToJournald(message) {
		return
	}

	if l.path == os.DevNull {
		return
	}

	file, err := openAppendFile(l.path)
	if err != nil {
		return
	}
	defer file.Close()

	_, _ = fmt.Fprintf(file, "[%s] %s\n", time.Now().UTC().Format(time.RFC3339), message)
}

func (l *Logger) sendToJournald(message string) bool {
	if l == nil || strings.TrimSpace(l.journalSocket) == "" {
		return false
	}

	conn, err := net.Dial("unixgram", l.journalSocket)
	if err != nil {
		return false
	}
	defer conn.Close()

	payload := strings.Join([]string{
		"MESSAGE=" + sanitizeJournalValue(message),
		"PRIORITY=6",
		"SYSLOG_IDENTIFIER=" + sanitizeJournalValue(l.identifier),
	}, "\n")

	if _, err := conn.Write([]byte(payload)); err != nil {
		return false
	}

	return true
}

func sanitizeJournalValue(value string) string {
	value = strings.ReplaceAll(value, "\x00", "")
	value = strings.ReplaceAll(value, "\n", `\n`)
	return value
}
