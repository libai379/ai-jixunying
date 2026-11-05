# silk：微信语音解码

微信语音是 SILK v3 格式（腾讯写法：开头 1 个字节 0x02，再 `#!SILK_V3`，后面每一帧「2 字节长度（小端）+ 数据」，每帧 20 毫秒）。
千问全模态等模型不认它，2026-11-05 实测直接发 SILK 不报错、但听成「电子蜂鸣声」「金属敲击声」；解码成 wav 再发才听对。

## 出处

- `src/main/java/org/jitsi/impl/neomedia/codec/audio/silk/` 下除 `WeixinSilk.java` 以外的 67 个文件，来自 Jitsi 的 libjitsi：
  https://github.com/jitsi/libjitsi （提交 f12d60c5892b7f5edb8c9a00e3a16db9b6111b28，路径 src/main/java/org/jitsi/impl/neomedia/codec/audio/silk/）。
  许可证 Apache License 2.0（全文见本目录 LICENSE），版权声明在每个文件开头，原样保留，文件内容未改。
  libjitsi 这个包是 Skype SILK 编解码器的纯 Java 移植，这里只收了解码要用到的文件（按类引用算出来的，编码器那一半没收）。
- `WeixinSilk.java` 是本项目写的：认微信语音文件头、按帧调用上面的解码器，输出 16 位单声道 PCM。

## 测试样本

`shared/src/desktopTest/resources/weixin-voice.silk`（7362 字节，150 帧，3 秒）是 2026-11-05 自己造的：
用代码合成 3 秒 24 kHz 的「元音」（基频 120→220 Hz 滑动、前 12 个谐波按 700 / 1200 Hz 附近加权、每秒 4 个音节起伏、1.3～1.7 秒静音，
公式和 SilkTest.kt 里的 synth() 一样），临时用同一个提交里 libjitsi 的 SILK 编码器（EncAPI 等 71 个文件，没收进仓库）
按 24000 Hz、20 毫秒一包、24 kbps、复杂度 2、不开 FEC 和 DTX 编出来，写成微信的格式。编码器跑时要关掉 Java 断言（ResidualEnergyFLP 里有一处断言在合成信号上不成立；原版 C 的 SKP_assert 只在调试版生效）。
没用网上找到的真实微信语音：那些样本的许可证不让带进仓库（比如 silk4j 是 AGPL）。

## 用法

`WeixinSilk.isSilk(bytes)`、`WeixinSilk.decode(bytes, 24000)`（返回小端 16 位 PCM 字节，不是 SILK 返回 null）。
微信语音默认 24000 Hz（腾讯官方插件 src/media/silk-transcode.ts 的 SILK_SAMPLE_RATE）。
