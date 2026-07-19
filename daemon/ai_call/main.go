package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"nexus.ai_call/engine"
	"nexus.ai_call/llm"
	"nexus.ai_call/stt"
	"nexus.ai_call/tts"
)

const (
	defaultSock       = "/data/vendor/ai_hook/pcm.sock"
	defaultSTTLog     = "/data/vendor/ai_hook/stt.log"
	defaultSTTBin     = "/data/local/tmp/nexus_stt/sherpa-onnx-offline"
	defaultModelDir   = "/data/local/tmp/nexus_stt/sense-voice"
	defaultTmpDir     = "/data/local/tmp/nexus_stt/tmp"
	defaultTTSBin     = "/data/local/tmp/nexus_stt/sherpa-onnx-offline-tts"
	defaultTTSModel   = "/data/local/tmp/nexus_stt/vits-zh-ll"
	defaultTXInjectP  = "/data/vendor/ai_hook/tx_inject.pcm"
	defaultTXRate     = 48000
	defaultEngineBin  = "/data/local/tmp/nexus_stt/nexus_engine"
	defaultEngineSock = "/data/local/tmp/nexus_stt/engine.sock"
	defaultLLMKeyFile = "/data/local/tmp/nexus_stt/deepseek.key"
)

func envOr(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	v := strings.TrimSpace(os.Getenv(key))
	if v == "" {
		return def
	}
	switch strings.ToLower(v) {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return def
	}
}

type ttsOut struct {
	Bin, Model, TX string
	Rate, Sid      int
	Client         *engine.Client // non-nil → resident nexus_engine
}

type llmOut struct {
	Enabled bool
	Client  *llm.Client
	System  string
}

type speakOpts struct {
	Beep      bool // diagnostic tone before speech
	WaitDrain bool // wait HAL pickup + playback (needed between stream sentences)
}

