package nexuscfg

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

const DefaultPath = "/data/adb/nexus/config.json"

type Config struct {
	Schema int    `json:"schema"`
	WebUI  WebUI  `json:"webui"`
	LLM    LLM    `json:"llm"`
	STT    STT    `json:"stt"`
	TTS    TTS    `json:"tts"`
	Paths  Paths  `json:"paths"`
	Sims   []Sim  `json:"sims"`
	Notify Notify `json:"notify"`
}

// Notify is WeCom / SMS outbound configuration.
type Notify struct {
	Enabled bool         `json:"enabled"`
	Channel string       `json:"channel"` // wecom_external | wecom_webhook
	WeCom   NotifyWeCom  `json:"wecom"`
	SMS     NotifySMS    `json:"sms"`
	Call    NotifyCall   `json:"call"`
}

type NotifyWeCom struct {
	CorpID         string `json:"corp_id"`
	Secret         string `json:"secret"`
	AgentID        int    `json:"agent_id"`
	ExternalUserID string `json:"external_userid"`
	Sender         string `json:"sender"` // WeCom member userid who owns the external contact
	ToUser         string `json:"touser"` // optional: app message to member (fallback)
	WebhookURL     string `json:"webhook_url"`
}

type NotifySMS struct {
	Enabled bool `json:"enabled"`
	PollMs  int  `json:"poll_ms"`
}

type NotifyCall struct {
	Enabled            bool `json:"enabled"`
	MaxTranscriptChars int  `json:"max_transcript_chars"`
}

func DefaultNotify() Notify {
	return Notify{
		Enabled: false,
		Channel: "wecom_webhook",
		SMS:     NotifySMS{Enabled: true, PollMs: 3000},
		Call:    NotifyCall{Enabled: true, MaxTranscriptChars: 3500},
	}
}


// Policy values for per-SIM incoming call handling.
const (
	PolicyHuman  = "human"
	PolicyAI     = "ai"
	PolicyReject = "reject"
)

type Sim struct {
	Slot    int    `json:"slot"`
	Label   string `json:"label"`   // from device (carrier/display); UI read-only
	Carrier string `json:"carrier"` // from device; UI read-only
	Number  string `json:"number"`  // from device; UI read-only
	Policy  string `json:"policy"`  // human | ai | reject — only editable field
}

type WebUI struct {
	Port int `json:"port"`
}

type LLM struct {
	Enabled      bool   `json:"enabled"`
	Model        string `json:"model"`
	APIKey       string `json:"api_key"`
	BargeIn      bool   `json:"barge_in"`
	MaxMsgs      int    `json:"max_msgs"`
	SystemPrompt string `json:"system_prompt"`
}

type STT struct {
	Backend string `json:"backend"`
	Lang    string `json:"lang"`
}

type TTS struct {
	BeepPrefix bool `json:"beep_prefix"`
	Sid        int  `json:"sid"`
}

type Paths struct {
	EngineSock string `json:"engine_sock"`
	ArchiveDir string `json:"archive_dir"`
	STTModel   string `json:"stt_model"`
	TTSModel   string `json:"tts_model"`
}

func Default() Config {
	return Config{
		Schema: 1,
		WebUI:  WebUI{Port: 8787},
		LLM: LLM{
			Enabled: true,
			Model:   "deepseek-v4-flash",
			MaxMsgs: 24,
		},
		STT: STT{Backend: "engine", Lang: "auto"},
		TTS: TTS{},
		Paths: Paths{
			EngineSock: "/data/adb/nexus/run/engine.sock",
			ArchiveDir: "/data/vendor/ai_hook/calls",
			STTModel:   "/data/adb/modules/nexus_models/models/sense-voice",
			TTSModel:   "/data/adb/modules/nexus_models/models/vits-zh-ll",
		},
		Sims:   DefaultSims(),
		Notify: DefaultNotify(),
	}
}

func DefaultSims() []Sim {
	return []Sim{
		{Slot: 0, Label: "卡1", Policy: PolicyHuman},
		{Slot: 1, Label: "卡2", Policy: PolicyHuman},
	}
}

// NormalizePolicy returns a valid policy; unknown/empty → human.
func NormalizePolicy(p string) string {
	switch strings.ToLower(strings.TrimSpace(p)) {
	case PolicyAI:
		return PolicyAI
	case PolicyReject:
		return PolicyReject
	default:
		return PolicyHuman
	}
}

// PolicyForSlot returns policy for slot; missing → human.
func PolicyForSlot(cfg Config, slot int) string {
	for _, s := range cfg.Sims {
		if s.Slot == slot {
			return NormalizePolicy(s.Policy)
		}
	}
	return PolicyHuman
}

