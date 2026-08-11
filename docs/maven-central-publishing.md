# Maven Central 发布指南

Firefly 的公共 Java 构件使用 `io.github.fishered` 命名空间。普通使用者只需要引入：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.fishered</groupId>
            <artifactId>firefly-bom</artifactId>
            <version>1.0.7</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>io.github.fishered</groupId>
    <artifactId>firefly-spring-boot-starter</artifactId>
</dependency>
```

发布流程会同时发布全部公共 Java 构件：

- `firefly-bom`
- `scheduler-core`
- `plugin-api`
- `netty-protocol`
- `netty`
- `executor-netty`
- `firefly-remote-adapter`
- `firefly-spring-boot-autoconfigure`
- `firefly-spring-boot-starter`

## 1. 注册 Central Portal

1. 登录 <https://central.sonatype.com/>。
2. 使用拥有 `fishered` 仓库的 GitHub 账号注册。
3. 在 Namespaces 中申请并验证 `io.github.fishered`。
4. 在账号设置中生成 User Token。Portal 返回的 username 和 password 都是发布凭据，不是 GitHub 密码。

## 2. 创建 GPG 密钥

安装 GnuPG 后执行：

```powershell
gpg --full-generate-key
gpg --list-secret-keys --keyid-format LONG
```

建议使用 RSA 4096、合理的有效期，并为私钥设置密码。记录输出中的长 Key ID，然后把公钥分发到公共 Key Server：

```powershell
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
```

导出公钥和 ASCII 私钥备份。使用 GPG 的 `--output` 直接写文件，避免 Windows PowerShell 重定向改变文件编码；私钥文件不要放进 Git 仓库：

```powershell
gpg --armor --output "C:\secure\firefly-public-key.asc" --export <KEY_ID>
gpg --armor --output "C:\secure\firefly-private-key.asc" --export-secret-keys <KEY_ID>
```

## 3. 本地验证发布内容

该命令不会上传任何内容，也不需要 Central 凭据：

```powershell
.\gradlew.bat --no-parallel --no-configuration-cache verifyMavenCentralPublication
```

它会运行测试，并为所有公共 Java 模块生成 POM、`sources.jar` 和 `javadoc.jar`，同时为 `firefly-bom` 生成并检查 Maven BOM POM。

本地 Maven 仓库发布仍然使用：

```powershell
.\gradlew.bat "-Pfirefly.maven.local.repo=E:/m2/repository" publishToFireflyMavenLocal
```

## 4. 首次手动上传

先在当前 PowerShell 会话设置临时环境变量：

```powershell
$env:ORG_GRADLE_PROJECT_mavenCentralUsername="<PORTAL_TOKEN_USERNAME>"
$env:ORG_GRADLE_PROJECT_mavenCentralPassword="<PORTAL_TOKEN_PASSWORD>"
$env:ORG_GRADLE_PROJECT_signingInMemoryKey=Get-Content -LiteralPath "C:\secure\firefly-private-key.asc" -Raw
$env:ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="<GPG_PASSWORD>"
```

上传但不自动发布：

```powershell
.\gradlew.bat --no-parallel --no-configuration-cache publishAllPublicationsToMavenCentralRepository
```

然后进入 Central Portal 的 Deployments 页面，等待验证完成。第一次建议人工检查九个公共构件、POM、签名，以及 Java Library 构件的 Sources 和 Javadoc 后再点击 Publish。

确认整个流程稳定后，可以直接上传并自动发布：

```powershell
.\gradlew.bat --no-parallel --no-configuration-cache publishAndReleaseToMavenCentral
```

## 5. 配置 GitHub Actions

在 GitHub 仓库的 `Settings > Environments` 中创建 `maven-central` 环境，建议启用 Required reviewers。然后在该环境中添加：

| Secret | 内容 |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal Token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal Token password |
| `MAVEN_SIGNING_KEY` | `firefly-private-key.asc` 的完整文本 |
| `MAVEN_SIGNING_PASSWORD` | GPG 私钥密码 |

正式发布步骤：

```powershell
git tag -a v1.0.1 -m "Release 1.0.1"
git push origin v1.0.1
```

在 GitHub Actions 中选择 `publish-maven-central`，从 `v1.0.1` Tag 运行，并输入版本 `1.0.1`。工作流会同时校验 Tag、Gradle 版本、Secrets、测试和发布构件，校验通过后才会自动 Release。

## 6. 发布后的检查

Central 正式版本不可覆盖或删除。发布后检查：

```text
https://central.sonatype.com/artifact/io.github.fishered/firefly-spring-boot-starter/1.0.1
```

在一个空 Maven 项目中仅引入 Starter，并执行：

```powershell
mvn -U dependency:tree
```

确认 Firefly Starter 及其传递模块都来自 Maven Central，并验证 `firefly-bom` 可以管理所有公共构件版本。任何修复都必须使用新版本。
