package main

// 1.D' receiver: UDS client to HAL (DL-only STT path).
//
//   go build -o pcm_recv .
//   adb push pcm_recv /data/local/tmp/
//   adb shell 'su -c "/data/local/tmp/pcm_recv -dump /data/vendor/ai_hook/uds_dl.pcm"'

import (
	"encoding/binary"
	"flag"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"
)

const defaultSock = "/data/vendor/ai_hook/pcm.sock"

func kindName(k uint16) string {
	switch k {
	case 0:
		return "mixed"
	case 1:
		return "DL"
	case 2:
		return "UL"
	default:
		return "unknown"
	}
}

func main() {
	sock := flag.String("sock", defaultSock, "unix stream socket path (HAL listens)")
	dump := flag.String("dump", "", "optional raw PCM dump path")
	flag.Parse()

	go func() {
		ch := make(chan os.Signal, 1)
		signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
		<-ch
		os.Exit(0)
	}()

	log.Printf("pcm_recv client mode, dialing %s", *sock)
	for {
		conn, err := net.DialTimeout("unix", *sock, 2*time.Second)
		if err != nil {
			log.Printf("dial: %v (retry)", err)
			time.Sleep(500 * time.Millisecond)
			continue
		}
		log.Printf("connected to HAL")
		handle(conn, *dump)
		log.Printf("disconnected, will reconnect")
		time.Sleep(200 * time.Millisecond)
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
	kind := binary.LittleEndian.Uint16(hdr[12:14])
	if magic != 0x4D435041 { // 'APCM'
		log.Printf("bad magic 0x%x", magic)
		return
	}
	log.Printf("stream start rate=%d ch=%d bits=%d kind=%s(%d)", rate, ch, bits, kindName(kind), kind)

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
