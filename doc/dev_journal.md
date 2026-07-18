# 寮€鍙戣繃绋嬫棩蹇楋紙澧為噺杩藉姞锛?

**鐢ㄩ€旓細** 鎸夋椂闂磋褰曞仛杩囦粈涔堛€佽俯杩囦粈涔堝潙銆佸綋鏃剁粨璁恒€? 
**鍐欐硶锛?* **鍙湪鏂囨湯杩藉姞鏂版潯鐩?*锛屼笉瑕佹敼鍐欐棫鏉＄洰锛堢籂閿欏彲鍐嶅紑涓€鏉°€屾洿姝ｃ€嶏級銆? 
**鎬绘柟妗?/ 褰撳墠鐘舵€侊細** 浠?[`plan.md`](plan.md) 涓哄噯锛屾湰鏂囦欢涓嶈礋璐ｃ€屾渶鏂扮湡鐩搞€嶇殑澶ф鏀瑰啓銆?

---

## 2026-07-18 鈥?娉ㄥ叆閾捐矾鎵撻€氾紙Zygisk + ptrace锛?

- **鍋氫簡浠€涔堬細** 鏀惧純 Overlay 鎹?`audioserver` + `LD_PRELOAD`锛涙敼涓?Zygisk companion / `service.sh` + ptrace remote `dlopen`銆?
- **杞借嵎璺緞锛?* 蹇呴』鐢?Magisk 鎸傝浇鐨?`/system/lib64/libai_hook.so`锛坄/data/local/tmp` 浼氳 SELinux 鎷掔粷锛夈€?
- **楠岃瘉锛?* `grep libai_hook /proc/$(pidof audioserver)/maps` 鏈?`r-xp`銆?
- **璇﹁锛?* [`02_zygisk_inject_progress.md`](02_zygisk_inject_progress.md)銆乕`Magisk_Injection_Log.md`](Magisk_Injection_Log.md)

---

## 2026-07-18 鈥?Dobby 鎺㈡祴 Hook 鎵撻€?

- **鍋氫簡浠€涔堬細** `libai_hook.so` 闈欐€侀摼 Dobby锛涙帰娴?Hook `libc.so!openat`銆?
- **韪╁潙锛?*
  1. `clock_gettime` 鏄撲负 VDSO锛屼笉閫傚悎鍋氭帰娴嬨€?
  2. `DobbyCodePatch` SIGSEGV 鈫?`audioserver` 缂?`execmem`銆?
  3. Magisk sepolicy 璇硶瑕佺敤锛歚allow audioserver audioserver process execmem`锛堜笉鑳藉啓 `self:`锛夈€?
  4. constructor 鍐呯珛鍒?Hook 鏄撲笌 dlopen 绔炴€?鈫?寤惰繜绾?1.5s銆?
  5. PowerShell 浼氬睍寮€ `$(pidof)` 鈫?`adb shell` 澶栧眰鐢ㄥ崟寮曞彿锛沗logcat` 鏌ュ巻鍙插姞 `-d`銆?
- **鎴愬姛鏃ュ織锛?* `DobbyHook(openat) rc=0` / `Dobby probe installed on openat`锛涙棤 `onAudioServerDied`銆?
- **缁撹锛?* 銆岃繘杩涚▼ + 妗嗘灦鑳?Hook銆嶅畬鎴愶紱涓嬩竴姝ユ崲閫氳瘽 PCM 绗﹀彿銆?

---

## 2026-07-18 鈥?闃舵 1.C锛歮aps / 绗﹀彿渚﹀療 + 璁℃暟 Hook

- **鐩爣锛?* 鎽告竻閫氳瘽 PCM 鍙兘缁忚繃鐨勫簱涓庣鍙凤紱鎸傚彧璇昏鏁?Hook銆?
- **鍋氫簡浠€涔堬細**
  1. 鎵?`audioserver` maps锛氱害 52 涓?audio 鐩稿叧鏄犲皠锛?*鏃?*鐙珛 `libaudioflinger.so`锛圓udioFlinger 缂栬繘 `/system/bin/audioserver`锛夛紱鏈?`libaudioflinger_datapath/fastpath`銆乣libnbaio`銆乣libaudiohal@6.0` 绛夈€?
  2. **vendor HAL 涓嶅湪 audioserver**锛歚audio.primary.kona` / qti voice 鐩稿叧鍦ㄨ繘绋?`/vendor/bin/hw/android.hardware.audio.service`锛堜緥 pid=6081锛夈€?
  3. `libaudioflinger_datapath.so` 瀵煎嚭锛歚AudioStreamIn::read`銆乣AudioStreamOut::write`锛坢angled 瑙佷笅锛夈€?
  4. `audio_hook.cpp`锛歚AI_HOOK_OPENAT_PROBE` 鍙叧锛涢粯璁ゆ寕涓婅堪 IN/OUT **璁℃暟 Hook**锛堜笉鏀?buffer锛夈€?
- **鍊欓€夌鍙凤細**
  - `_ZN7android13AudioStreamIn4readEPvmPm`
  - `_ZN7android14AudioStreamOut5writeEPKvm`
- **缁撹锛?* 绗竴鍒€鍏堝湪 **audioserver 鍐?datapath** 璁℃暟锛涜嫢閫氳瘽 hit 涓嶆定锛屽啀鑰冭檻娉ㄥ叆 HAL 杩涚▼鎴?NBAIO锛坄MonoPipeReader::read` 绛夛級銆?
- **鐩稿叧浠ｇ爜锛?* `zygisk_module/cpp/audio_hook.cpp`锛涙竻鍗?[`03_pcm_hook_next.md`](03_pcm_hook_next.md)

