package main

import (
	"os"
	"strings"

	"nexus.nexuscfg"
)

type nexusFileOpts struct {
	APIKey, Model, System, Lang, Archive string
	BargeIn, LLMEnabled, Beep            bool
	MaxMsgs, Sid                         int
	EngineSock, STTModel, TTSModel       string
}

func loadNexusFileOpts(path string) (nexusFileOpts, bool) {
	cfg, err := nexuscfg.Load(path)
	if err != nil {
		return nexusFileOpts{}, false
	}
	return nexusFileOpts{
		APIKey:      strings.TrimSpace(cfg.LLM.APIKey),
		Model:       cfg.LLM.Model,
		System:      cfg.LLM.SystemPrompt,
		Lang:        cfg.STT.Lang,
		Archive:     cfg.Paths.ArchiveDir,
		BargeIn:     cfg.LLM.BargeIn,
		LLMEnabled:  cfg.LLM.Enabled,
		Beep:        cfg.TTS.BeepPrefix,
		MaxMsgs:     cfg.LLM.MaxMsgs,
		Sid:         cfg.TTS.Sid,
		EngineSock:  cfg.Paths.EngineSock,
		STTModel:    cfg.Paths.STTModel,
		TTSModel:    cfg.Paths.TTSModel,
	}, true
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

func envSet(key string) bool {
	_, ok := os.LookupEnv(key)
	return ok
}
