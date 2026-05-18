package wrapper

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"gopkg.in/yaml.v3"
)

const (
	configVersion     = 1
	defaultConfigPath = "/etc/rke2lab/flox-shim-wrapper.yaml"
)

type storedConfig struct {
	Version    int              `yaml:"version"`
	Paths      storedPaths      `yaml:"paths"`
	Debug      storedDebug      `yaml:"debug"`
	Logging    storedLogging    `yaml:"logging"`
	RootfsSync storedRootfsSync `yaml:"rootfsSync"`
}

type storedPaths struct {
	RealShim string `yaml:"realShim"`
}

type storedDebug struct {
	Wait     bool   `yaml:"wait"`
	WaitFile string `yaml:"waitFile"`
	Sleep    string `yaml:"sleep"`
}

type storedLogging struct {
	Path           string `yaml:"path"`
	SyncPath       string `yaml:"syncPath"`
	JournaldSocket string `yaml:"journaldSocket"`
	Identifier     string `yaml:"identifier"`
}

type storedRootfsSync struct {
	Enabled bool   `yaml:"enabled"`
	Helper  string `yaml:"helper"`
}

type configDocument struct {
	Version    int                      `yaml:"version"`
	Paths      configDocumentPaths      `yaml:"paths"`
	Debug      configDocumentDebug      `yaml:"debug"`
	Logging    configDocumentLogging    `yaml:"logging"`
	RootfsSync configDocumentRootfsSync `yaml:"rootfsSync"`
}

type configDocumentPaths struct {
	RealShim *string `yaml:"realShim,omitempty"`
}

type configDocumentDebug struct {
	Wait     *bool   `yaml:"wait,omitempty"`
	WaitFile *string `yaml:"waitFile,omitempty"`
	Sleep    *string `yaml:"sleep,omitempty"`
}

type configDocumentLogging struct {
	Path           *string `yaml:"path,omitempty"`
	SyncPath       *string `yaml:"syncPath,omitempty"`
	JournaldSocket *string `yaml:"journaldSocket,omitempty"`
	Identifier     *string `yaml:"identifier,omitempty"`
}

type configDocumentRootfsSync struct {
	Enabled *bool   `yaml:"enabled,omitempty"`
	Helper  *string `yaml:"helper,omitempty"`
}

func defaultStoredConfig() storedConfig {
	return storedConfig{
		Version: configVersion,
		Paths: storedPaths{
			RealShim: defaultRealShim,
		},
		Debug: storedDebug{
			Wait:     false,
			WaitFile: defaultDebugWaitFile,
			Sleep:    "0s",
		},
		Logging: storedLogging{
			Path:           defaultWrapperLog,
			SyncPath:       defaultSyncLog,
			JournaldSocket: defaultJournalSocket,
			Identifier:     defaultJournalTag,
		},
		RootfsSync: storedRootfsSync{
			Enabled: true,
			Helper:  defaultSyncHelper,
		},
	}
}

func resolveRuntimeConfig() (Config, error) {
	configPath := getenvDefault("FLOX_SHIM_CONFIG_PATH", defaultConfigPath)
	if err := ensureConfigFile(configPath, defaultStoredConfig()); err != nil {
		return Config{}, fmt.Errorf("ensure wrapper config file %s: %w", configPath, err)
	}

	payload, err := os.ReadFile(configPath)
	if err != nil {
		return Config{}, fmt.Errorf("read wrapper config file %s: %w", configPath, err)
	}

	var document configDocument
	if err := yaml.Unmarshal(payload, &document); err != nil {
		return Config{}, fmt.Errorf("parse wrapper config file %s: %w", configPath, err)
	}

	if document.Version == 0 {
		return Config{}, fmt.Errorf("wrapper config file %s is missing required version field", configPath)
	}
	if document.Version != configVersion {
		return Config{}, fmt.Errorf("wrapper config file %s has unsupported version %d", configPath, document.Version)
	}

	stored := mergeConfigDocument(defaultStoredConfig(), document)
	config, err := runtimeConfigFromStored(stored, configPath)
	if err != nil {
		return Config{}, err
	}

	return applyEnvOverrides(config), nil
}