---

## 2026-07-18 鈥?闃舵 1.C 渚﹀療锛歮aps / HAL 杩涚▼ / 绗﹀彿

- **鐩爣锛?* 鎽告竻閫氳瘽 PCM 鍙兘钀藉湪鍝潯璺緞锛岄€夎鏁?Hook 鍊欓€夈€?
- **鍋氫簡浠€涔堬細** 鎵?`audioserver` maps锛涘畾浣?`audio.primary.kona` 鎵€鍦ㄨ繘绋嬶紱`strings` 鐪?datapath / nbaio銆?
- **鐜拌薄锛?*
  1. `audioserver` **鏃?*鐙珛 `libaudioflinger.so`锛圓udioFlinger 缂栬繘 `/system/bin/audioserver`锛夛紱鏈?`libaudioflinger_datapath.so` / `libnbaio.so` / `libaudiohal@6.0.so` 绛夈€?
  2. **vendor HAL**锛坄audio.primary.kona`銆乹ti/voice/pal 绫诲簱锛夊彧鍦? 
     `/vendor/bin/hw/android.hardware.audio.service`锛堝綋鏃?pid=6081锛夛紝**涓嶅湪** `audioserver`銆?
  3. datapath 瀵煎嚭锛歚AudioStreamIn::read` / `AudioStreamOut::write`锛坢angled 宸茬‘璁わ級銆?
  4. nbaio锛歚MonoPipeReader::read`銆乣NBAIO_Sink::writeVia` 绛夈€?
- **缁撹锛?* 绗竴杞鏁?Hook 鎸傚湪 **audioserver 鍐?* datapath 鐨?In/Out锛涜嫢閫氳瘽鏃舵棤 hit锛屽啀鑰冭檻娉ㄥ叆 HAL 杩涚▼鎴栨寕 nbaio銆?
- **鐩稿叧浠ｇ爜锛?* `zygisk_module/cpp/audio_hook.cpp`锛堥殢鍚庡姞鍏?IN/OUT 璁℃暟 Hook锛夈€?

---

## 2026-07-18 鈥?闃舵 1.C锛歞atapath 璁℃暟 Hook 宸茶涓?

- **鍋氫簡浠€涔堬細** `AI_HOOK_OPENAT_PROBE` 鍙紑鍏筹紱鎸? 
  `libaudioflinger_datapath.so!AudioStreamIn::read` / `AudioStreamOut::write`锛堝彧璁℃暟锛夈€?