func main() {
	sock := flag.String("sock", envOr("PCM_SOCK", defaultSock), "HAL UDS path")
	dump := flag.String("dump", "", "optional raw DL PCM dump path")
	sttLog := flag.String("stt-log", envOr("STT_LOG", defaultSTTLog), "transcript log path")
	backendName := flag.String("backend", envOr("STT_BACKEND", "mock"), "stt backend: mock|sherpa|engine")
	sttBin := flag.String("stt-bin", envOr("STT_BIN", defaultSTTBin), "sherpa-onnx-offline path")
	modelDir := flag.String("model-dir", envOr("STT_MODEL_DIR", defaultModelDir), "SenseVoice model dir")
	lang := flag.String("lang", envOr("STT_LANG", "auto"), "sense-voice language: auto|zh|en|ja|ko|yue")
	queueSize := flag.Int("queue", 2, "async STT queue size")
	say := flag.String("say", "", "TTS text → tx_inject.pcm then exit (no UDS)")
	ttsBin := flag.String("tts-bin", envOr("TTS_BIN", defaultTTSBin), "sherpa-onnx-offline-tts path")
	ttsModel := flag.String("tts-model", envOr("TTS_MODEL_DIR", defaultTTSModel), "VITS model dir")
	txPath := flag.String("tx", envOr("TX_INJECT", defaultTXInjectP), "HAL tx_inject.pcm path")
	txRate := flag.Int("tx-rate", defaultTXRate, "TX PCM sample rate (Hz)")
	ttsSid := flag.Int("tts-sid", 0, "VITS speaker id")
	echoTTS := flag.Bool("echo-tts", envBool("ECHO_TTS", false), "after STT OK, TTS same text → TX")
	engineBin := flag.String("engine-bin", envOr("ENGINE_BIN", defaultEngineBin), "nexus_engine path")
	engineSock := flag.String("engine-sock", envOr("ENGINE_SOCK", defaultEngineSock), "nexus_engine UDS")
	useLLM := flag.Bool("llm", envBool("LLM", false), "STT → DeepSeek stream → sentence TTS → TX")
	llmKey := flag.String("llm-key", envOr("DEEPSEEK_API_KEY", ""), "DeepSeek API key (or use key file)")
	llmKeyFile := flag.String("llm-key-file", envOr("DEEPSEEK_KEY_FILE", defaultLLMKeyFile), "API key file path")
	llmBase := flag.String("llm-base", envOr("DEEPSEEK_BASE", llm.DefaultBaseURL), "DeepSeek API base URL")
	llmModel := flag.String("llm-model", envOr("DEEPSEEK_MODEL", llm.DefaultModel), "chat model id")
	llmSystem := flag.String("llm-system", envOr("LLM_SYSTEM", llm.DefaultSystemPrompt), "system prompt")
	llmPing := flag.Bool("llm-ping", false, "one-shot DeepSeek ping then exit")
	flag.Parse()

	ttsCfg := ttsOut{
		Bin: *ttsBin, Model: *ttsModel, TX: *txPath, Rate: *txRate, Sid: *ttsSid,
	}

	if *llmPing {
		key, err := llm.LoadAPIKey(*llmKey, *llmKeyFile)
		if err != nil {
			log.Fatalf("llm-ping: %v", err)
		}
		c := &llm.Client{
			BaseURL: *llmBase,
			APIKey:  key,
			Model:   *llmModel,
			HTTP:    llm.NewHTTPClient(nil),
		}
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		full, err := c.ChatStream(ctx, []llm.Message{
			{Role: "user", Content: "用三个字打招呼"},
		}, nil)
		if err != nil {
			log.Fatalf("llm-ping: %v", err)
		}
		log.Printf("llm-ping ok model=%s reply=%q", *llmModel, full)
		return
	}

	useEngine := strings.EqualFold(strings.TrimSpace(*backendName), "engine")
	if strings.TrimSpace(*say) != "" && useEngine {
		sup, client, err := startEngine(*engineBin, *engineSock, *modelDir, *ttsModel, *lang)
		if err != nil {
			log.Fatalf("engine: %v", err)
		}
		defer sup.Stop()
		ttsCfg.Client = client
		if err := speakTX(context.Background(), *say, ttsCfg, speakOpts{Beep: envBool("TX_BEEP_PREFIX", false)}); err != nil {
			log.Fatalf("say: %v", err)
		}
		return
	}
	if strings.TrimSpace(*say) != "" {
		if err := speakTX(context.Background(), *say, ttsCfg, speakOpts{Beep: envBool("TX_BEEP_PREFIX", false)}); err != nil {
			log.Fatalf("say: %v", err)
		}
		return
	}

	var engSup *engine.Supervisor
	var engClient *engine.Client
	if useEngine {
		var err error
		engSup, engClient, err = startEngine(*engineBin, *engineSock, *modelDir, *ttsModel, *lang)
		if err != nil {
			log.Fatalf("engine: %v", err)
		}
		defer engSup.Stop()
		ttsCfg.Client = engClient
	}

	var llmCfg llmOut
	if *useLLM {
		key, err := llm.LoadAPIKey(*llmKey, *llmKeyFile)
		if err != nil {
			log.Fatalf("llm: %v", err)
		}
		llmCfg = llmOut{
			Enabled: true,
			Client: &llm.Client{
				BaseURL: *llmBase,
				APIKey:  key,
				Model:   *llmModel,
				HTTP:    llm.NewHTTPClient(nil),
			},
			System: *llmSystem,
		}
		if *echoTTS {
			log.Printf("note: -llm overrides -echo-tts")
			*echoTTS = false
		}
	}

	backend, err := newBackend(*backendName, *sttBin, *modelDir, *lang, engClient)
	if err != nil {
		log.Fatalf("backend: %v", err)
	}
	log.Printf("ai_call start backend=%s sock=%s stt_log=%s echo_tts=%v llm=%v engine=%v",
		backend.Name(), *sock, *sttLog, *echoTTS, llmCfg.Enabled, useEngine)

	logFile, err := os.OpenFile(*sttLog, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0666)
	if err != nil {
		log.Printf("stt-log open failed (%v), using stdout only", err)
		logFile = nil
	} else {
		defer logFile.Close()
	}

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	uttCh := make(chan Utterance, *queueSize)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		sttWorker(ctx, backend, uttCh, logFile, *echoTTS, ttsCfg, llmCfg)
	}()

	for {
		if ctx.Err() != nil {
			break
		}
		conn, err := dialUDS(*sock, 2*time.Second)
		if err != nil {
			log.Printf("dial: %v (retry)", err)
			select {
			case <-ctx.Done():
			case <-time.After(500 * time.Millisecond):
			}
			continue
		}
		log.Printf("connected to HAL")
		runStream(ctx, conn, *dump, uttCh)
		_ = conn.Close()
		log.Printf("disconnected, will reconnect")
		select {
		case <-ctx.Done():
		case <-time.After(200 * time.Millisecond):
		}
	}

	close(uttCh)
	wg.Wait()
	log.Printf("ai_call exit")
}

