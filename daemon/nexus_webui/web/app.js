function $(id) { return document.getElementById(id); }

function toast(msg, err) {
  const el = $("toast");
  el.hidden = false;
  el.textContent = msg;
  el.className = "toast" + (err ? " err" : "");
}

async function refreshStatus() {
  const r = await fetch("/api/status");
  const j = await r.json();
  const box = $("proc");
  box.innerHTML = "";
  const procs = j.processes || {};
  for (const name of Object.keys(procs)) {
    const p = procs[name];
    const d = document.createElement("span");
    d.className = "pill " + (p.running ? "on" : "off");
    d.textContent = name + (p.running ? " #" + p.pid : " down");
    box.appendChild(d);
  }
}

async function loadConfig() {
  const j = await (await fetch("/api/config")).json();
  const c = j.config || {};
  const llm = c.llm || {};
  const stt = c.stt || {};
  const tts = c.tts || {};
  const paths = c.paths || {};
  const webui = c.webui || {};
  $("llm_enabled").checked = !!llm.enabled;
  $("llm_model").value = llm.model || "";
  $("llm_api_key").value = "";
  $("llm_api_key").placeholder = llm.api_key_set
    ? ("已配置 ···" + (llm.api_key_hint || ""))
    : "未设置";
  $("clear_api_key").checked = false;
  $("llm_barge_in").checked = !!llm.barge_in;
  $("llm_max_msgs").value = llm.max_msgs || 24;
  $("llm_system").value = llm.system_prompt || "";
  $("stt_lang").value = stt.lang || "auto";
  $("tts_beep").checked = !!tts.beep_prefix;
  $("tts_sid").value = tts.sid || 0;
  $("path_engine_sock").value = paths.engine_sock || "";
  $("path_archive").value = paths.archive_dir || "";
  $("path_stt").value = paths.stt_model || "";
  $("path_tts").value = paths.tts_model || "";
  $("webui_port").value = webui.port || 8787;
}

function collectForm() {
  const body = {
    llm: {
      enabled: $("llm_enabled").checked,
      model: $("llm_model").value,
      barge_in: $("llm_barge_in").checked,
      max_msgs: Number($("llm_max_msgs").value || 24),
      system_prompt: $("llm_system").value,
    },
    stt: { lang: $("stt_lang").value },
    tts: {
      beep_prefix: $("tts_beep").checked,
      sid: Number($("tts_sid").value || 0),
    },
    paths: {
      engine_sock: $("path_engine_sock").value,
      archive_dir: $("path_archive").value,
      stt_model: $("path_stt").value,
      tts_model: $("path_tts").value,
    },
    webui: { port: Number($("webui_port").value || 8787) },
  };
  const key = $("llm_api_key").value.trim();
  if (key) body.llm.api_key = key;
  if ($("clear_api_key").checked) body.clear_api_key = true;
  return body;
}

async function saveConfig(ev) {
  ev.preventDefault();
  const body = collectForm();
  const r = await fetch("/api/config", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const j = await r.json();
  if (!j.ok) {
    toast(j.error || "保存失败", true);
    return;
  }
  let msg = "已保存";
  if (j.restarted && j.restarted.length) msg += " · 已重启 " + j.restarted.join(",");
  if (j.webui_restart_required) msg += " · 请重启 nexus_webui 使端口生效";
  toast(msg);
  await loadConfig();
  await refreshStatus();
}

async function loadLogs() {
  const name = $("logName").value;
  const lines = $("logLines").value || 80;
  const j = await (await fetch("/api/logs?name=" + encodeURIComponent(name) + "&lines=" + lines)).json();
  if (!j.ok) {
    $("logOut").textContent = j.error || "error";
    return;
  }
  $("logOut").textContent = j.lines || "(empty)";
}

$("btnRefresh").onclick = () => refreshStatus().catch((e) => toast(String(e), true));
$("btnRestart").onclick = async () => {
  const j = await (await fetch("/api/restart", { method: "POST" })).json();
  if (!j.ok) toast(j.error || "重启失败", true);
  else toast("已重启对话服务");
  await refreshStatus();
};
$("cfgForm").onsubmit = (e) => saveConfig(e).catch((err) => toast(String(err), true));
$("btnLogs").onclick = () => loadLogs().catch((e) => toast(String(e), true));

Promise.all([refreshStatus(), loadConfig()]).catch((e) => toast(String(e), true));
