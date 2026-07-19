package llm

import (
	"fmt"
	"strings"
	"time"
)

// DefaultSystemPrompt is the phone receptionist persona.
// {{NOW}} is replaced on every LLM request with local datetime + 工作日/休息日.
const DefaultSystemPrompt = `你是机主的电话助理，正在代接来电。用简体中文口语简短回答，每句尽量短，适合语音播报。不要用 Markdown、列表、表情或括号旁白。结合本通电话上下文，不要复述对方整句原话。

当前时间：{{NOW}}
请按「当前时间」判断今天是工作日还是休息日（周一到周五为工作日，周六周日为休息日）。

来电分类与处理：
1. 外卖：告诉对方放门口即可，致谢后可结束。
2. 快递：工作日请放驿站；休息日请送上门。说清即可，语气礼貌。
3. 推销、广告、回访、骚扰等：可以随意聊几句、打趣或周旋，不必正经拒绝；对方啰嗦时再自然收束。仍不要泄露隐私、不要答应办卡/转账/上门。
4. 若对方仍有问题、必须联系机主、或你无法代决：请对方加微信联系机主，不要泄露隐私，不要承诺机主何时回电。

开场可先问来意；确认类型后按上面规则答复。不要主动透露你是 AI，除非对方追问。`

var weekdayCN = [...]string{"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"}

// ExpandSystemPrompt injects current local time into {{NOW}}.
// If the template has no {{NOW}}, a time line is appended so the model still sees "now".
func ExpandSystemPrompt(tmpl string, now time.Time) string {
	tmpl = strings.TrimSpace(tmpl)
	if tmpl == "" {
		tmpl = DefaultSystemPrompt
	}
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		loc = time.FixedZone("CST", 8*3600)
	}
	now = now.In(loc)
	line := formatNowLine(now)
	if strings.Contains(tmpl, "{{NOW}}") {
		return strings.ReplaceAll(tmpl, "{{NOW}}", line)
	}
	return tmpl + "\n\n当前时间：" + line
}

func formatNowLine(now time.Time) string {
	kind := "工作日"
	if now.Weekday() == time.Saturday || now.Weekday() == time.Sunday {
		kind = "休息日"
	}
	return fmt.Sprintf("%s %s %02d:%02d（%s）",
		now.Format("2006年1月2日"),
		weekdayCN[now.Weekday()],
		now.Hour(), now.Minute(),
		kind)
}
