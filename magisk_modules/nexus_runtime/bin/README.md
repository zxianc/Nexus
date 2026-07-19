# Place arm64 binaries here before packing the Magisk zip:
#
# 必需（现行 engine 路径）:
#   ai_call
#   nexus_engine
#   nexus_webui                  # 本机配置页 http://127.0.0.1:8787
#   nexus_callpolicy             # 双卡来电策略（human/ai/reject）
#
# 必需（放在 ../lib/）:
#   libonnxruntime.so
#
# 可选 CLI 回退（也放本目录 bin/）:
#   sherpa-onnx-offline          # STT CLI，-backend sherpa
#   sherpa-onnx-offline-tts      # TTS CLI，无 engine 时 -say
#
# 注意：模型权重不在本模块，在 nexus_models 的 models/ 下。
#
# 真机调试树对应关系:
#   /data/local/tmp/ai_call                      → bin/ai_call
#   /data/local/tmp/nexus_stt/nexus_engine       → bin/nexus_engine
#   daemon/nexus_webui/nexus_webui_arm64         → bin/nexus_webui
#   /data/local/tmp/nexus_stt/libonnxruntime.so → lib/libonnxruntime.so
#   /data/local/tmp/nexus_stt/sherpa-onnx-*     → bin/sherpa-onnx-*
