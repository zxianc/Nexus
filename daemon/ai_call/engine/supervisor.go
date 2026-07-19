package engine

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// Supervisor starts and owns a nexus_engine child process.
type Supervisor struct {
	Bin        string
	Sock       string
	STTModel   string
	TTSModel   string
	LibDir     string
	Lang       string
	Threads    int
	cmd        *exec.Cmd
	startedByUs bool
}

func (s *Supervisor) Start(ctx context.Context) error {
	if s.Bin == "" || s.Sock == "" {
		return fmt.Errorf("engine: empty Bin/Sock")
	}
	if s.Threads <= 0 {
		s.Threads = 2
	}
	if s.Lang == "" {
		s.Lang = "auto"
	}

	// Reuse already-running engine if ping works.
	tctx, cancel := context.WithTimeout(ctx, 800*time.Millisecond)
	err := (&Client{Sock: s.Sock}).Ping(tctx)
	cancel()
	if err == nil {
		log.Printf("engine: reuse existing sock=%s", s.Sock)
		return nil
	}

	_ = os.Remove(s.Sock)
	libDir := s.LibDir
	if libDir == "" {
		libDir = filepath.Dir(s.Bin)
	}
	args := []string{
		fmt.Sprintf("--sock=%s", s.Sock),
		fmt.Sprintf("--stt-model-dir=%s", s.STTModel),
		fmt.Sprintf("--tts-model-dir=%s", s.TTSModel),
		fmt.Sprintf("--lang=%s", s.Lang),
		fmt.Sprintf("--threads=%d", s.Threads),
	}
	cmd := exec.Command(s.Bin, args...)
	cmd.Dir = libDir
	cmd.Env = append(os.Environ(), "LD_LIBRARY_PATH="+libDir)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("engine start: %w", err)
	}
	s.cmd = cmd
	s.startedByUs = true
	log.Printf("engine: started pid=%d bin=%s", cmd.Process.Pid, s.Bin)

	wctx, wcancel := context.WithTimeout(ctx, 120*time.Second)
	defer wcancel()
	if err := WaitReady(wctx, s.Sock); err != nil {
		s.Stop()
		return err
	}
	log.Printf("engine: ready sock=%s", s.Sock)
	return nil
}

func (s *Supervisor) Stop() {
	if !s.startedByUs || s.cmd == nil || s.cmd.Process == nil {
		return
	}
	_ = s.cmd.Process.Kill()
	_, _ = s.cmd.Process.Wait()
	_ = os.Remove(s.Sock)
	log.Printf("engine: stopped")
	s.cmd = nil
	s.startedByUs = false
}
