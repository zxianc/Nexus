package nexuscfg

import (
	"testing"
)

func TestDefaultSimsHuman(t *testing.T) {
	cfg := Default()
	if len(cfg.Sims) != 2 {
		t.Fatalf("len=%d", len(cfg.Sims))
	}
	if PolicyForSlot(cfg, 0) != PolicyHuman || PolicyForSlot(cfg, 1) != PolicyHuman {
		t.Fatalf("%+v", cfg.Sims)
	}
	if PolicyForSlot(cfg, 9) != PolicyHuman {
		t.Fatal("missing slot")
	}
}

func TestNormalizePolicy(t *testing.T) {
	if NormalizePolicy("AI") != PolicyAI {
		t.Fatal("ai")
	}
	if NormalizePolicy("reject") != PolicyReject {
		t.Fatal("reject")
	}
	if NormalizePolicy("nope") != PolicyHuman {
		t.Fatal("fallback")
	}
}

func TestApplyPUTSims(t *testing.T) {
	cur := Default()
	cur.Sims = []Sim{
		{Slot: 0, Label: "CMCC", Carrier: "CMCC", Number: "+86139", Policy: PolicyHuman},
		{Slot: 1, Label: "UNICOM", Carrier: "CHN-UNICOM", Number: "+852", Policy: PolicyHuman},
	}
	// Client must not overwrite device identity — only policy.
	next, _, err := ApplyPUT(cur, []byte(`{"sims":[{"slot":0,"label":"hack","number":"000","policy":"ai"},{"slot":1,"policy":"reject"}]}`))
	if err != nil {
		t.Fatal(err)
	}
	if PolicyForSlot(next, 0) != PolicyAI || next.Sims[0].Number != "+86139" || next.Sims[0].Label != "CMCC" {
		t.Fatalf("%+v", next.Sims[0])
	}
	if PolicyForSlot(next, 1) != PolicyReject || next.Sims[1].Carrier != "CHN-UNICOM" {
		t.Fatalf("%+v", next.Sims[1])
	}
}

func TestMergeDeviceSimsKeepsPolicy(t *testing.T) {
	cfg := Default()
	cfg.Sims[0].Policy = PolicyAI
	MergeDeviceSims(&cfg, []Sim{
		{Slot: 0, Label: "CMCC", Carrier: "CMCC", Number: "139"},
		{Slot: 1, Label: "联通", Carrier: "CHN-UNICOM", Number: "177"},
	})
	if cfg.Sims[0].Policy != PolicyAI || cfg.Sims[0].Carrier != "CMCC" {
		t.Fatalf("%+v", cfg.Sims[0])
	}
	if cfg.Sims[1].Number != "177" {
		t.Fatalf("%+v", cfg.Sims[1])
	}
}