func startEngine(bin, sock, sttModel, ttsModel, lang string) (*engine.Supervisor, *engine.Client, error) {
	sup := &engine.Supervisor{
		Bin:      bin,
		Sock:     sock,
		STTModel: sttModel,
		TTSModel: ttsModel,
		LibDir:   filepath.Dir(bin),
		Lang:     lang,
		Threads:  2,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	if err := sup.Start(ctx); err != nil {
		return nil, nil, err
	}
	return sup, &engine.Client{Sock: sock}, nil
}

func newBackend(name, bin, modelDir, lang string, eng *engine.Client) (stt.Backend, error) {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "mock", "":
		return stt.Mock{}, nil
	case "sherpa":
		return stt.Sherpa{
			Bin:      bin,
			ModelDir: modelDir,
			Language: lang,
			Threads:  2,
			TmpDir:   defaultTmpDir,
			UseITN:   true,
		}, nil
	case "engine":
		if eng == nil {
			return nil, fmt.Errorf("engine backend requires nexus_engine client")
		}
		return stt.Engine{Client: eng, TmpDir: defaultTmpDir}, nil
	default:
		return nil, fmt.Errorf("unknown backend %q (mock|sherpa|engine)", name)
	}
}

func speakTX(parent context.Context, text string, cfg ttsOut, opts speakOpts) error {
	ctx, cancel := context.WithTimeout(parent, 2*time.Minute)
	defer cancel()
	var (
		pcm  []byte
		rate int
		err  error
		name string
	)
	if cfg.Client != nil {
		eng := tts.Engine{Client: cfg.Client, Sid: cfg.Sid, TmpDir: defaultTmpDir}
		pcm, rate, err = eng.SynthesizeEx(ctx, text)
		name = eng.Name()
	} else {
		cli := tts.Sherpa{
			Bin: cfg.Bin, ModelDir: cfg.Model, Sid: cfg.Sid,
			Speed: 1.0, Threads: 2, TmpDir: defaultTmpDir,
		}
		pcm, rate, err = cli.SynthesizeEx(ctx, text)
		name = cli.Name()
	}
	if err != nil {
		return err
	}
	txRate := cfg.Rate
	if txRate <= 0 {
		txRate = defaultTXRate
	}
	if rate != txRate {
		pcm = tts.ResampleS16Mono(pcm, rate, txRate)
	}
	gainS16Mono(pcm, 4.0)
	if opts.Beep {
		beep := tonePrefixS16Mono(txRate, 300)
		pcm = append(beep, pcm...)
	}
	if err := writeTXInject(cfg.TX, pcm); err != nil {
		return err
	}
	log.Printf("say ok backend=%s in_rate=%d out_rate=%d bytes=%d path=%s text=%q",
		name, rate, txRate, len(pcm), cfg.TX, text)
	if opts.WaitDrain {
		waitTXPlayed(ctx, cfg.TX, len(pcm), txRate)
	}
	return nil
}

// waitTXPlayed waits until HAL picks up the inject file, then for playback duration.
// Needed between streamed sentences: HAL replaces the queue on each new file load.
func waitTXPlayed(ctx context.Context, path string, pcmBytes, rate int) {
	if path == "" {
		path = defaultTXInject
	}
	deadline := time.Now().Add(8 * time.Second)
	for time.Now().Before(deadline) {
		if ctx.Err() != nil {
			return
		}
		if _, err := os.Stat(path); os.IsNotExist(err) {
			break
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(20 * time.Millisecond):
		}
	}
	if rate <= 0 {
		rate = defaultTXRate
	}
	samples := pcmBytes / 2
	dur := time.Duration(samples) * time.Second / time.Duration(rate)
	dur += 80 * time.Millisecond
	select {
	case <-ctx.Done():
	case <-time.After(dur):
	}
}