- **楠岃瘉锛?* 閲嶆敞鍏ュ悗鏃ュ織  
  `PCM count hooks: IN=1 OUT=1 (1=ok)`锛沗DobbyHook(...) rc=0`锛涜繘绋嬫湭姝汇€?
- **绌洪棽锛?* 宸叉湁绋€鐤?`count OUT`锛堜緥 `bytes=0`锛夛紱灏氭棤 `count IN`銆傞渶濯掍綋/閫氳瘽瀵圭収銆?
- **寰呬綘鍋氾細** 绌洪棽 / 鏀惧獟浣?/ **鎵撶數璇?* 鍚勬姄涓€娈? 
  `adb logcat -d -s AI_Audio_Hook:I`锛岀湅 `count IN` / `count OUT` 鏄惁涓婃定銆?

---

## 2026-07-18 鈥?鏇存锛歫ournal 渚﹀療鏉＄洰閲嶅

涓婃枃銆宮aps / 绗﹀彿渚﹀療銆嶄笌銆宮aps / HAL 杩涚▼ / 绗﹀彿銆嶅唴瀹归噸鍙狅紝浠?**銆岄樁娈?1.C锛歮aps / 绗﹀彿渚﹀療 + 璁℃暟 Hook銆?* + **銆宒atapath 璁℃暟 Hook 宸茶涓娿€?* 涓哄噯鍗冲彲銆?

---

## 2026-07-18 鈥?閫氳瘽瀵圭収锛歞atapath 鏈懡涓儹璺緞

- **鐜拌薄锛?* 閫氳瘽鏈?`AudioFocus`锛坄USAGE_VOICE_COMMUNICATION`锛岀害 14:53:19 / 14:53:48锛夈€?
- **Hook锛?* 鍏ㄧ▼浠呯█鐤?`count OUT ... bytes=0`锛?*鏃?* `count IN`锛涗笌閫氳瘽闊抽噺涓嶅尮閰嶃€?
- **瀵圭収锛?* `dumpsys media.audio_flinger` 鏄剧ず FastMixer `writeSequence`/`framesWritten` 寰堝ぇ 鈫?闊抽璧颁簡 **MonoPipe / FastMixer**锛屾湭缁忚繃鎴戜滑鎸傜殑 `AudioStreamOut::write` 绗﹀彿锛堢枒浼艰櫄琛ㄨ皟鐢級銆?
- **闄勬敞锛?* vendor HAL 杩涚▼鍔犺浇鐨勬槸 **32-bit** `audio.primary.kona.so`锛坄/vendor/lib/`锛夛紝褰撳墠 arm64 杞借嵎涓嶈兘鐩存帴濉炶繘璇ヨ繘绋嬨€?
- **涓嬩竴姝ワ細** 鏀规寕 `libnbaio.so` 鐨?`MonoPipe::write` / `MonoPipeReader::read` 鍐嶆墦涓€閫氬鐓с€?

---

## 2026-07-18 鈥?绗簩閫氬鐓э細MonoPipe 涔熶笉鏄€氳瘽 PCM

- **鏃堕棿绾匡紙dumpsys audio锛夛細**  
  `15:00:07 MODE_RINGTONE` 鈫?`15:00:09 MODE_IN_CALL` 鈫?`15:00:29 MODE_NORMAL`锛堢害 20s锛夈€?
- **Hook锛?* `MonoPipe::write` / `MonoPipeReader::read` 宸叉寕涓婏紙`write=1 read=1`锛夛紝閫氳瘽鏈熼棿 **闆?* `count MonoPipe` 鏃ュ織銆?
- **AudioFlinger锛?* 鍝嶉搩/鍒囧惉绛掓椂 FastMixer 鏈夌害 `framesWritten鈮?27k`锛岄殢鍚?**threadLoop_standby**锛涚湡姝ｉ€氳瘽娈?AF 澶勪簬 standby銆?
- **缁撹锛?* 楂橀€氶€氳瘽 PCM **涓嶇粡杩?* `audioserver` 鐨?Mixer/MonoPipe锛涘湪 **`android.hardware.audio.service`锛?2-bit锛?* 鐨?`audio.primary.kona`锛堝簳灞?`libtinyalsa` `pcm_write`/`pcm_read`锛夈€?
- **涓嬩竴姝ワ紙1.C Round-3锛夛細** 缂?**armeabi-v7a** 杞借嵎 + 瀵?HAL 杩涚▼ ptrace `dlopen`锛泂epolicy 缁?`hal_audio_default` `execmem`銆?

---

## 2026-07-18 鈥?Round-3锛?2-bit HAL 娉ㄥ叆瀹炵幇

- **鍋氫簡浠€涔堬細**
  1. `audio_hook_hal.cpp`锛欻ook `libtinyalsa.so!pcm_write` / `pcm_read`锛堝彧璁℃暟锛夈€?
  2. `inject.cpp` 鏀寔 arm32锛圗ABI 鏍堜紶 mmap 绗?5/6 鍙傦級锛涗骇鍑?`bin/inject32`銆?
  3. Magisk锛歚system/lib/libai_hook.so`锛?2锛? `system/lib64/...`锛?4锛夛紱`sepolicy` 澧炲姞 `hal_audio_default` execmem銆?
  4. `service.sh` 浼樺厛娉ㄥ叆 `android.hardware.audio.service`銆?
