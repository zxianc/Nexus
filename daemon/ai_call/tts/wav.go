package tts

import (
	"encoding/binary"
	"fmt"
	"os"
)

func readWavPCM(path string) (pcm []byte, rate int, channels int, err error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, 0, 0, err
	}
	if len(b) < 44 || string(b[0:4]) != "RIFF" || string(b[8:12]) != "WAVE" {
		return nil, 0, 0, fmt.Errorf("tts: not a wav: %s", path)
	}
	off := 12
	var dataOff, dataLen int
	for off+8 <= len(b) {
		chunk := string(b[off : off+4])
		sz := int(binary.LittleEndian.Uint32(b[off+4 : off+8]))
		off += 8
		if chunk == "fmt " {
			if sz < 16 || off+16 > len(b) {
				return nil, 0, 0, fmt.Errorf("tts: bad fmt chunk")
			}
			audioFmt := binary.LittleEndian.Uint16(b[off : off+2])
			channels = int(binary.LittleEndian.Uint16(b[off+2 : off+4]))
			rate = int(binary.LittleEndian.Uint32(b[off+4 : off+8]))
			bits := binary.LittleEndian.Uint16(b[off+14 : off+16])
			if audioFmt != 1 || bits != 16 {
				return nil, 0, 0, fmt.Errorf("tts: want PCM s16, got fmt=%d bits=%d", audioFmt, bits)
			}
		} else if chunk == "data" {
			dataOff = off
			dataLen = sz
			break
		}
		off += sz
		if sz&1 == 1 {
			off++
		}
	}
	if dataOff == 0 || dataOff+dataLen > len(b) {
		return nil, 0, 0, fmt.Errorf("tts: missing data chunk")
	}
	pcm = make([]byte, dataLen)
	copy(pcm, b[dataOff:dataOff+dataLen])
	return pcm, rate, channels, nil
}

// ResampleS16MonoNearest upsamples/downsamples mono s16le by integer ratio when possible.
func ResampleS16Mono(pcm []byte, fromRate, toRate int) []byte {
	if fromRate == toRate || fromRate <= 0 || toRate <= 0 || len(pcm) < 2 {
		return pcm
	}
	inFrames := len(pcm) / 2
	outFrames := inFrames * toRate / fromRate
	if outFrames <= 0 {
		return nil
	}
	out := make([]byte, outFrames*2)
	for i := 0; i < outFrames; i++ {
		src := i * fromRate / toRate
		if src >= inFrames {
			src = inFrames - 1
		}
		out[i*2] = pcm[src*2]
		out[i*2+1] = pcm[src*2+1]
	}
	return out
}
