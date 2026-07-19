package main

import "testing"

func TestParseSimInfoRows(t *testing.T) {
	sample := `
Row: 0 _id=1, icc_id=8985, sim_id=1, display_name=3, carrier_name=CHN-UNICOM, number=, phone_number_source_ims=+852111
Row: 1 _id=2, icc_id=8986, sim_id=0, display_name=CMCC, carrier_name=CMCC, number=+8613900000000, phone_number_source_ims=+8613900000000
`
	got := parseSimInfoRows(sample)
	if len(got) != 2 {
		t.Fatalf("len=%d", len(got))
	}
	by := map[int]string{}
	for _, s := range got {
		by[s.Slot] = s.Carrier + "|" + s.Number
	}
	if by[0] != "CMCC|+8613900000000" {
		t.Fatalf("slot0=%q", by[0])
	}
	if by[1] != "CHN-UNICOM|+852111" {
		t.Fatalf("slot1=%q", by[1])
	}
	for _, s := range got {
		if s.Slot == 1 && s.Label != "CHN-UNICOM" {
			t.Fatalf("label prefer carrier: %+v", s)
		}
	}
}
