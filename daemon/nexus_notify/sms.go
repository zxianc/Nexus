package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"nexus.nexuscfg"
)

var (
	reSMSRow = regexp.MustCompile(`(?m)^Row:\s*\d+\s+(.*)$`)
)

// SMSPoller polls content://sms/inbox and forwards new messages.
type SMSPoller struct {
	CursorPath string
	Interval   time.Duration
	Load       func() (nexuscfg.Config, error)
	Client     *WeComClient
	QueryInbox func(ctx context.Context) (string, error)
	Slots      *SubSlotMap
}

func (p *SMSPoller) Run(ctx context.Context) {
	if p.Interval <= 0 {
		p.Interval = 3 * time.Second
	}
	if p.QueryInbox == nil {
		p.QueryInbox = querySMSInbox
	}
	if p.Slots == nil {
		p.Slots = &SubSlotMap{}
	}
	// Seed cursor on first run so history is not blasted.
	if err := p.ensureCursor(ctx); err != nil {
		log.Printf("sms cursor seed: %v", err)
	}
	t := time.NewTicker(p.Interval)
	defer t.Stop()
	for {
		p.pollOnce(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			cfg, err := p.Load()
			if err == nil && cfg.Notify.SMS.PollMs > 0 {
				next := time.Duration(cfg.Notify.SMS.PollMs) * time.Millisecond
				if next != p.Interval && next >= 500*time.Millisecond {
					t.Reset(next)
					p.Interval = next
				}
			}
		}
	}
}

func (p *SMSPoller) cursorPath() string {
	if p.CursorPath != "" {
		return p.CursorPath
	}
	return "/data/adb/nexus/run/notify_sms_cursor"
}

func (p *SMSPoller) ensureCursor(ctx context.Context) error {
	path := p.cursorPath()
	if _, err := os.Stat(path); err == nil {
		return nil
	}
	out, err := p.QueryInbox(ctx)
	if err != nil {
		return err
	}
	msgs := parseSMSInbox(out)
	maxID := int64(0)
	for _, m := range msgs {
		if m.ID > maxID {
			maxID = m.ID
		}
	}
	return writeCursor(path, maxID)
}

func (p *SMSPoller) pollOnce(ctx context.Context) {
	cfg, err := p.Load()
	if err != nil {
		return
	}
	if !cfg.Notify.Enabled || !cfg.Notify.SMS.Enabled {
		return
	}
	out, err := p.QueryInbox(ctx)
	if err != nil {
		log.Printf("sms query: %v", err)
		return
	}
	msgs := parseSMSInbox(out)
	sortSMSByID(msgs)
	cur, err := readCursor(p.cursorPath())
	if err != nil {
		cur = 0
	}
	for _, m := range msgs {
		if m.ID <= cur {
			continue
		}
		slot := p.Slots.Slot(ctx, m.SubID)
		local := FormatSimLocal(cfg.Sims, slot)
		text := FormatSMSMessage(SMSNotify{
			Time:   formatSMSTime(m.DateMs),
			Sender: m.Address,
			Local:  local,
			Body:   m.Body,
		})
		cctx, cancel := context.WithTimeout(ctx, 45*time.Second)
		err := p.Client.SendWithRetry(cctx, cfg.Notify.WeCom, cfg.Notify.Channel, text, 3)
		cancel()
		if err != nil {
			log.Printf("sms notify id=%d: %v", m.ID, err)
			// Keep cursor so we retry this id next round (don't advance past failure).
			return
		}
		log.Printf("sms notify ok id=%d from=%s sub_id=%d slot=%d local=%q", m.ID, m.Address, m.SubID, slot, local)
		if err := writeCursor(p.cursorPath(), m.ID); err != nil {
			log.Printf("sms cursor write: %v", err)
		}
		cur = m.ID
	}
}

func sortSMSByID(msgs []smsMsg) {
	sort.Slice(msgs, func(i, j int) bool { return msgs[i].ID < msgs[j].ID })
}

type smsMsg struct {
	ID      int64
	Address string
	Body    string
	DateMs  int64
	SubID   int
}

func querySMSInbox(ctx context.Context) (string, error) {
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	cmd := exec.CommandContext(cctx, "content", "query", "--uri", "content://sms/inbox")
	out, err := cmd.CombinedOutput()
	if err != nil {
		return "", fmt.Errorf("%v: %s", err, truncateASCII(string(out), 200))
	}
	return string(out), nil
}

func parseSMSInbox(raw string) []smsMsg {
	var out []smsMsg
	for _, m := range reSMSRow.FindAllStringSubmatch(raw, -1) {
		if len(m) < 2 {
			continue
		}
		kv := parseContentRow(m[1])
		id, _ := strconv.ParseInt(kv["id"], 10, 64)
		if id == 0 {
			id, _ = strconv.ParseInt(kv["_id"], 10, 64)
		}
		if id == 0 {
			continue
		}
		date, _ := strconv.ParseInt(kv["date"], 10, 64)
		sub := 0
		for _, k := range []string{"sub_id", "sim_id", "subscription_id"} {
			if v, ok := kv[k]; ok {
				sub, _ = strconv.Atoi(v)
				break
			}
		}
		out = append(out, smsMsg{
			ID:      id,
			Address: kv["address"],
			Body:    kv["body"],
			DateMs:  date,
			SubID:   sub,
		})
	}
	return out
}

func parseContentRow(row string) map[string]string {
	out := map[string]string{}
	// content query format: key=value, key=value, ...
	// Values may contain commas rarely; split on ", " then first '='.
	parts := strings.Split(row, ", ")
	for _, p := range parts {
		i := strings.IndexByte(p, '=')
		if i <= 0 {
			continue
		}
		k := strings.TrimSpace(p[:i])
		v := p[i+1:]
		out[k] = v
	}
	return out
}

func formatSMSTime(ms int64) string {
	if ms <= 0 {
		return time.Now().Format("2006-01-02 15:04:05")
	}
	return time.UnixMilli(ms).In(time.Local).Format("2006-01-02 15:04:05")
}

func readCursor(path string) (int64, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	return strconv.ParseInt(strings.TrimSpace(string(b)), 10, 64)
}

func writeCursor(path string, id int64) error {
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(fmt.Sprintf("%d\n", id)), 0600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
