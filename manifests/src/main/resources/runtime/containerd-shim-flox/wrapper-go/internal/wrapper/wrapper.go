package wrapper

import (
	"encoding/json"
	"fmt"
	"hash/fnv"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	defaultRealShim      = "/usr/local/libexec/rke2lab/containerd-shim-flox-v2-wrapper/containerd-shim-flox-v2.real"
	defaultSyncHelper    = "/usr/local/libexec/rke2lab/containerd-shim-flox-v2-wrapper/flox-rootfs-sync.sh"
	defaultWrapperLog    = "/var/log/rke2lab/containerd-shim-flox-v2-wrapper.log"
	defaultSyncLog       = "/var/log/rke2lab/flox-rootfs-sync.log"
	defaultDebugWaitFile = "/tmp/containerd-shim-flox-v2-wrapper-continue"
	defaultDebugListen   = "127.0.0.1:43123"
	defaultJournalSocket = "/run/systemd/journal/socket"
	defaultJournalTag    = "containerd-shim-flox-v2-wrapper"
	defaultBundleRoot    = "/run/k3s/containerd/io.containerd.runtime.v2.task"

	annotationDebug        = "flox.dev/debug"
	annotationDebugSuspend = "flox.dev/debug-suspend"
)

type Config struct {
	RealShim          string
	RootfsSyncHelper  string
	RootfsSyncEnable  bool
	WrapperLog        string
	SyncLog           string
	DebugEnabled      bool
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
	logger := newLogger(resolvedConfig)

	if stat, err := os.Stat(resolvedConfig.RealShim); err != nil || stat.Mode()&0o111 == 0 {
		return fmt.Errorf("missing real shim: %s", resolvedConfig.RealShim)
	}

	shimNamespace := extractFlagValue("-namespace", args)
	shimID := extractFlagValue("-id", args)
	subcommand := extractSubcommand(args)
	resolvedConfig = applyBundleDebugAnnotations(resolvedConfig, shimNamespace, shimID, logger)
	w.cfg = resolvedConfig

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

	if w.cfg.DebugEnabled {
		return w.execViaDelve(args, shimNamespace, shimID, logger)
	}

	return w.execRealShim(args)
}

func (w *Wrapper) execRealShim(args []string) error {
	return syscall.Exec(w.cfg.RealShim, append([]string{w.cfg.RealShim}, args...), os.Environ())
}

func (w *Wrapper) execViaDelve(args []string, shimNamespace, shimID string, logger *Logger) error {
	delvePath, err := exec.LookPath("dlv")
	if err != nil {
		logger.Log("debug requested but dlv is unavailable; executing real shim directly realShim=%s", w.cfg.RealShim)
		return w.execRealShim(args)
	}

	listenAddress := perShimDebugListenAddress(shimNamespace, shimID)
	delveArgs := []string{
		delvePath,
		"exec",
		w.cfg.RealShim,
		"--headless",
		"--api-version=2",
		"--accept-multiclient",
		"--listen=" + listenAddress,
	}
	if !w.cfg.DebugWait {
		delveArgs = append(delveArgs, "--continue")
	}
	delveArgs = append(delveArgs, "--")
	delveArgs = append(delveArgs, args...)

	logger.Log("launching shim under dlv path=%s listen=%s continue=%t", delvePath, listenAddress, !w.cfg.DebugWait)
	return syscall.Exec(delvePath, delveArgs, os.Environ())
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

	if w.cfg.DebugWait {
		logger.Log("debug suspend requested; launching dlv without --continue so target waits for debugger attach")
	}

	return nil
}

type bundleConfig struct {
	Annotations map[string]string `json:"annotations"`
}

func applyBundleDebugAnnotations(cfg Config, shimNamespace, shimID string, logger *Logger) Config {
	if strings.TrimSpace(shimNamespace) == "" || strings.TrimSpace(shimID) == "" {
		return cfg
	}

	bundleConfigPath := filepath.Join(getenvDefault("FLOX_SHIM_BUNDLE_ROOT", defaultBundleRoot), shimNamespace, shimID, "config.json")
	payload, err := os.ReadFile(bundleConfigPath)
	if err != nil {
		if !os.IsNotExist(err) {
			logger.Log("bundle annotation lookup failed path=%s err=%v", bundleConfigPath, err)
		}
		return cfg
	}

	var bundle bundleConfig
	if err := json.Unmarshal(payload, &bundle); err != nil {
		logger.Log("bundle annotation parse failed path=%s err=%v", bundleConfigPath, err)
		return cfg
	}

	debugRequested := parseBoolString(bundle.Annotations[annotationDebug])
	if !debugRequested {
		return cfg
	}

	updated := cfg
	updated.DebugEnabled = true
	updated.DebugWaitFile = perShimDebugWaitFilePath(shimNamespace, shimID)
	if parseBoolString(bundle.Annotations[annotationDebugSuspend]) {
		updated.DebugWait = true
	}

	logger.Log("enabled debug mode from bundle annotation path=%s wait=%t waitFile=%s", bundleConfigPath, updated.DebugWait, updated.DebugWaitFile)
	return updated
}

func perShimDebugWaitFilePath(shimNamespace, shimID string) string {
	sanitizedNamespace := sanitizePathToken(shimNamespace)
	sanitizedID := sanitizePathToken(shimID)
	if sanitizedNamespace == "" || sanitizedID == "" {
		return defaultDebugWaitFile
	}

	return filepath.Join(os.TempDir(), fmt.Sprintf("containerd-shim-flox-v2-wrapper-continue-%s-%s", sanitizedNamespace, sanitizedID))
}

func perShimDebugListenAddress(shimNamespace, shimID string) string {
	sanitizedNamespace := sanitizePathToken(shimNamespace)
	sanitizedID := sanitizePathToken(shimID)
	if sanitizedNamespace == "" || sanitizedID == "" {
		return defaultDebugListen
	}

	hasher := fnv.New32a()
	_, _ = hasher.Write([]byte(sanitizedNamespace))
	_, _ = hasher.Write([]byte("/"))
	_, _ = hasher.Write([]byte(sanitizedID))
	port := 40000 + int(hasher.Sum32()%20000)
	return "127.0.0.1:" + strconv.Itoa(port)
}

func sanitizePathToken(value string) string {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return ""
	}

	var builder strings.Builder
	for _, r := range trimmed {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9':
			builder.WriteRune(r)
		case r == '-', r == '_', r == '.':
			builder.WriteRune(r)
		default:
			builder.WriteRune('-')
		}
	}

	return builder.String()
}

func parseBoolString(value string) bool {
	switch strings.TrimSpace(value) {
	case "1", "true", "TRUE", "yes", "YES", "on", "ON":
		return true
	default:
		return false
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
	return parseBoolString(value)
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
