# GitHub + Render 部署说明

GitHub 仓库只负责存代码；要让不同网络的手机都能访问，需要把仓库部署到 Render、Railway、Fly.io 等可以运行 Web 服务的平台。

## 1. 上传到 GitHub

你的仓库地址：

```text
https://github.com/chngychng/werewolf-dealer
```

如果你已经把本 zip 解压到 `F:\judge\werewolf-dealer`，在 Windows PowerShell 或 Git Bash 中运行：

```bash
cd /f/judge/werewolf-dealer
```

如果你用的是 PowerShell 而不是 Git Bash，用：

```powershell
cd F:\judge\werewolf-dealer
```

第一次上传到已有 GitHub 仓库：

```bash
git init
git remote add origin https://github.com/chngychng/werewolf-dealer.git
git branch -M main
git add .
git commit -m "add werewolf dealer app"
git push -u origin main
```

如果提示远端已有 README，先拉一下再推：

```bash
git pull origin main --allow-unrelated-histories
git push -u origin main
```

如果提示 remote origin 已存在，用：

```bash
git remote set-url origin https://github.com/chngychng/werewolf-dealer.git
git push -u origin main
```

后续每次改完再上传：

```bash
git add .
git commit -m "update rules"
git push
```

## 2. Render 部署

1. 打开 Render。
2. New → Web Service。
3. 连接 GitHub，选择 `chngychng/werewolf-dealer`。
4. Environment / Runtime 选择 Docker，或者让 Render 自动识别仓库根目录的 `Dockerfile`。
5. Branch 选 `main`。
6. 点击 Deploy Web Service。
7. 部署成功后，Render 会给一个公网链接，例如：

```text
https://werewolf-dealer-xxxx.onrender.com
```

玩家手机打开：

```text
https://werewolf-dealer-xxxx.onrender.com/player.html
```

法官端打开：

```text
https://werewolf-dealer-xxxx.onrender.com/
```

## 3. 注意

- 这个版本的游戏状态保存在云服务内存里；服务重启后，本局会丢失。
- 免费云服务可能会休眠，第一次打开可能较慢。
- 公网部署后，知道法官端链接的人可能打开法官端；正式使用建议之后加法官密码。
