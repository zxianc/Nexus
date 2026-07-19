# nexus_callpolicy

双卡来电策略守护进程：轮询 `dumpsys telecom`，按 `config.json` 的 `sims[].policy` 决定 **human / ai / reject**。

- 配置：`/data/adb/nexus/config.json`（每次 tick 热读，改 WebUI 后无需重启本进程）
- 日志：`/data/vendor/ai_hook/nexus_callpolicy.log`
- 启动：`nexus_runtime/service.sh`；**不被** `restart_callstack.sh` 杀掉
- 设计：`docs/superpowers/specs/2026-07-19-callpolicy-sims-design.md`

## 构建

```bat
build.bat
copy nexus_callpolicy_arm64 ..\..\magisk_modules\nexus_runtime\bin\nexus_callpolicy
```

## 真机标定

**检测（优先）：** `dumpsys telephony.registry` 中 `Phone Id=N` + `mCallState=1`（响铃）+ `mCallIncomingNumber`。  
避免解析 `dumpsys telecom` 历史里的 `Enter RINGING` / `Ringing calls:` 段标题（会误报）。

Answer/Reject（本机标定）：

| 动作 | 优先命令 |
|------|----------|
| Answer | `KEYCODE_HEADSETHOOK` → `telecom 36`（acceptRingingCall）→ `KEYCODE_CALL` |
| Reject | `telecom 35`（endCall）→ `KEYCODE_ENDCALL` |

启动时会 `appops set … ANSWER_PHONE_CALLS allow`（否则 accept 会静默失败）。**必须以 `mCallState` 校验**，禁止把 Parcel 退出码当成成功。
