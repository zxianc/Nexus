package main

import (
	"context"
	"testing"
	"time"

	"nexus.nexuscfg"
)

type stubWatch struct {
	list []Incoming
}

func (s *stubWatch) Poll(context.Context) ([]Incoming, error) { return s.list, nil }

type stubAdapter struct {
	answers, rejects int
}

func (a *stubAdapter) Answer(_ context.Context, _ int) error { a.answers++; return nil }
func (a *stubAdapter) Reject(_ context.Context, _ int) error { a.rejects++; return nil }

func TestEngineDedupeAndPolicy(t *testing.T) {
	dir := t.TempDir()
	path := dir + "/config.json"
	cfg := nexuscfg.Default()
	cfg.Sims = []nexuscfg.Sim{
		{Slot: 0, Label: "卡1", Policy: nexuscfg.PolicyAI},
		{Slot: 1, Label: "卡2", Policy: nexuscfg.PolicyReject},
	}
	if err := nexuscfg.SaveAtomic(path, cfg); err != nil {
		t.Fatal(err)
	}

	w := &stubWatch{list: []Incoming{{Key: "k1", Slot: 0, Peer: "10086"}}}
	ad := &stubAdapter{}
	eng := &Engine{ConfigPath: path, Watch: w, Adapter: ad}

	eng.tick(context.Background())
	eng.tick(context.Background()) // same key → dedupe
	if ad.answers != 1 {
		t.Fatalf("answers=%d", ad.answers)
	}

	w.list = []Incoming{{Key: "k2", Slot: 1, Peer: "10010"}}
	eng.tick(context.Background())
	if ad.rejects != 1 {
		t.Fatalf("rejects=%d", ad.rejects)
	}

	w.list = []Incoming{{Key: "k3", Slot: 0, Peer: "x"}}
	// human: wipe handled age by using new key + policy human
	cfg.Sims[0].Policy = nexuscfg.PolicyHuman
	_ = nexuscfg.SaveAtomic(path, cfg)
	before := ad.answers
	eng.tick(context.Background())
	if ad.answers != before {
		t.Fatalf("human should not answer")
	}
}

func TestEngineAnswerFailureRetries(t *testing.T) {
	dir := t.TempDir()
	path := dir + "/config.json"
	cfg := nexuscfg.Default()
	cfg.Sims = []nexuscfg.Sim{{Slot: 0, Policy: nexuscfg.PolicyAI}}
	if err := nexuscfg.SaveAtomic(path, cfg); err != nil {
		t.Fatal(err)
	}
	w := &stubWatch{list: []Incoming{{Key: "fail1", Slot: 0, Peer: "10086"}}}
	ad := &failThenOKAdapter{failN: 1}
	eng := &Engine{ConfigPath: path, Watch: w, Adapter: ad}
	eng.tick(context.Background())
	if ad.answers != 1 {
		t.Fatalf("first answers=%d", ad.answers)
	}
	// Still in 2s cooldown
	eng.tick(context.Background())
	if ad.answers != 1 {
		t.Fatalf("cooldown answers=%d", ad.answers)
	}
	// Expire failure entry
	eng.mu.Lock()
	ent := eng.handled["fail1"]
	ent.at = time.Now().Add(-3 * time.Second)
	eng.handled["fail1"] = ent
	eng.mu.Unlock()
	eng.tick(context.Background())
	if ad.answers != 2 {
		t.Fatalf("retry answers=%d", ad.answers)
	}
}

type failThenOKAdapter struct {
	failN, answers int
}

func (a *failThenOKAdapter) Answer(context.Context, int) error {
	a.answers++
	if a.failN > 0 {
		a.failN--
		return context.DeadlineExceeded
	}
	return nil
}
func (a *failThenOKAdapter) Reject(context.Context, int) error { return nil }

func TestEngineMissingConfigDefaultsHuman(t *testing.T) {
	ad := &stubAdapter{}
	eng := &Engine{
		ConfigPath: "/no/such/config.json",
		Watch:      &stubWatch{list: []Incoming{{Key: "r", Slot: 0}}},
		Adapter:    ad,
	}
	eng.handled = map[string]handledEntry{}
	eng.tick(context.Background())
	if ad.answers != 0 || ad.rejects != 0 {
		t.Fatalf("want human no-op, got a=%d r=%d", ad.answers, ad.rejects)
	}
}