func ensureConfigFile(path string, cfg storedConfig) error {
	if _, err := os.Stat(path); err == nil {
		return nil
	} else if !os.IsNotExist(err) {
		return err
	}

	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}

	payload, err := yaml.Marshal(cfg)
	if err != nil {
		return err
	}

	tempFile, err := os.CreateTemp(filepath.Dir(path), ".flox-shim-wrapper-*.yaml")
	if err != nil {
		return err
	}
	tempName := tempFile.Name()
	defer os.Remove(tempName)

	if _, err := tempFile.Write(payload); err != nil {
		_ = tempFile.Close()
		return err
	}
	if err := tempFile.Close(); err != nil {
		return err
	}
	if err := os.Chmod(tempName, 0o644); err != nil {
		return err
	}

	if err := os.Link(tempName, path); err != nil {
		if os.IsExist(err) {
			return nil
		}
		return err
	}

	return nil
}

func mergeConfigDocument(base storedConfig, document configDocument) storedConfig {
	if document.Paths.RealShim != nil {
		base.Paths.RealShim = *document.Paths.RealShim
	}
	if document.Debug.Wait != nil {
		base.Debug.Wait = *document.Debug.Wait
	}
	if document.Debug.WaitFile != nil {
		base.Debug.WaitFile = *document.Debug.WaitFile
	}
	if document.Debug.Sleep != nil {
		base.Debug.Sleep = *document.Debug.Sleep
	}
	if document.Logging.Path != nil {
		base.Logging.Path = *document.Logging.Path
	}
	if document.Logging.SyncPath != nil {
		base.Logging.SyncPath = *document.Logging.SyncPath
	}
	if document.Logging.JournaldSocket != nil {
		base.Logging.JournaldSocket = *document.Logging.JournaldSocket
	}
	if document.Logging.Identifier != nil {
		base.Logging.Identifier = *document.Logging.Identifier
	}
	if document.RootfsSync.Enabled != nil {
		base.RootfsSync.Enabled = *document.RootfsSync.Enabled
	}
	if document.RootfsSync.Helper != nil {
		base.RootfsSync.Helper = *document.RootfsSync.Helper
	}
	return base
}

func runtimeConfigFromStored(stored storedConfig, configPath string) (Config, error) {
	debugSleep, err := time.ParseDuration(stored.Debug.Sleep)
	if err != nil {
		return Config{}, fmt.Errorf("parse debug.sleep from %s: %w", configPath, err)
	}

	return Config{
		RealShim:          stored.Paths.RealShim,
		RootfsSyncHelper:  stored.RootfsSync.Helper,
		RootfsSyncEnable:  stored.RootfsSync.Enabled,
		WrapperLog:        stored.Logging.Path,
		SyncLog:           stored.Logging.SyncPath,
		DebugWait:         stored.Debug.Wait,
		DebugWaitFile:     stored.Debug.WaitFile,
		DebugSleep:        debugSleep,
		JournalSocket:     stored.Logging.JournaldSocket,
		JournalIdentifier: stored.Logging.Identifier,
	}, nil
}

func applyEnvOverrides(cfg Config) Config {
	cfg.RealShim = getenvDefault("FLOX_SHIM_REAL", cfg.RealShim)
	cfg.RootfsSyncHelper = getenvDefault("FLOX_ROOTFS_SYNC_HELPER", cfg.RootfsSyncHelper)
	cfg.RootfsSyncEnable = getenvBoolDefault("FLOX_SHIM_ROOTFS_SYNC", cfg.RootfsSyncEnable)
	cfg.WrapperLog = getenvDefault("FLOX_SHIM_WRAPPER_LOG", cfg.WrapperLog)
	cfg.SyncLog = getenvDefault("FLOX_SHIM_SYNC_LOG", cfg.SyncLog)
	cfg.JournalSocket = getenvDefault("FLOX_SHIM_JOURNAL_SOCKET", cfg.JournalSocket)
	cfg.JournalIdentifier = getenvDefault("FLOX_SHIM_JOURNAL_IDENTIFIER", cfg.JournalIdentifier)
	return cfg
}
