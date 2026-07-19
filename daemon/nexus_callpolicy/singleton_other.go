//go:build !unix

package main

import (
	"os"
	"path/filepath"
)

// acquireSingletonLock is a no-op flock on non-unix (host unit tests).
func acquireSingletonLock(path string) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return nil, err
	}
	return os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0600)
}
