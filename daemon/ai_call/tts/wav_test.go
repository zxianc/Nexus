package tts

import (
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"
)

func TestResampleS16Mono3x(t *testing.T) {
	// 2 samples @16k → 6 @48k
	in := make([]byte, 4)
	binary.LittleEndian.PutUint16(in[0:], 1000)
	binary.LittleEndian.PutUint16(in[2:], 2000)
	out := ResampleS16Mono(in, 16000, 48000)
	if len(out) != 12 {
		t.Fatalf("len=%d want 12", len(out))
	}
}

func TestReadWavPCM(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "t.wav")
	// minimal 1-sample mono 16k wav
	pcm := []byte{0x00, 0x10}
	hdr := make([]byte, 44)
	copy(hdr[0:4], "RIFF")
	binary.LittleEndian.PutUint32(hdr[4:], uint32(36+len(pcm)))
	copy(hdr[8:12], "WAVE")
	copy(hdr[12:16], "fmt ")
	binary.LittleEndian.PutUint32(hdr[16:], 16)
	binary.LittleEndian.PutUint16(hdr[20:], 1)
	binary.LittleEndian.PutUint16(hdr[22:], 1)
	binary.LittleEndian.PutUint32(hdr[24:], 16000)
	binary.LittleEndian.PutUint32(hdr[28:], 16000*2)
	binary.LittleEndian.PutUint16(hdr[32:], 2)
	binary.LittleEndian.PutUint16(hdr[34:], 16)
	copy(hdr[36:40], "data")
	binary.LittleEndian.PutUint32(hdr[40:], uint32(len(pcm)))
	if err := os.WriteFile(path, append(hdr, pcm...), 0644); err != nil {
		t.Fatal(err)
	}
	got, rate, ch, err := readWavPCM(path)
	if err != nil {
		t.Fatal(err)
	}
	if rate != 16000 || ch != 1 || len(got) != 2 {
		t.Fatalf("rate=%d ch=%d len=%d", rate, ch, len(got))
	}
}
