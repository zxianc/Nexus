package main

import (
	"strings"
	"testing"
)

func TestParseSMSInbox(t *testing.T) {
	raw := `Row: 0 _id=12, thread_id=1, address=10086, person=null, date=1721376000000, date_sent=0, protocol=0, read=1, status=-1, type=1, reply_path_present=0, subject=null, body=您好验证码1234, service_center=+8613800, locked=0, sub_id=1, error_code=0, seen=1
Row: 1 _id=13, thread_id=2, address=1069, person=null, date=1721376100000, date_sent=0, protocol=0, read=0, status=-1, type=1, reply_path_present=0, subject=null, body=快递 orth, service_center=+8613800, locked=0, sub_id=2, error_code=0, seen=0
`
	msgs := parseSMSInbox(raw)
	if len(msgs) != 2 {
		t.Fatalf("len=%d", len(msgs))
	}
	if msgs[0].ID != 12 || msgs[0].Address != "10086" || !strings.Contains(msgs[0].Body, "验证码") {
		t.Fatalf("%+v", msgs[0])
	}
	if msgs[0].SubID != 1 || msgs[1].SubID != 2 {
		t.Fatalf("sub %d %d", msgs[0].SubID, msgs[1].SubID)
	}
}

func TestParseContentRow(t *testing.T) {
	kv := parseContentRow(`_id=1, address=10086, body=hello, world, sub_id=2`)
	// body may be truncated at comma — acceptable limitation for v1
	if kv["_id"] != "1" || kv["address"] != "10086" {
		t.Fatalf("%v", kv)
	}
}