- **鐪熸満锛?* 鏈?reboot 鏃?`/system/lib/libai_hook.so` 灏氭湭 magic-mount锛涗粠 `/data/adb/modules/...` dlopen 杩斿洖 0锛圫ELinux锛夈€?*闇€瑁?zip 鍚庨噸鍚?* 鍐嶉獙璇併€?
- **楠岃瘉锛堥噸鍚悗锛夛細**
  ```text
  adb shell 'su -c "grep libai_hook /proc/\$(pidof android.hardware.audio.service)/maps"'
  adb logcat -d -s AI_Audio_Hook:I
  ```
  鏈熸湜锛歚HAL inject OK` / `HAL pcm hooks: write=1 read=1`锛屽啀鎵撲竴閫氱數璇濈湅 `count HAL pcm_*`銆?

---

## 2026-07-18 鈥?閲嶅惎鍚庨€氳瘽锛欻AL 鏈繘杩涚▼锛沴inker 鍛藉悕绌洪棿鎷掔粷 /system/lib

- **閫氳瘽锛?* `20:35:02 RINGTONE` 鈫?`20:35:04 IN_CALL` 鈫?`20:35:24 NORMAL`銆?
- **HAL锛?* overlay `/system/lib/libai_hook.so` 瀛樺湪锛屼絾 **maps 鏃犲簱**锛涙墜鍔ㄦ敞鍏?`dlopen => 0`銆?
- **鏍瑰洜锛?*  
  1. 鍏堟槸 SELinux锛坄system_file` 涓嶅彲璇伙級锛? 
  2. 淇?context 鍚?linker 鎶ワ細`not accessible for the namespace`锛宍permitted_paths=/odm:/vendor:/system/vendor`锛?*涓嶈兘**浠?`/system/lib` 鍔犺浇锛夈€?
- **audioserver锛?* MonoPipe 浠嶆湁 hit锛堝惈閫氳瘽鏃舵锛夛紝浣嗘鍓?standby 缁撹浠嶅湪鈥斺€斾笉鑳藉綋浣滈€氳瘽 PCM 璇佹嵁銆?
- **淇锛?* 32-bit 杞借嵎鏀规斁鍒?**`/vendor/lib/libai_hook.so`**锛堟ā鍧?`vendor/lib/`锛夛紱`service.sh` / sepolicy / post-fs-data 宸叉敼銆傞渶鍐嶈 zip **閲嶅惎** 鍚庨獙璇併€?

---

## 2026-07-18 鈥?HAL 娉ㄥ叆鎵撻€氾紱Dobby maps 瑙ｆ瀽宕╂簝宸茬粫杩?

- **绐佺牬锛?* `/vendor/lib/libai_hook.so` 鍙?`dlopen`锛坙inker namespace OK锛沴abel=`vendor_file`锛夈€?
- **宕╂簝锛?* 棣栫増鍦?`DobbySymbolResolver` 鈫?`GetProcessModuleMap` **SIGBUS**锛坱ombstone锛夈€?
- **淇锛?* HAL 杞借嵎鏀圭敤 `dlsym(libtinyalsa)` 鍙?`pcm_write`/`pcm_read`锛屽啀 `DobbyHook`銆?
- **楠岃瘉锛?* `HAL pcm hooks: write=1 read=1`锛沵aps 鏈夊簱锛涜繘绋嬩笉鍙嶅姝汇€?
- **寰呬綘锛?* 鎵撲竴閫氱數璇濓紝鐪?`count HAL pcm_write` / `pcm_read`銆?

---

## 2026-07-18 鈥?閫氳瘽瀵圭収锛歱cm_write 鏈?hit锛宲cm_read 鏃狅紱閫氳瘽涓鍙兘缁曞紑 tinyalsa

- **鏃堕棿锛?* `22:25:33 RINGTONE` 鈫?`22:25:36 IN_CALL` 鈫?`22:25:55 NORMAL`锛垀19s锛夈€?
- **鐜拌薄锛?*
  - `pcm_write`锛氬搷閾?鎺ラ€氬垵澶ч噺 hit锛坄bytes=768`锛夛紝鑷冲皯鍒?`#1000`锛垀22:25:38锛夈€?
  - `pcm_read`锛?*鍏ㄧ▼ 0 hit**銆?
  - `#1000` 涔嬪悗鏈鏇撮珮妗ｄ綅鏃ュ織 鈫?鐪熸閫氳瘽娈靛彲鑳戒笉鍐嶈蛋 `pcm_write`锛堥珮閫?voice/CSD/compress voip 鏃佽矾锛夈€?