// MergeDeviceSims overlays device-discovered identity onto cfg.Sims (keeps policy).
func MergeDeviceSims(cfg *Config, device []Sim) {
	ensureSims(cfg)
	if len(device) == 0 {
		return
	}
	bySlot := map[int]Sim{}
	for _, d := range device {
		bySlot[d.Slot] = d
	}
	seen := map[int]bool{}
	for i := range cfg.Sims {
		slot := cfg.Sims[i].Slot
		d, ok := bySlot[slot]
		if !ok {
			continue
		}
		seen[slot] = true
		pol := cfg.Sims[i].Policy
		cfg.Sims[i] = d
		cfg.Sims[i].Policy = NormalizePolicy(pol)
	}
	for slot, d := range bySlot {
		if seen[slot] {
			continue
		}
		d.Policy = PolicyHuman
		cfg.Sims = append(cfg.Sims, d)
	}
	ensureSims(cfg)
}

func ensureSims(cfg *Config) {
	if len(cfg.Sims) == 0 {
		cfg.Sims = DefaultSims()
		return
	}
	for i := range cfg.Sims {
		cfg.Sims[i].Policy = NormalizePolicy(cfg.Sims[i].Policy)
		if cfg.Sims[i].Label == "" {
			cfg.Sims[i].Label = fmt.Sprintf("卡%d", cfg.Sims[i].Slot+1)
		}
	}
}

func ensureNotify(cfg *Config) {
	d := DefaultNotify()
	unconfigured := cfg.Notify.Channel == "" &&
		strings.TrimSpace(cfg.Notify.WeCom.CorpID) == "" &&
		strings.TrimSpace(cfg.Notify.WeCom.Secret) == "" &&
		strings.TrimSpace(cfg.Notify.WeCom.WebhookURL) == "" &&
		!cfg.Notify.Enabled
	if unconfigured {
		// Legacy configs without notify: keep sub-feature defaults ready.
		cfg.Notify.SMS.Enabled = d.SMS.Enabled
		cfg.Notify.Call.Enabled = d.Call.Enabled
	}
	if cfg.Notify.Channel == "" {
		cfg.Notify.Channel = d.Channel
	}
	switch strings.ToLower(strings.TrimSpace(cfg.Notify.Channel)) {
	case "wecom_external":
		cfg.Notify.Channel = "wecom_external"
	default:
		cfg.Notify.Channel = "wecom_webhook"
	}
	if cfg.Notify.SMS.PollMs <= 0 {
		cfg.Notify.SMS.PollMs = d.SMS.PollMs
	}
	if cfg.Notify.Call.MaxTranscriptChars <= 0 {
		cfg.Notify.Call.MaxTranscriptChars = d.Call.MaxTranscriptChars
	}
}

func Load(path string) (Config, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	var cfg Config
	if err := json.Unmarshal(b, &cfg); err != nil {
		return Config{}, err
	}
	if cfg.Schema == 0 {
		cfg.Schema = 1
	}
	ensureSims(&cfg)
	ensureNotify(&cfg)
	return cfg, nil
}

func LoadOrDefault(path string) (Config, error) {
	cfg, err := Load(path)
	if err != nil {
		if os.IsNotExist(err) {
			return Default(), err
		}
		return Config{}, err
	}
	return cfg, nil
}

func SaveAtomic(path string, cfg Config) error {
	if cfg.Schema == 0 {
		cfg.Schema = 1
	}
	ensureSims(&cfg)
	ensureNotify(&cfg)
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	b, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	b = append(b, '\n')
	tmp := filepath.Join(dir, fmt.Sprintf(".config.%d.tmp", os.Getpid()))
	if err := os.WriteFile(tmp, b, 0600); err != nil {
		return err
	}
	_ = os.Chmod(tmp, 0600)
	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	_ = os.Chmod(path, 0600)
	return nil
}

func Redact(cfg Config) map[string]any {
	hint := ""
	set := cfg.LLM.APIKey != ""
	if set {
		k := cfg.LLM.APIKey
		if len(k) >= 4 {
			hint = k[len(k)-4:]
		} else {
			hint = k
		}
	}
	return map[string]any{
		"schema": cfg.Schema,
		"webui": map[string]any{
			"port": cfg.WebUI.Port,
		},
		"llm": map[string]any{
			"enabled":       cfg.LLM.Enabled,
			"model":         cfg.LLM.Model,
			"api_key_set":   set,
			"api_key_hint":  hint,
			"barge_in":      cfg.LLM.BargeIn,
			"max_msgs":      cfg.LLM.MaxMsgs,
			"system_prompt": cfg.LLM.SystemPrompt,
		},
		"stt": map[string]any{
			"backend": cfg.STT.Backend,
			"lang":    cfg.STT.Lang,
		},
		"tts": map[string]any{
			"beep_prefix": cfg.TTS.BeepPrefix,
			"sid":         cfg.TTS.Sid,
		},
		"paths": map[string]any{
			"engine_sock": cfg.Paths.EngineSock,
			"archive_dir": cfg.Paths.ArchiveDir,
			"stt_model":   cfg.Paths.STTModel,
			"tts_model":   cfg.Paths.TTSModel,
		},
		"sims":   redactSims(cfg.Sims),
		"notify": redactNotify(cfg.Notify),
	}
}

