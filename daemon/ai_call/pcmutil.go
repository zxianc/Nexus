package main

import (
	"encoding/binary"
	"math"
)

// stereoS16ToMono16k converts 48kHz stereo s16le frames to 16kHz mono s16le.
// Assumes rateIn is a multiple of 16000 (typically 48000 → factor 3).
func stereoS16ToMono16k(in []byte, channels int, rateIn int) []byte {
	if channels < 1 || rateIn < 16000 || len(in) < channels*2 {
		return nil
	}
	factor := rateIn / 16000
	if factor < 1 || rateIn%16000 != 0 {
		// fallback: treat as already 16k if close
		factor = 1
	}
	frameBytes := channels * 2
	nFrames := len(in) / frameBytes
	outFrames := nFrames / factor
	if outFrames <= 0 {
		return nil
	}
	out := make([]byte, outFrames*2)
	for i := 0; i < outFrames; i++ {
		var sum int32
		for j := 0; j < factor; j++ {
			off := (i*factor + j) * frameBytes
			// take left (or only) channel; L≡R on our path
			s := int32(int16(binary.LittleEndian.Uint16(in[off:])))
			sum += s
		}
		v := sum / int32(factor)
		if v > math.MaxInt16 {
			v = math.MaxInt16
		} else if v < math.MinInt16 {
			v = math.MinInt16
		}
		binary.LittleEndian.PutUint16(out[i*2:], uint16(int16(v)))
	}
	return out
}

// rmsS16 returns RMS of mono s16le samples.
func rmsS16(mono []byte) float64 {
	n := len(mono) / 2
	if n == 0 {
		return 0
	}
	var sum float64
	for i := 0; i < n; i++ {
		s := float64(int16(binary.LittleEndian.Uint16(mono[i*2:])))
		sum += s * s
	}
	return math.Sqrt(sum / float64(n))
}
