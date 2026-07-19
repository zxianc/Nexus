package llm

import (
	"testing"
)

func TestSentenceBuf_FlushOnPunct(t *testing.T) {
	var got []string
	b := NewSentenceBuf(func(s string) {
		got = append(got, s)
	})
	b.Push("你好。世界！还有吗？")
	b.Flush()
	want := []string{"你好。", "世界！", "还有吗？"}
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("[%d] got %q want %q", i, got[i], want[i])
		}
	}
}

func TestSentenceBuf_PartialThenComplete(t *testing.T) {
	var got []string
	b := NewSentenceBuf(func(s string) {
		got = append(got, s)
	})
	b.Push("你好")
	if len(got) != 0 {
		t.Fatalf("early emit: %v", got)
	}
	b.Push("世界。下一句")
	if len(got) != 1 || got[0] != "你好世界。" {
		t.Fatalf("got %v", got)
	}
	b.Flush()
	if len(got) != 2 || got[1] != "下一句" {
		t.Fatalf("after flush %v", got)
	}
}

func TestSentenceBuf_SkipEmpty(t *testing.T) {
	var got []string
	b := NewSentenceBuf(func(s string) {
		got = append(got, s)
	})
	b.Push("。。")
	b.Flush()
	if len(got) != 0 {
		t.Fatalf("want empty, got %v", got)
	}
}

func TestSentenceBuf_LatinPunct(t *testing.T) {
	var got []string
	b := NewSentenceBuf(func(s string) {
		got = append(got, s)
	})
	b.Push("Hi. OK?\nBye!")
	b.Flush()
	want := []string{"Hi.", "OK?", "Bye!"}
	if len(got) != len(want) {
		t.Fatalf("got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("[%d] got %q want %q", i, got[i], want[i])
		}
	}
}