func redactNotify(n Notify) map[string]any {
	cfg := Config{Notify: n}
	ensureNotify(&cfg)
	ensure := cfg.Notify
	secretSet := strings.TrimSpace(ensure.WeCom.Secret) != ""
	hint := ""
	if secretSet {
		k := ensure.WeCom.Secret
		if len(k) >= 4 {
			hint = k[len(k)-4:]
		} else {
			hint = k
		}
	}
	return map[string]any{
		"enabled": ensure.Enabled,
		"channel": ensure.Channel,
		"wecom": map[string]any{
			"corp_id_set":         strings.TrimSpace(ensure.WeCom.CorpID) != "",
			"secret_set":          secretSet,
			"secret_hint":         hint,
			"agent_id":            ensure.WeCom.AgentID,
			"external_userid_set": strings.TrimSpace(ensure.WeCom.ExternalUserID) != "",
			"sender_set":          strings.TrimSpace(ensure.WeCom.Sender) != "",
			"touser_set":          strings.TrimSpace(ensure.WeCom.ToUser) != "",
			"webhook_url_set":     strings.TrimSpace(ensure.WeCom.WebhookURL) != "",
		},
		"sms": map[string]any{
			"enabled": ensure.SMS.Enabled,
			"poll_ms": ensure.SMS.PollMs,
		},
		"call": map[string]any{
			"enabled":              ensure.Call.Enabled,
			"max_transcript_chars": ensure.Call.MaxTranscriptChars,
		},
	}
}

func redactSims(sims []Sim) []map[string]any {
	ensure := sims
	if len(ensure) == 0 {
		ensure = DefaultSims()
	}
	out := make([]map[string]any, 0, len(ensure))
	for _, s := range ensure {
		out = append(out, map[string]any{
			"slot":    s.Slot,
			"label":   s.Label,
			"carrier": s.Carrier,
			"number":  s.Number,
			"policy":  NormalizePolicy(s.Policy),
		})
	}
	return out
}

type putBody struct {
	ClearAPIKey *bool `json:"clear_api_key"`
	WebUI       *struct {
		Port *int `json:"port"`
	} `json:"webui"`
	LLM *struct {
		Enabled      *bool   `json:"enabled"`
		Model        *string `json:"model"`
		APIKey       *string `json:"api_key"`
		BargeIn      *bool   `json:"barge_in"`
		MaxMsgs      *int    `json:"max_msgs"`
		SystemPrompt *string `json:"system_prompt"`
	} `json:"llm"`
	STT *struct {
		Backend *string `json:"backend"`
		Lang    *string `json:"lang"`
	} `json:"stt"`
	TTS *struct {
		BeepPrefix *bool `json:"beep_prefix"`
		Sid        *int  `json:"sid"`
	} `json:"tts"`
	Paths *struct {
		EngineSock *string `json:"engine_sock"`
		ArchiveDir *string `json:"archive_dir"`
		STTModel   *string `json:"stt_model"`
		TTSModel   *string `json:"tts_model"`
	} `json:"paths"`
	Sims   []Sim `json:"sims"`
	Notify *struct {
		Enabled *bool `json:"enabled"`
		SMS     *struct {
			Enabled *bool `json:"enabled"`
			PollMs  *int  `json:"poll_ms"`
		} `json:"sms"`
		Call *struct {
			Enabled            *bool `json:"enabled"`
			MaxTranscriptChars *int  `json:"max_transcript_chars"`
		} `json:"call"`
	} `json:"notify"`
}

