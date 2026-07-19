package main

import "testing"

func TestParseRegistryRingingPerSlot(t *testing.T) {
	sample := `
last known state:
  Phone Id=0
    mCallState=0
    mCallIncomingNumber=
  Phone Id=1
    mCallState=1
    mCallIncomingNumber=13800138000
`
	got := parseRegistry(sample)
	if len(got) != 1 {
		t.Fatalf("len=%d %#v", len(got), got)
	}
	if got[0].Slot != 1 || got[0].Peer != "13800138000" {
		t.Fatalf("%+v", got[0])
	}
}

func TestParseRegistryIdle(t *testing.T) {
	sample := `
  Phone Id=0
    mCallState=0
  Phone Id=1
    mCallState=0
`
	if got := parseRegistry(sample); len(got) != 0 {
		t.Fatalf("%+v", got)
	}
}

func TestParseTelecomIgnoresHistory(t *testing.T) {
	// Real dumpsys always has "Ringing calls:" and "Enter RINGING" history.
	sample := `
CallsManager:
  mCalls:
  mCallAudioManager:
    Ringing calls:
      History:
      2026-07-19T18:04:12.819890 - Enter RINGING
Historical Events:
  CallTC@2 [incoming]
  	To address: tel:*********84
    16:49:16.167 - SET_RINGING
`
	if got := parseTelecomLive(sample); len(got) != 0 {
		t.Fatalf("false positive: %+v", got)
	}
}

func TestParseTelecomLiveRinging(t *testing.T) {
	sample := `
mCalls:
Call TC@12 [RINGING]
  To address: tel:+8613800138000
  slotId: 1
`
	got := parseTelecomLive(sample)
	if len(got) != 1 {
		t.Fatalf("len=%d %#v", len(got), got)
	}
	if got[0].Slot != 1 || got[0].Peer != "+8613800138000" {
		t.Fatalf("%+v", got[0])
	}
}
