package main

import (
	"context"
	"testing"
)

func TestParseSubSlotMapFromISub(t *testing.T) {
	raw := `
Logical SIM slot sub id mapping:
  Logical SIM slot 0: subId=2
  Logical SIM slot 1: subId=1
defaultSubId=2
`
	m := parseSubSlotMap(raw)
	if m[2] != 0 || m[1] != 1 {
		t.Fatalf("%v", m)
	}
}

func TestParseSubSlotMapFromSimInfo(t *testing.T) {
	raw := `Row: 0 _id=1, sim_id=1, carrier_name=CHN-UNICOM
Row: 1 _id=2, sim_id=0, carrier_name=CMCC
`
	m := parseSubSlotMap(raw)
	if m[1] != 1 || m[2] != 0 {
		t.Fatalf("%v", m)
	}
}

func TestSubSlotMapSlot(t *testing.T) {
	s := &SubSlotMap{
		Query: func(context.Context) (string, error) {
			return "Logical SIM slot 0: subId=2\nLogical SIM slot 1: subId=1\n", nil
		},
	}
	if s.Slot(context.Background(), 2) != 0 {
		t.Fatal("sub 2 should be slot 0 (CMCC/卡1)")
	}
	if s.Slot(context.Background(), 1) != 1 {
		t.Fatal("sub 1 should be slot 1 (UNICOM/卡2)")
	}
}