func ApplyPUT(current Config, body []byte) (Config, bool, error) {
	var p putBody
	if err := json.Unmarshal(body, &p); err != nil {
		return Config{}, false, err
	}
	next := current
	if p.WebUI != nil && p.WebUI.Port != nil {
		next.WebUI.Port = *p.WebUI.Port
	}
	if p.LLM != nil {
		if p.LLM.Enabled != nil {
			next.LLM.Enabled = *p.LLM.Enabled
		}
		if p.LLM.Model != nil {
			next.LLM.Model = *p.LLM.Model
		}
		if p.LLM.BargeIn != nil {
			next.LLM.BargeIn = *p.LLM.BargeIn
		}
		if p.LLM.MaxMsgs != nil {
			next.LLM.MaxMsgs = *p.LLM.MaxMsgs
		}
		if p.LLM.SystemPrompt != nil {
			next.LLM.SystemPrompt = *p.LLM.SystemPrompt
		}
		clear := p.ClearAPIKey != nil && *p.ClearAPIKey
		if clear {
			next.LLM.APIKey = ""
		} else if p.LLM.APIKey != nil && strings.TrimSpace(*p.LLM.APIKey) != "" {
			next.LLM.APIKey = strings.TrimSpace(*p.LLM.APIKey)
		}
	} else if p.ClearAPIKey != nil && *p.ClearAPIKey {
		next.LLM.APIKey = ""
	}
	if p.STT != nil {
		if p.STT.Backend != nil {
			next.STT.Backend = *p.STT.Backend
		}
		if p.STT.Lang != nil {
			next.STT.Lang = *p.STT.Lang
		}
	}
	if p.TTS != nil {
		if p.TTS.BeepPrefix != nil {
			next.TTS.BeepPrefix = *p.TTS.BeepPrefix
		}
		if p.TTS.Sid != nil {
			next.TTS.Sid = *p.TTS.Sid
		}
	}
	if p.Paths != nil {
		if p.Paths.EngineSock != nil {
			next.Paths.EngineSock = *p.Paths.EngineSock
		}
		if p.Paths.ArchiveDir != nil {
			next.Paths.ArchiveDir = *p.Paths.ArchiveDir
		}
		if p.Paths.STTModel != nil {
			next.Paths.STTModel = *p.Paths.STTModel
		}
		if p.Paths.TTSModel != nil {
			next.Paths.TTSModel = *p.Paths.TTSModel
		}
	}
	if p.Sims != nil {
		ensureSims(&next)
		bySlot := map[int]*Sim{}
		for i := range next.Sims {
			bySlot[next.Sims[i].Slot] = &next.Sims[i]
		}
		for _, in := range p.Sims {
			if s, ok := bySlot[in.Slot]; ok {
				s.Policy = NormalizePolicy(in.Policy)
				continue
			}
			next.Sims = append(next.Sims, Sim{
				Slot:   in.Slot,
				Label:  fmt.Sprintf("卡%d", in.Slot+1),
				Policy: NormalizePolicy(in.Policy),
			})
		}
		ensureSims(&next)
	}
	if p.Notify != nil {
		if p.Notify.Enabled != nil {
			next.Notify.Enabled = *p.Notify.Enabled
		}
		if p.Notify.SMS != nil {
			if p.Notify.SMS.Enabled != nil {
				next.Notify.SMS.Enabled = *p.Notify.SMS.Enabled
			}
			if p.Notify.SMS.PollMs != nil {
				next.Notify.SMS.PollMs = *p.Notify.SMS.PollMs
			}
		}
		if p.Notify.Call != nil {
			if p.Notify.Call.Enabled != nil {
				next.Notify.Call.Enabled = *p.Notify.Call.Enabled
			}
			if p.Notify.Call.MaxTranscriptChars != nil {
				next.Notify.Call.MaxTranscriptChars = *p.Notify.Call.MaxTranscriptChars
			}
		}
		ensureNotify(&next)
	}
	return next, NeedsEngineRestart(current, next), nil
}

func NeedsEngineRestart(before, after Config) bool {
	return before.Paths.STTModel != after.Paths.STTModel ||
		before.Paths.TTSModel != after.Paths.TTSModel ||
		before.Paths.EngineSock != after.Paths.EngineSock
}

// NeedsCallstackRestart is true when ai_call/engine must be restarted after a config save.
// Sims policy and notify settings are hot-read by their daemons — no callstack restart.
func NeedsCallstackRestart(before, after Config) bool {
	if NeedsEngineRestart(before, after) {
		return true
	}
	if before.LLM.Enabled != after.LLM.Enabled ||
		before.LLM.Model != after.LLM.Model ||
		before.LLM.APIKey != after.LLM.APIKey ||
		before.LLM.BargeIn != after.LLM.BargeIn ||
		before.LLM.MaxMsgs != after.LLM.MaxMsgs ||
		before.LLM.SystemPrompt != after.LLM.SystemPrompt {
		return true
	}
	if before.STT != after.STT || before.TTS != after.TTS {
		return true
	}
	if before.Paths.ArchiveDir != after.Paths.ArchiveDir {
		return true
	}
	return false
}
