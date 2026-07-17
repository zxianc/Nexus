#!/system/bin/sh
ui_print "- 正在安装 AI 音频拦截核心..."

# 1. 在模块中创建虚拟的 bin 目录
mkdir -p $MODPATH/system/bin

# 2. 将系统原生的 audioserver 复制到我们的模块里，改名叫 audioserver_real
ui_print "- 备份并克隆原生 audioserver..."
cp /system/bin/audioserver $MODPATH/system/bin/audioserver_real

# 3. 编写代理脚本：顶替原来的 audioserver
ui_print "- 写入 LD_PRELOAD 代理脚本..."
cat < $MODPATH/system/bin/audioserver
#!/system/bin/sh
# 强制预加载我们的 Hook 库
export LD_PRELOAD=/system/lib64/libai_hook.so
# 移交执行权给真正的 audioserver
exec /system/bin/audioserver_real "\$@"
EOF

# 4. 修复极其关键的权限和 SELinux 标签 (否则系统会拒绝执行)
ui_print "- 修复执行权限与 SELinux 上下文..."
chmod 755 $MODPATH/system/bin/audioserver
chmod 755 $MODPATH/system/bin/audioserver_real

# 提取系统原本的 SELinux 标签并应用给我们的文件
SERVER_CON=$(ls -Z /system/bin/audioserver | awk '{print $1}')
chcon $SERVER_CON $MODPATH/system/bin/audioserver
chcon $SERVER_CON $MODPATH/system/bin/audioserver_real