- **缁撹锛?* tinyalsa 鑳芥姄浣?*閮ㄥ垎**涓嬭锛堝搷閾?鐭殏锛夛紝**涓嶆槸**瀹屾暣閫氳瘽涓婁笅琛屻€備笅涓€姝ヨ鎸?`audio.primary.kona` 鐨?voice/`out_write`/`in_read` 鎴栭潪 tinyalsa 璺緞銆?

---

## 2026-07-18 鈥?multi-hook 閫氳瘽锛歷oice 鍛戒腑锛孭CM I/O 浠嶆梺璺?

- **杞借嵎锛?* 鍦?tinyalsa 涔嬪鍔犳寕 `pcm_mmap_*`銆乣libtinycompress` `compress_*`銆乣voice_start_call` / `voice_start_usecase` / `voice_stop_usecase`銆乣cin_read`锛涘叏閮?`DobbyHook rc=0`銆?
- **閫氳瘽鏃堕棿绾匡細**
  - `22:59:20` 璧峰ぇ閲?`pcm_write`锛坄bytes=768`锛岃嚦灏戝埌 `#1000`锛?
  - `22:59:21.822` `voice_start_call` 鈫?`voice_start_usecase usecase=41`
  - `22:59:24` 鍚庢湭瑙佹洿楂?`pcm_write` 妗ｄ綅 鈫?閫氳瘽涓鏃?tinyalsa 鍐欏叆
  - `22:59:40` `voice_stop_usecase usecase=41`
- **闆?hit锛?* `pcm_read` / `pcm_mmap_*` / `compress_write|read` / `cin_read`
- **缁撹锛?* CS voice 鐢熷懡鍛ㄦ湡鍦?`audio.primary.kona`锛涘弻宸?PCM **涓?*缁?tinyalsa / tinycompress / cin銆備笅涓€姝ユ寕 `compress_voip_{open,start,close}_*` 涓?`start_{out,in}put_stream` 鍒ゅ畾鏄惁 VoIP/鏅€?stream锛屽惁鍒欐寜 ADSP/modem 鏃佽矾鏀硅矾銆?

---

## 2026-07-18 鈥?Round-4锛歝ompress_voip 鏈蛋锛涚‘璁よ渹绐?CS/IMS 鏃佽矾

- **鏂板 Hook锛?* `compress_voip_{open,start,close}_{in,out}`銆乣start_{out,in}put_stream`銆乣audio_extn_cin_read`锛堝潎 rc=0锛夈€?
- **閫氳瘽锛?* `23:24:07` `start_output_stream` + `pcm_write`锛堝搷閾冿級鈫?`23:24:09` `voice_start_* usecase=41` 鈫?`23:24:26` `voice_stop`锛涙寕鏂悗鍐?`start_output_stream`锛坄pcm_write #1500 bytes=3840`锛夈€?
- **闆?hit锛?* 鍏ㄩ儴 `compress_voip_*`銆乣pcm_read`銆乵map銆乣compress_*`銆乣cin_*`銆?
- **缁撹锛?* 涓?SIM銆岃彍鍗曡缃€嶅熀鏈棤鍏筹紱鏈?SIM 鐨勮渹绐濋€氳瘽璧?ADSP/modem锛屼笉浼?magically 杩?tinyalsa銆傚畬鏁?PCM 闇€ **incall-rec**锛堝钩鍙?XML锛歚INCALL_REC_*` 鈫?PCM **dev 23**锛夋垨鍏跺畠瀵煎嚭銆?
- **Round-5锛?* `voice_start` 鍚庝富鍔?`pcm_open` 鎺㈡祴 d23/VoiceMMode/AFE-PROXY锛屽苟 Hook incall-rec 瑙傚療鐐广€?

