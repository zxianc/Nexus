package main

import "unicode"

// hasSpeechText reports whether STT output contains at least one letter or digit
// (Latin or CJK). Punctuation-only / empty results are discarded.
func hasSpeechText(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return true
		}
	}
	return false
}
