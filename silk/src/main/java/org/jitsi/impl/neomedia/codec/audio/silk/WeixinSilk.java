package org.jitsi.impl.neomedia.codec.audio.silk;

import java.io.ByteArrayOutputStream;

/**
 * 本项目（AI集训营，癸星科技）写的，不是 libjitsi 的文件：把微信语音（SILK v3 文件）解成 PCM。
 * 放在 libjitsi 这个包里，是因为它的解码接口（DecAPI、SKP_Silk_decoder_state、SKP_SILK_SDK_DecControlStruct）只在包内可见；
 * libjitsi 的文件一个没改。出处和许可证见 silk/README.md、silk/LICENSE。
 *
 * <p>文件格式：腾讯写法开头多 1 个字节 0x02，然后「#!SILK_V3」；之后每一帧是 2 字节长度（小端，有符号）+ 那么多字节的数据。
 * 解码循环照 Skype SILK SDK 自带的 Decoder.c（kn007/silk-v3-decoder 里那份）：一包里可能有好几帧，moreInternalDecoderFrames 为 1 时同一包接着解。
 */
public final class WeixinSilk {
    private WeixinSilk() {}

    private static final byte[] MAGIC = {'#', '!', 'S', 'I', 'L', 'K', '_', 'V', '3'};

    /** 一包最多几帧（Decoder.c 的 MAX_INPUT_FRAMES 是 5；多出来说明码流坏了）。 */
    private static final int MAX_FRAMES_PER_PACKET = 5;

    /** 数据从哪里开始：腾讯写法（0x02 + #!SILK_V3）返回 10，标准写法返回 9；不是 SILK 返回 -1。 */
    public static int headerLength(byte[] data) {
        if (data == null) return -1;
        if (startsWith(data, 0)) return MAGIC.length;
        if (data.length > 0 && data[0] == 0x02 && startsWith(data, 1)) return MAGIC.length + 1;
        return -1;
    }

    public static boolean isSilk(byte[] data) {
        return headerLength(data) > 0;
    }

    private static boolean startsWith(byte[] data, int at) {
        if (data.length < at + MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (data[at + i] != MAGIC[i]) return false;
        return true;
    }

    /**
     * 解成小端 16 位单声道 PCM。sampleRate 是想要的输出采样率（8000～48000，微信语音用 24000）。
     * 不是 SILK 返回 null；中途遇到坏帧（长度不对）就停在那里，返回已经解出来的部分。
     */
    public static byte[] decode(byte[] data, int sampleRate) {
        int p = headerLength(data);
        if (p < 0) return null;
        SKP_Silk_decoder_state state = new SKP_Silk_decoder_state();
        if (DecAPI.SKP_Silk_SDK_InitDecoder(state) != 0) return null;
        SKP_SILK_SDK_DecControlStruct control = new SKP_SILK_SDK_DecControlStruct();
        control.API_sampleRate = sampleRate;
        // 一次解 20 毫秒；输出采样率最高 48 kHz，一帧最多 960 个采样
        short[] frame = new short[Define.MAX_API_FS_KHZ * Define.FRAME_LENGTH_MS];
        short[] count = new short[1];
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, data.length * 8));
        byte[] le = new byte[frame.length * 2];
        while (p + 2 <= data.length) {
            // 长度是有符号的 16 位：负数（0x8000 以上，含结尾标记 0xFFFF）就是结束了
            int len = (short) ((data[p] & 0xff) | ((data[p + 1] & 0xff) << 8));
            p += 2;
            if (len <= 0 || p + len > data.length) break;
            int frames = 0;
            do {
                count[0] = (short) frame.length;
                DecAPI.SKP_Silk_SDK_Decode(state, control, 0, data, p, len, frame, 0, count);
                int n = Math.max(0, Math.min(count[0], frame.length));
                for (int i = 0; i < n; i++) {
                    le[2 * i] = (byte) frame[i];
                    le[2 * i + 1] = (byte) (frame[i] >> 8);
                }
                out.write(le, 0, n * 2);
                frames++;
            } while (control.moreInternalDecoderFrames != 0 && frames < MAX_FRAMES_PER_PACKET);
            p += len;
        }
        return out.toByteArray();
    }
}