---

## 2026-07-18 鈥?Round-5锛氳８ pcm 鎺㈡祴 鈥?d2 琚崰锛沝23 闇€璺敱锛涙棤闈為浂 PCM

- **閫氳瘽锛?* ~40s锛沗23:42:41` `voice_start_* usecase=41` 鈫?probe 鈫?鍏跺悗 `voice_stop`銆?
- **鎺㈡祴锛?*
  - **d23 (incall-rec)锛?* `pcm_open` READY锛屼絾 `pcm_read` 涓€寰?`rc=-22 cannot prepare channel`锛堢己 HAL/mixer 寤洪摼锛夈€?
  - **d2 (VoiceMMode1)锛?* `Device or resource busy` 鈫?閫氳瘽 PCM 鍗犲湪姝よ澶囷紝HAL 宸叉墦寮€銆?
  - **d15锛?* 鍚?d23锛宲repare 澶辫触銆?
  - **d6 (AFE-PROXY TX)锛?* 鍙 30 甯э紝**鍏ㄩ浂**锛堟湭鎺ュ埌閫氳瘽锛夈€?
- **妗嗘灦渚э細** 鍏ㄧ▼鏃?`voice_check_and_set_incall_rec_usecase` / `cin_open` 鈫?鏈蛋姝ｅ紡 incall-rec usecase銆?
- **闄勬敞锛?* `voice_set_parameters` 鏃ュ織涔辩爜 鈫?绛惧悕涓嶆槸 `const char*`锛堢枒涓?`str_parms*`锛夛紝涓嬭疆淇銆?
- **缁撹锛?* 瑁?`pcm_open(23)` 涓嶅锛涜涔堣蛋瀹屾暣 incall-rec锛坄start_input` + VOICE_CALL / `platform_set_incall_recording_session_id` + mixer锛夛紝瑕佷箞鍦?**宸叉墦寮€鐨?VoiceMMode 璺緞**涓婃兂鍔炴硶锛堝叡浜?澶嶅埗娴侊紝涓嶈兘鍐?open锛夈€?

---

## 2026-07-18 鈥?Round-6 鍑嗗锛歏OC_REC mixer + session_id

- **璺緞锛?* `incall-rec-uplink` 鈫?`MultiMedia9 Mixer VOC_REC_UL=1`锛汥L 鍚岀悊锛沀L+DL 缁勫悎涓よ€呫€?
- **API锛?* `platform_set_incall_recording_session_id(platform, vsid, INCALL_REC_UPLINK_AND_DOWNLINK=2)`锛沗VOICE_VSID` 榛樿 `0x10C01000`銆?
- **瀹炵幇锛?* Hook `platform_start_voice_call` 鎶?platform/vsid锛沗voice_start` 鍚庡紑 mixer 骞?`pcm_read` d23銆?

---

## 2026-07-18 鈥?Round-6 绐佺牬锛歩ncall-rec d23 璇诲埌闈為浂 PCM

- **閫氳瘽锛?* ~20s锛沗23:51:03` voice_start usecase=41 鈫?`platform_start_voice_call vsid=0x11c05000` 鈫?`23:51:23` voice_stop銆?
- **寤洪摼锛?* `platform_set_incall_recording_session_id rc=0`锛沗VOC_REC_UL/DL=1 rc=0`銆?
- **璇诲彇锛?* `pcm READY d23 48000Hz ch2`锛沗pcm_start rc=0`锛?*hits=100, nonzero_frames=96**銆?
- **娓呯悊锛?* hangup 鏃?UL/DL 缃?0锛涙棤鏂?tombstone銆?
- **缁撹锛?* 铚傜獫閫氳瘽 PCM 鍙€氳繃 **MultiMedia9 incall-rec** 瀵煎嚭銆備笅杞細钀界洏鏍￠獙鏄惁涓轰汉澹般€佹敼杩?peak 缁熻銆佹寔缁噰闆嗚嚦鎸傛柇銆?

---

## 2026-07-19 鈥?Round-7锛氬弻鍚戠‘璁?+ 钀界洏鍚獙

- **鏄惁鍙屽悜锛?* 鏄€俙mode=INCALL_REC_UPLINK_AND_DOWNLINK(2)`锛屼笖鍚屾椂鎵撳紑 `VOC_REC_UL` + `VOC_REC_DL`锛?8k stereo 钀界洏锛屾棩蹇楁姤 `maxL`/`maxR` 鐪嬪乏鍙宠兘閲忥紙甯镐负 UL/DL 鍒嗚建鎴栨贩鍚堬級銆?
- **瀹炵幇锛?* 閫氳瘽鍏ㄧ▼ `pcm_read` 鈫?`/data/local/tmp/ai_incall.pcm`锛坒allback 妯″潡鐩綍锛夈€?
- **寰呮祴锛?* 鍐嶆墦涓€閫氾紝鍙屾柟璇磋瘽锛屾媺鍥炲惉楠屻€?

