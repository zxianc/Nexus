package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"nexus.ai_call/stt"
)

const (
	defaultSock    = "/data/vendor/ai_hook/pcm.sock"
	defaultSTTLog  = "/data/vendor/ai_hook/stt.log"
	defaultSTTBin  = "/data/local/tmp/nexus_stt/sherpa-onnx-offline"
	defaultModelDir = "/data/local/tmp/nexus_stt/sense-voice"
	defaultTmpDir  = "/data/local/tmp/nexus_stt/tmp"
)

func envOr(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func main() {
	sock := flag.String("sock", envOr("PCM_SOCK", defaultSock), "HAL UDS path")
	dump := flag.String("dump", "", "optional raw DL PCM dump path")
	sttLog := flag.String("stt-log", envOr("STT_LOG", defaultSTTLog), "transcript log path")
	backendName := flag.String("backend", envOr("STT_BACKEND", "mock"), "stt backend: mock|sherpa")
	sttBin := flag.String("stt-bin", envOr("STT_BIN", defaultSTTBin), "sherpa-onnx-offline path")
	modelDir := flag.String("model-dir", envOr("STT_MODEL_DIR", defaultModelDir), "SenseVoice model dir")
	lang := flag.String("lang", envOr("STT_LANG", "zh"), "sense-voice language")
	queueSize := flag.Int("queue", 2, "async STT queue size")
	flag.Parse()

	backend, err := newBackend(*backendName, *sttBin, *modelDir, *lang)
	if err != nil {
		log.Fatalf("backend: %v", err)
	}
	log.Printf("ai_call start backend=%s sock=%s stt_log=%s", backend.Name(), *sock, *sttLog)

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
		sttWorker(ctx, backend, uttCh, logFile)
	}()

	go func() {
		<-ctx.Done()
		// allow worker to drain briefly after cancel
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

func newBackend(name, bin, modelDir, lang string) (stt.Backend, error) {
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
	default:
		return nil, fmt.Errorf("unknown backend %q (mock|sherpa)", name)
	}
}

func sttWorker(ctx context.Context, backend stt.Backend, in <-chan Utterance, logFile *os.File) {
	for utt := range in {
		t0 := time.Now()
		cctx, cancel := context.WithTimeout(ctx, 60*time.Second)
		text, err := backend.Transcribe(cctx, utt.PCM16k)
		cancel()
		ts := time.Now().Format("15:04:05")
		line := ""
		writeSTT := true
		if err != nil {
			line = fmt.Sprintf("%s ERR backend=%s peak=%.0f dur_ms=%d err=%v\n",
				ts, backend.Name(), utt.PeakRMS, len(utt.PCM16k)*1000/32000, err)
		} else if !hasSpeechText(text) {
			line = fmt.Sprintf("%s DROP backend=%s peak=%.0f dur_ms=%d rt=%.2fs text=%q\n",
				ts, backend.Name(), utt.PeakRMS, len(utt.PCM16k)*1000/32000, time.Since(t0).Seconds(), text)
			writeSTT = false
		} else {
			line = fmt.Sprintf("%s OK backend=%s peak=%.0f dur_ms=%d rt=%.2fs text=%s\n",
				ts, backend.Name(), utt.PeakRMS, len(utt.PCM16k)*1000/32000, time.Since(t0).Seconds(), text)
		}
		log.Print(strings.TrimSuffix(line, "\n"))
		if writeSTT && logFile != nil {
			_, _ = logFile.WriteString(line)
			_ = logFile.Sync()
		}
	}
}

func runStream(ctx context.Context, conn net.Conn, dumpPath string, uttCh chan<- Utterance) {
	hdr, err := readAPCMHeader(conn)
	if err != nil {
		log.Printf("hdr: %v", err)
		return
	}
	log.Printf("stream start rate=%d ch=%d bits=%d kind=%s(%d)",
		hdr.Rate, hdr.Channels, hdr.Bits, kindName(hdr.Kind), hdr.Kind)

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