func sttWorker(ctx context.Context, backend stt.Backend, in <-chan Utterance, logFile *os.File, echo bool, ttsCfg ttsOut, llmCfg llmOut) {
	for utt := range in {
		t0 := time.Now()
		cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
		text, err := backend.Transcribe(cctx, utt.PCM16k)
		cancel()
		ts := time.Now().Format("15:04:05")
		line := ""
		writeSTT := true
		okSpeech := false
		if err != nil {
			line = fmt.Sprintf("%s ERR backend=%s peak=%.0f dur_ms=%d err=%v\n",
				ts, backend.Name(), utt.PeakRMS, len(utt.PCM16k)*1000/32000, err)
		} else if !hasSpeechText(text) {
			line = fmt.Sprintf("%s DROP backend=%s peak=%.0f dur_ms=%d rt=%.2fs text=%q\n",
				ts, backend.Name(), utt.PeakRMS, len(utt.PCM16k)*1000/32000, time.Since(t0).Seconds(), text)
			writeSTT = false
		} else {
			okSpeech = true
			line = fmt.Sprintf("%s OK backend=%s peak=%.0f dur_ms=%d rt=%.2fs text=%s\n",
				ts, backend.Name(), utt.PeakRMS, len(utt.PCM16k)*1000/32000, time.Since(t0).Seconds(), text)
		}
		log.Print(strings.TrimSuffix(line, "\n"))
		if writeSTT && logFile != nil {
			_, _ = logFile.WriteString(line)
			_ = logFile.Sync()
		}
		if !okSpeech {
			continue
		}
		if llmCfg.Enabled {
			if err := replyLLM(ctx, text, llmCfg, ttsCfg); err != nil {
				log.Printf("llm: %v", err)
			}
			continue
		}
		if echo {
			opts := speakOpts{Beep: envBool("TX_BEEP_PREFIX", false)}
			if err := speakTX(ctx, text, ttsCfg, opts); err != nil {
				log.Printf("echo-tts: %v", err)
			}
		}
	}
}

func replyLLM(ctx context.Context, userText string, llmCfg llmOut, ttsCfg ttsOut) error {
	t0 := time.Now()
	msgs := []llm.Message{
		{Role: "system", Content: llmCfg.System},
		{Role: "user", Content: userText},
	}
	type streamRes struct {
		full string
		err  error
	}
	sentCh := make(chan string, 8)
	resCh := make(chan streamRes, 1)
	go func() {
		full, err := llmCfg.Client.ChatStream(ctx, msgs, func(sentence string) {
			select {
			case sentCh <- sentence:
			case <-ctx.Done():
			}
		})
		close(sentCh)
		resCh <- streamRes{full: full, err: err}
	}()

	first := true
	beepOn := envBool("TX_BEEP_PREFIX", false)
	for sentence := range sentCh {
		opts := speakOpts{
			Beep:      first && beepOn,
			WaitDrain: true,
		}
		first = false
		if err := speakTX(ctx, sentence, ttsCfg, opts); err != nil {
			log.Printf("llm-tts: %v", err)
		}
	}
	res := <-resCh
	if res.err != nil {
		return res.err
	}
	log.Printf("llm ok rt=%.2fs reply=%q", time.Since(t0).Seconds(), res.full)
	return nil
}

func runStream(ctx context.Context, conn net.Conn, dumpPath string, uttCh chan<- Utterance) {
	hdr, err := readAPCMHeader(conn)
	if err != nil {
		log.Printf("hdr: %v", err)
		return
	}
	log.Printf("stream start rate=%d ch=%d bits=%d kind=%s(%d)",
		hdr.Rate, hdr.Channels, hdr.Bits, kindName(hdr.Kind), hdr.Kind)
	clearTXInject(defaultTXInject) // drop stale clip from previous offline -say

	var dump *os.File
	if dumpPath != "" {
		dump, err = os.Create(dumpPath)
		if err != nil {
			log.Printf("dump create: %v", err)
		} else {
			defer dump.Close()
		}
	}

	vad := NewEnergyVAD(DefaultVADConfig())
	buf := make([]byte, 8192)
	var total int64
	t0 := time.Now()
	lastLog := t0

	enqueue := func(utts []Utterance) {
		for _, u := range utts {
			select {
			case uttCh <- u:
			default:
				log.Printf("stt queue full, drop utt dur_ms=%d", len(u.PCM16k)*1000/32000)
			}
		}
	}

	for {
		if ctx.Err() != nil {
			break
		}
		_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := conn.Read(buf)
		if n > 0 {
			total += int64(n)
			if dump != nil {
				_, _ = dump.Write(buf[:n])
			}
			mono := stereoS16ToMono16k(buf[:n], int(hdr.Channels), int(hdr.Rate))
			if len(mono) > 0 {
				enqueue(vad.Push(mono))
			}
			if time.Since(lastLog) >= time.Second {
				log.Printf("recv total=%d (%.1fs)", total, time.Since(t0).Seconds())
				lastLog = time.Now()
			}
		}
		if err != nil {
			if ne, ok := err.(interface{ Timeout() bool }); ok && ne.Timeout() {
				continue
			}
			log.Printf("stream end total=%d err=%v", total, err)
			break
		}
	}
	enqueue(vad.Flush())
}
