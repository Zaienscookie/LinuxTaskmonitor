# Linux 远程性能监控仪表盘 (Java)

通过 SSH 远程查看 Linux 服务器的 CPU / 内存 / 磁盘 / 网络 / 进程等实时性能，带深色仪表盘界面。

## 环境要求
- JDK 17+（本机为 JDK 21）
- 无需 Maven，依赖已下载到 `lib/`

## 运行
双击 `run.bat`，或在命令行执行：
```
java -cp "lib\jsch-0.1.55.jar;lib\gson-2.10.1.jar;target\classes" com.linuxmonitor.Main
```

## 配置
先点击「设置」填写 Linux 服务器信息，或直接编辑 `config.json`：
```json
{
  "host": "192.168.1.100",
  "port": 22,
  "username": "root",
  "password": "",
  "key_file": "",
  "use_key": false,
  "refresh_interval": 2
}
```
支持密码或密钥（勾选"使用密钥登录"并填写密钥文件路径）登录。

## 功能
- CPU 使用率 + 负载平均值仪表盘
- 内存 / Swap 使用情况
- 磁盘各挂载点占用
- 网络实时上行 / 下行速度
- Top 15 进程（按 CPU 排序）
- 运行时长、进程数等系统信息

## 重新编译
```
javac -cp "lib\jsch-0.1.55.jar;lib\gson-2.10.1.jar" -d target\classes -encoding UTF-8 src\main\java\com\linuxmonitor\*.java
```

## 项目结构
```
LinuxTaskmonitor/
├── run.bat                    # 启动脚本
├── config.json               # 服务器配置
├── lib/                      # 依赖库 (JSch, Gson)
├── target/classes/           # 编译产物
└── src/main/java/com/linuxmonitor/
    ├── Main.java             # 入口 + 配置加载
    ├── SSHClient.java        # SSH 连接管理
    ├── SystemData.java       # 数据模型
    ├── LinuxMonitor.java     # 数据采集与解析
    └── Dashboard.java        # Swing 仪表盘界面
```

> Last updated: 2026-08-18
