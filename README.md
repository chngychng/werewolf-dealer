# werewolf-dealer

狼人杀第一晚发牌/操作记录器。

## 功能

- 12/13 人模式。
- 玩家端只显示自己的身份和自己可执行的第一晚操作。
- 轮到玩家操作时，提交后自动进入下一步。
- 法官端可开局、查看身份表、查看操作记录、查看天亮结果。
- 支持 Docker 部署到云平台，部署后不同网络的手机也能打开。

## 本地运行

在 IntelliJ IDEA 中打开本项目，运行：

```text
src/com/example/werewolf/WerewolfDealer.java
```

或命令行运行：

```bash
javac -encoding UTF-8 -d out src/com/example/werewolf/WerewolfDealer.java
java -cp out com.example.werewolf.WerewolfDealer 8080
```

打开：

```text
http://localhost:8080
```

## Docker 运行

```bash
docker build -t werewolf-dealer .
docker run --rm -p 8080:8080 -e PORT=8080 werewolf-dealer
```

打开：

```text
http://localhost:8080
```

## 云部署

见 `DEPLOY.md`。

## 测试

见 `TEST_CASES.md`。
