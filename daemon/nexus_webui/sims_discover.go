package main

import (
	"context"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"

	"nexus.nexuscfg"
)

var (
	reSimRowField = regexp.MustCompile(`(\w+)=([^,]*)`)
)

// discoverDeviceSims reads slot/carrier/number from Android telephony provider.
func discoverDeviceSims(ctx context.Context) ([]nexuscfg.Sim, error) {
	cctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, "content", "query", "--uri", "content://telephony/siminfo").CombinedOutput()
	text := string(out)
	if err != nil && len(strings.TrimSpace(text)) == 0 {
		return nil, err
	}
	sims := parseSimInfoRows(text)
	if len(sims) == 0 {
		// fallback: gsm.sim.operator.alpha
		sims = simsFromOperatorProp(ctx)
	}
	return sims, nil
}

func parseSimInfoRows(text string) []nexuscfg.Sim {
	var out []nexuscfg.Sim
	for _, line := range strings.Split(text, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "Row:") {
			continue
		}
		fields := map[string]string{}
		for _, m := range reSimRowField.FindAllStringSubmatch(line, -1) {
			fields[m[1]] = strings.TrimSpace(m[2])
		}
		slot := -1
		if v, ok := fields["sim_id"]; ok {
			slot, _ = strconv.Atoi(v)
		}
		if slot < 0 {
			continue
		}
		carrier := firstNonEmpty(fields["carrier_name"], fields["display_name"])
		label := firstNonEmpty(fields["carrier_name"], fields["display_name"], "卡"+strconv.Itoa(slot+1))
		number := firstNonEmpty(fields["number"], fields["phone_number_source_ims"], fields["phone_number_source_carrier"])
		out = append(out, nexuscfg.Sim{
			Slot:    slot,
			Label:   label,
			Carrier: carrier,
			Number:  number,
			Policy:  nexuscfg.PolicyHuman,
		})
	}
	return out
}

func simsFromOperatorProp(ctx context.Context) []nexuscfg.Sim {
	cctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, "getprop", "gsm.sim.operator.alpha").CombinedOutput()
	if err != nil {
		return nil
	}
	parts := strings.Split(strings.TrimSpace(string(out)), ",")
	var sims []nexuscfg.Sim
	for i, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		sims = append(sims, nexuscfg.Sim{
			Slot:    i,
			Label:   p,
			Carrier: p,
			Policy:  nexuscfg.PolicyHuman,
		})
	}
	return sims
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		v = strings.TrimSpace(v)
		if v != "" && v != "null" && v != "NULL" {
			return v
		}
	}
	return ""
}
