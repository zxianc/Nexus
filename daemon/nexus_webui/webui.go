package main

import (
	"io/fs"
	"net/http"

	"embed"
)

//go:embed web/*
var webFS embed.FS

func withStatic(api http.Handler) http.Handler {
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		return api
	}
	fileServer := http.FileServer(http.FS(sub))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if stringsHasAPIPrefix(r.URL.Path) {
			api.ServeHTTP(w, r)
			return
		}
		if r.URL.Path == "/" || r.URL.Path == "/index.html" {
			b, err := webFS.ReadFile("web/index.html")
			if err != nil {
				http.Error(w, err.Error(), 500)
				return
			}
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write(b)
			return
		}
		fileServer.ServeHTTP(w, r)
	})
}

func stringsHasAPIPrefix(p string) bool {
	return len(p) >= 4 && p[:4] == "/api"
}
