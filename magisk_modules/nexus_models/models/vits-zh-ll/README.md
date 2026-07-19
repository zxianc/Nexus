# VITS zh-ll (`sherpa-onnx-vits-zh-ll`)

Copy model dir contents here before packing:
`/data/local/tmp/nexus_stt/vits-zh-ll/*` → here

Typical files: `model.onnx`, `tokens.txt`, `lexicon.txt`, `phone.fst`, `date.fst`, `number.fst`.

## Speaker ID (`sid` / `-tts-sid` / `tts.sid`)

本模型为 **5 说话人**（官方：`csukuangfj/sherpa-onnx-vits-zh-ll`）：

| sid | 说明 |
|-----|------|
| 0 | 默认 |
| 1 | 可选音色 |
| 2 | 可选音色（文档示例常用） |
| 3 | 可选音色 |
| 4 | 可选音色 |

合法范围 **0～4**；无官方人名标签，靠听感区分。超出范围勿用。

配置：`/data/adb/nexus/config.json` → `tts.sid`，或 WebUI「Speaker id」，或 `ai_call -tts-sid N`。
