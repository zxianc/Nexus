package nexuscfg

import (
	"os"
	"path/filepath"
	"strings"
)

// Migrate fills empty API key from secrets/deepseek.key under nexusDir.
func Migrate(nexusDir string, cfg Config) (Config, bool, error) {
	if strings.TrimSpace(cfg.LLM.APIKey) != "" {
		return cfg, false, nil
	}
	keyPath := filepath.Join(nexusDir, "secrets", "deepseek.key")
	b, err := os.ReadFile(keyPath)
	if err != nil {
		if os.IsNotExist(err) {
			return cfg, false, nil
		}
		return cfg, false, err
	}
	key := strings.TrimSpace(string(b))
	if key == "" {
		return cfg, false, nil
	}
	cfg.LLM.APIKey = key
	return cfg, true, nil
}
