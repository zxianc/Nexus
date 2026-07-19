package main

import "testing"

func TestParseRegistryCallStatesDual(t *testing.T) {
	sample := `
  Phone Id=0
    mCallState=2
  Phone Id=1
    mCallState=0
`
	st := parseRegistryCallStates(sample)
	if st[0] != 2 || st[1] != 0 {
		t.Fatalf("%v", st)
	}
	if !anyCallActive(st) {
		t.Fatal("want active")
	}
}

func TestAnyCallActiveIdle(t *testing.T) {
	if anyCallActive(map[int]int{0: 0, 1: 0}) {
		t.Fatal("idle")
	}
	if !anyCallActive(map[int]int{1: 1}) {
		t.Fatal("ringing")
	}
}

func TestParseActiveCallMeta(t *testing.T) {
	sample := `
  Phone Id=0
    mCallState=0
    mCallIncomingNumber=
  Phone Id=1
    mCallState=2
    mCallIncomingNumber=17724784184
`
	peer, slot, ok := parseActiveCallMeta(sample)
	if !ok || peer != "17724784184" || slot != 1 {
		t.Fatalf("peer=%q slot=%d ok=%v", peer, slot, ok)
	}
}
