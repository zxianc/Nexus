package main

// VADConfig tunes energy-based utterance segmentation (16 kHz mono).
type VADConfig struct {
	FrameMs      int     // analysis frame, default 20
	SpeechRMS    float64 // enter speech if RMS >= this
	SilenceRMS   float64 // hangover counts silence if RMS < this
	MinSpeechMs  int     // drop utterances shorter than this
	SilenceEndMs int     // silence after speech ends utterance
	MaxSpeechMs  int     // force cut long speech
	PreRollMs    int     // keep this much before speech start
}

func DefaultVADConfig() VADConfig {
	return VADConfig{
		FrameMs:      20,
		SpeechRMS:    400,
		SilenceRMS:   250,
		MinSpeechMs:  500,
		SilenceEndMs: 500,
		MaxSpeechMs:  8000,
		PreRollMs:    200,
	}
}

// Utterance is one VAD-segmented mono 16 kHz PCM chunk.
type Utterance struct {
	PCM16k  []byte
	PeakRMS float64
}

// EnergyVAD segments a continuous 16 kHz mono stream.
type EnergyVAD struct {
	cfg          VADConfig
	frameBytes   int
	preRollBytes int
	pending      []byte // incomplete frame bytes
	preRoll      []byte // ring of recent silence before speech
	utt          []byte // current utterance
	inSpeech     bool
	silenceMs    int
	speechMs     int
	peak         float64
}

func NewEnergyVAD(cfg VADConfig) *EnergyVAD {
	if cfg.FrameMs <= 0 {
		cfg.FrameMs = 20
	}
	fb := 16000 * cfg.FrameMs / 1000 * 2
	pr := 16000 * cfg.PreRollMs / 1000 * 2
	return &EnergyVAD{
		cfg:          cfg,
		frameBytes:   fb,
		preRollBytes: pr,
	}
}

func (v *EnergyVAD) resetUtt() {
	v.utt = v.utt[:0]
	v.inSpeech = false
	v.silenceMs = 0
	v.speechMs = 0
	v.peak = 0
}

// Push appends 16 kHz mono PCM and returns completed utterances (may be empty).
func (v *EnergyVAD) Push(mono16k []byte) []Utterance {
	v.pending = append(v.pending, mono16k...)
	var out []Utterance
	for len(v.pending) >= v.frameBytes {
		frame := append([]byte(nil), v.pending[:v.frameBytes]...)
		v.pending = v.pending[v.frameBytes:]
		if u := v.feedFrame(frame); u != nil {
			out = append(out, *u)
		}
	}
	return out
}

// Flush ends any in-progress utterance (call hangup / stream end).
func (v *EnergyVAD) Flush() []Utterance {
	var out []Utterance
	if v.inSpeech && v.speechMs >= v.cfg.MinSpeechMs && len(v.utt) > 0 {
		out = append(out, Utterance{PCM16k: append([]byte(nil), v.utt...), PeakRMS: v.peak})
	}
	v.pending = v.pending[:0]
	v.preRoll = v.preRoll[:0]
	v.resetUtt()
	return out
}

func (v *EnergyVAD) feedFrame(frame []byte) *Utterance {
	r := rmsS16(frame)
	if !v.inSpeech {
		// keep pre-roll
		v.preRoll = append(v.preRoll, frame...)
		if v.preRollBytes > 0 && len(v.preRoll) > v.preRollBytes {
			v.preRoll = v.preRoll[len(v.preRoll)-v.preRollBytes:]
		}
		if r >= v.cfg.SpeechRMS {
			v.inSpeech = true
			v.utt = append(v.utt[:0], v.preRoll...)
			v.utt = append(v.utt, frame...)
			v.speechMs = v.cfg.FrameMs + v.cfg.PreRollMs
			v.silenceMs = 0
			v.peak = r
			v.preRoll = v.preRoll[:0]
		}
		return nil
	}

	v.utt = append(v.utt, frame...)
	v.speechMs += v.cfg.FrameMs
	if r > v.peak {
		v.peak = r
	}
	if r < v.cfg.SilenceRMS {
		v.silenceMs += v.cfg.FrameMs
	} else {
		v.silenceMs = 0
	}

	end := v.silenceMs >= v.cfg.SilenceEndMs || v.speechMs >= v.cfg.MaxSpeechMs
	if !end {
		return nil
	}
	if v.speechMs < v.cfg.MinSpeechMs {
		v.resetUtt()
		return nil
	}
	u := &Utterance{PCM16k: append([]byte(nil), v.utt...), PeakRMS: v.peak}
	v.resetUtt()
	return u
}