---

## 2026-07-19 鈥?Round-7 閫氳瘽锛氭湁鐪熷疄鐢靛钩锛涜惤鐩?EACCES锛汱=R

- **鏃堕棿锛?* `00:05:37` 鈫?`00:05:59`锛垀22s锛夛紱BIDIR UL+DL 寤洪摼鎴愬姛銆?
- **鐢靛钩锛?* `hits=2119 nz=1693`锛?*maxL=maxR=31354**锛堣璇濇鏄庢樉鎶崌锛屽儚鐪熶汉澹拌€岄潪搴曞櫔锛夈€?
- **澹伴亾锛?* 鍏ㄧ▼ **maxL == maxR** 鈫?鏇村儚 **UL+DL 娣峰悎鍚庡弻澹伴亾鎷疯礉**锛屼笉鏄乏涓婅/鍙充笅琛屽垎杞ㄣ€?
- **钀界洏澶辫触锛?* 涓夊璺緞鍧?`errno=13`锛坄hal_audio_default` 鏃犲啓鏉冮檺锛夆啋 `dumped=0`銆?
- **涓嬭疆锛?* 棰勫缓鍙啓鏂囦欢/鐩綍锛堟纭?SELinux label锛夋垨缁?UDS 鎶?PCM 閫佸嚭 HAL銆?

---

## 2026-07-19 鈥?Round-8锛氫慨 dump 鏉冮檺

- **鏀瑰姩锛?* `sepolicy` 鏀捐 `hal_audio_default` 鈫?`vendor_data_file`/`shell_data_file` 鍐欙紱live `magiskpolicy`锛涢寤?`/data/vendor/ai_hook/ai_incall.pcm`銆?
- **寰呮祴锛?* 鍐嶆墦涓€閫氾紝纭 `Round-8 dump fd=` 涓?`dumped>0`锛屾媺鍥炲惉楠屻€?

---

## 2026-07-19 鈥?Round-8 钀界洏鎴愬姛骞跺惉楠屽噯澶?

- **dump锛?* `fd=64` 鈫?`/data/vendor/ai_hook/ai_incall.pcm`锛?*dumped=3062400**锛垀16s @ 48k stereo s16le锛夈€?
- **鐢靛钩锛?* hits=1595 nz=1330锛宮ax=28977銆?
- **澹伴亾锛?* **L==R 100%** 鈫?UL+DL 宸叉贩鍚堜负鍗曡建鍐嶅鍒舵垚 stereo銆?
- **鏈満锛?* 宸茶浆 `tmp/ai_incall.wav` 鍙挱鏀惧惉楠屻€?

---

## 2026-07-19 鈥?鍚獙纭锛?.C 闂幆

- **鐢ㄦ埛纭锛?* `ai_incall.wav` 鍗充负鍙屽悜閫氳瘽褰曢煶銆?
- **鍒嗘瀽锛?* ~16s锛?8kHz s16le锛孡==R 100%锛宮ax鈮?8977銆?
- **涓嬩竴闃舵锛?* 1.D 楠ㄦ灦宸插紑锛歚daemon/pcm_recv` + HAL `connect(/data/vendor/ai_hook/pcm.sock)` 鎺ㄦ祦锛涘緟鐪熸満鑱旇皟銆?

---

<!-- 鏂版潯鐩ā鏉匡紙澶嶅埗鍒版枃鏈～鍐欙級锛?

## YYYY-MM-DD 鈥?鏍囬

- **鐩爣锛?*
- **鍋氫簡浠€涔堬細**
- **鐜拌薄 / 鏃ュ織锛?*
- **缁撹锛?*
- **鐩稿叧浠ｇ爜 / 鎻愪氦锛?*

-->
