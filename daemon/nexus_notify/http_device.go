package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

// Same approach as ai_call/llm: CGO=0 on Android has no resolv.conf / system CAs.
var defaultDNSServers = []string{"223.5.5.5:53", "8.8.8.8:53"}

var androidCADirs = []string{
	"/apex/com.android.conscrypt/cacerts",
	"/system/etc/security/cacerts",
}

func newAndroidHTTPClient() *http.Client {
	servers := append([]string(nil), defaultDNSServers...)
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var last error
			d := net.Dialer{Timeout: 3 * time.Second}
			for _, s := range servers {
				conn, err := d.DialContext(ctx, "udp", s)
				if err == nil {
					return conn, nil
				}
				last = err
			}
			return nil, fmt.Errorf("dns dial: %w", last)
		},
	}
	dialer := &net.Dialer{
		Timeout:   20 * time.Second,
		KeepAlive: 30 * time.Second,
		Resolver:  resolver,
	}
	return &http.Client{
		Timeout: 45 * time.Second,
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			DialContext:           dialer.DialContext,
			ForceAttemptHTTP2:     true,
			MaxIdleConns:          4,
			IdleConnTimeout:       90 * time.Second,
			TLSHandshakeTimeout:   15 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
			TLSClientConfig: &tls.Config{
				MinVersion: tls.VersionTLS12,
				RootCAs:    loadSystemRootCAs(),
			},
		},
	}
}

func loadSystemRootCAs() *x509.CertPool {
	pool, err := x509.SystemCertPool()
	if err != nil || pool == nil {
		pool = x509.NewCertPool()
	}
	for _, dir := range androidCADirs {
		ents, err := os.ReadDir(dir)
		if err != nil {
			continue
		}
		for _, e := range ents {
			if e.IsDir() {
				continue
			}
			b, err := os.ReadFile(filepath.Join(dir, e.Name()))
			if err != nil || len(b) == 0 {
				continue
			}
			if !pool.AppendCertsFromPEM(b) {
				if cert, err := x509.ParseCertificate(b); err == nil {
					pool.AddCert(cert)
				}
			}
		}
	}
	return pool
}
