# 米果官网

这个目录是可部署的静态官网。页面上的版本号、versionCode、SHA-256、更新日期和下载地址只从 `release-manifest.json` 读取，再生成 `dist/`。不要手改 `dist/`。

当前清单只记录一条 Android 包：`android-store-1.0.0-vc102`。它对应 Git 标签 `v1.0.0`。标签之后的源码改动不属于这条校验值。要换安装包，先把旧记录改成 `superseded` 或 `withdrawn` 并写明原因，再新增一条 `current`。同一份清单里不能有两条 `current`，也不能让旧的 versionCode 继续充当下载包。

`artifact.status` 为 `not_published` 时，`httpsUrl` 必须是 `null`，页面不会出现下载链接。改成 `published` 之前，`website/scripts/check.py` 会下载该 HTTPS 地址并核对 SHA-256。校验不一致、地址不是 HTTPS，或者返回的是网页，生成会失败。

应用商店审核进度在这份 schema 里只能是 `not_recorded`。页面和 `store-materials.txt` 都写「未记录」。

## 检查

在仓库根目录运行：

```bash
python3 website/scripts/check.py
```

检查会核对清单结构、标签日期、正式隐私页正文、外链是否返回 HTTPS 200，以及页面里没有下载假地址。通过后，`website/dist/` 与生成结果一致。若提示 dist 已重新生成，先看 diff，再跑一次检查。

## 部署

把 `website/dist/` 里面的文件放到静态站点的根目录，并用 HTTPS 对外提供。页面使用相对路径，也可以放在子目录。

`404.html` 要由托管方在找不到路径时返回。GitHub Pages 会使用这个文件名；其它托管需要自行把错误页指到它。

本仓库没有填写官网域名，也没有替任何托管打开公开访问。部署之前需要确认域名、证书和谁有权上传文件。

## 以后补上安装包

1. 准备与清单 SHA-256 完全一致的商店构建 APK。标签之后的源码不能沿用 vc102 的校验值。
2. 把文件放到真实可访问的 HTTPS 地址，文件名以 `.apk` 结尾。GitHub Release 的附件地址可以用，但必须先真正创建 Release 并传上这个文件。
3. 在 `release-manifest.json` 填写 `httpsUrl`、`fileName`，把 `status` 改为 `published`，并更新 `checkedOn` 与 `evidence`。
4. 重新运行检查。只有下载结果的 SHA-256 一致时，下载按钮才会写进 `dist/`。
5. 用新的 `dist/store-materials.txt` 替换之前抄出去的版本核对文本。

## 还要人工确认的事

- 官网对外域名和托管位置。
- 谁有权创建 GitHub Release 或上传安装包。
- 客服电话。清单里没有号码，页面写的是「未提供」。
- 是否改应用内已经在使用的隐私政策地址。本目录只做可随站部署的副本，不改 Android 代码。
