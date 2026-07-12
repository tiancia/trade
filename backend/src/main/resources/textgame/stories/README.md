# 内置文字游戏剧情

本目录保存随应用打包的剧情 JSON。剧情内容必须通过 `TextGameStoryValidator` 校验，版本发布后由会话服务读取数据库中的快照，而不是直接读取此目录。

当前 `TextGameSeedImporter` 只显式导入 `100-days-comeback.v1.json`，并在数据库不存在版本 1 时创建已发布版本。把新 JSON 放进本目录不会自动生效；新增内置剧情时还需：

1. 使用稳定且唯一的 `storyKey`；
2. 按 `<story-key>.v<version>.json` 命名；
3. 增加校验测试；
4. 在 Seed Importer 中显式注册导入规则，或通过管理 API 创建并发布版本。

线上剧情修改应优先通过管理 API 的草稿、校验和发布流程，避免直接替换已经发布的 classpath 文件。
