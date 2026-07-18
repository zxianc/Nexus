package main

// Minimal 1.D receiver: listen on UDS, count PCM bytes (optional dump).
//
//   go build -o pcm_recv .
//   adb push pcm_recv /data/local/tmp/
//   adb shell 'su -c "mkdir -p /data/vendor/ai_hook; chmod 777 /data/vendor/ai_hook; /data/local/tmp/pcm_recv -dump /data/vendor/ai_hook/uds_dump.pcm"'

import (
	"encoding/binary"
	"flag"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"
)

const defaultSock = "/data/vendor/ai_hook/pcm.sock"

func main() {
	sock := flag.String("sock", defaultSock, "unix stream socket path")
	dump := flag.String("dump", "", "optional raw PCM dump path")
	flag.Parse()

	_ = os.Remove(*sock)
	if err := os.MkdirAll(filepath.Dir(*sock), 0777); err != nil {
		log.Printf("mkdir: %v", err)
	}

	ln, err := net.Listen("unix", *sock)
	if err != nil {
		log.Fatalf("listen %s: %v", *sock, err)
	}
	_ = os.Chmod(*sock, 0666)
	log.Printf("listening on %s", *sock)

	go func() {
		ch := make(chan os.Signal, 1)
		signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
		<-ch
		_ = ln.Close()
		_ = os.Remove(*sock)
		os.Exit(0)
	}()

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		go handle(conn, *dump)
	}
}

func handle(conn net.Conn, dumpPath string) {
	defer conn.Close()

	var hdr [16]byte
	if _, err := io.ReadFull(conn, hdr[:]); err != nil {
		log.Printf("hdr: %v", err)
		return
	}
	magic := binary.LittleEndian.Uint32(hdr[0:4])
	rate := binary.LittleEndian.Uint32(hdr[4:8])
	ch := binary.LittleEndian.Uint16(hdr[8:10])
	bits := binary.LittleEndian.Uint16(hdr[10:12])
	if magic != 0x4D435041 { // 'APCM'
		log.Printf("bad magic 0x%x", magic)
		return
	}
	log.Printf("stream start rate=%d ch=%d bits=%d", rate, ch, bits)

	var out *os.File
	if dumpPath != "" {
		var err error
		out, err = os.Create(dumpPath)
		if err != nil {
			log.Printf("dump create: %v", err)
		} else {
			defer out.Close()
		}
	}

	buf := make([]byte, 8192)
	var total int64
	t0 := time.Now()
	lastLog := t0
	for {
		n, err := conn.Read(buf)
		if n > 0 {
			total += int64(n)
			if out != nil {
				_, _ = out.Write(buf[:n])
			}
			if time.Since(lastLog) >= time.Second {
				log.Printf("recv total=%d (%.1fs)", total, time.Since(t0).Seconds())
				lastLog = time.Now()
			}
		}
		if err != nil {
			log.Printf("stream end total=%d err=%v", total, err)
			return
		}
	}
}
