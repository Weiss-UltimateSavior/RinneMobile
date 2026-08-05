package com.core.agent.workspace;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 工作区文本编码探测与编解码 helper（重构计划 4.5 GameWorkspaceGateway 拆分，阶段 130）。
 *
 * 自 GameWorkspaceGateway 抽取的高内聚编码工具：BOM/候选编码探测、decode/encode
 * 无损往返判定。行为与迁移前逐字等价；Log tag 沿用原类名保持日志连续性。
 */
final class WorkspaceEncoding {
    private static final String TAG = "GameWorkspaceGateway";

    private WorkspaceEncoding() { }

    static final class Decoded {
        final String text;
        final String encoding;
        Decoded(String text, String encoding) {
            this.text = text;
            this.encoding = encoding;
        }
    }

    static final class EncodingCandidate {
        final String encoding;
        final double confidence;
        final int languageScore;
        EncodingCandidate(String encoding, double confidence, int languageScore) {
            this.encoding = encoding;
            this.confidence = confidence;
            this.languageScore = languageScore;
        }
    }

    static String detectBom(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) return "utf-8-bom";
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) return "utf-16le-bom";
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) return "utf-16be-bom";
        return null;
    }

    static List<EncodingCandidate> encodingCandidates(byte[] bytes) {
        List<EncodingCandidate> candidates = new ArrayList<>();
        boolean ascii = true;
        for (byte value : bytes) if ((value & 0x80) != 0) { ascii = false; break; }
        try {
            String utf8 = decode(bytes, "utf-8").text;
            candidates.add(new EncodingCandidate("utf-8", ascii ? 0.90 : 0.99, languageScore(utf8)));
        } catch (Exception e) { Log.w(TAG, "diagnostic-utf8-decode-failed", e); }
        int evenZero = 0, oddZero = 0;
        for (int i = 0; i < bytes.length; i++) if (bytes[i] == 0) { if ((i & 1) == 0) evenZero++; else oddZero++; }
        if (evenZero > bytes.length / 8 || oddZero > bytes.length / 8) {
            addEncodingCandidate(candidates, bytes, oddZero >= evenZero ? "utf-16le" : "utf-16be", 0.88);
        }
        if (!ascii) {
            addEncodingCandidate(candidates, bytes, "gb18030", 0.62);
            addEncodingCandidate(candidates, bytes, "shift_jis", 0.60);
        }
        candidates.sort((a, b) -> {
            int confidence = Double.compare(b.confidence, a.confidence);
            return confidence != 0 ? confidence : Integer.compare(b.languageScore, a.languageScore);
        });
        return candidates;
    }

    private static void addEncodingCandidate(List<EncodingCandidate> values, byte[] bytes,
                                             String encoding, double baseConfidence) {
        try {
            String text = decode(bytes, encoding).text;
            int score = languageScore(text);
            double confidence = Math.min(0.89, baseConfidence + Math.min(0.20, score / 1000.0));
            values.add(new EncodingCandidate(encoding, confidence, score));
        } catch (Exception e) { Log.w(TAG, "diagnostic-encoding-candidate-failed", e); }
    }

    private static int languageScore(String text) {
        int score = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3040' && c <= '\u30FF')) score += 3;
            else if (c == '\n' || c == '\r' || c == '\t' || !Character.isISOControl(c)) score++;
            else score -= 20;
        }
        return score;
    }

    static boolean looksBinary(byte[] bytes) {
        if (bytes.length == 0) return false;
        int controls = 0, zeros = 0;
        for (byte value : bytes) {
            int c = value & 0xff;
            if (c == 0) zeros++;
            if ((c < 0x09 || (c > 0x0D && c < 0x20)) && c != 0) controls++;
        }
        boolean utf16Pattern = zeros > bytes.length / 8;
        return !utf16Pattern && controls > Math.max(2, bytes.length / 20);
    }

    static byte[] encode(String text, String encoding) throws CharacterCodingException {
        Charset charset;
        byte[] bom = new byte[0];
        if ("utf-8-bom".equals(encoding)) { charset = StandardCharsets.UTF_8; bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; }
        else if ("utf-16le-bom".equals(encoding)) { charset = StandardCharsets.UTF_16LE; bom = new byte[]{(byte) 0xFF, (byte) 0xFE}; }
        else if ("utf-16be-bom".equals(encoding)) { charset = StandardCharsets.UTF_16BE; bom = new byte[]{(byte) 0xFE, (byte) 0xFF}; }
        else if ("gb18030".equals(encoding)) charset = Charset.forName("GB18030");
        else if ("shift_jis".equals(encoding)) charset = Charset.forName("Shift_JIS");
        else if ("utf-16le".equals(encoding)) charset = StandardCharsets.UTF_16LE;
        else if ("utf-16be".equals(encoding)) charset = StandardCharsets.UTF_16BE;
        else charset = StandardCharsets.UTF_8;
        CharsetEncoder encoder = charset.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = encoder.encode(CharBuffer.wrap(text));
        byte[] body = new byte[encoded.remaining()];
        encoded.get(body);
        byte[] result = Arrays.copyOf(bom, bom.length + body.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    static Decoded decode(byte[] bytes, String requested) throws CharacterCodingException {
        String mode = requested == null ? "auto" : requested.toLowerCase(Locale.ROOT);
        int offset = 0;
        Charset charset;
        String label;
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            charset = StandardCharsets.UTF_8; label = "utf-8-bom"; offset = 3;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            charset = StandardCharsets.UTF_16LE; label = "utf-16le-bom"; offset = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            charset = StandardCharsets.UTF_16BE; label = "utf-16be-bom"; offset = 2;
        } else {
            if ("auto".equals(mode) || "utf-8".equals(mode)) { charset = StandardCharsets.UTF_8; label = "utf-8"; }
            else if ("gb18030".equals(mode)) { charset = Charset.forName("GB18030"); label = "gb18030"; }
            else if ("shift_jis".equals(mode)) { charset = Charset.forName("Shift_JIS"); label = "shift_jis"; }
            else if ("utf-16le".equals(mode)) { charset = StandardCharsets.UTF_16LE; label = "utf-16le"; }
            else if ("utf-16be".equals(mode)) { charset = StandardCharsets.UTF_16BE; label = "utf-16be"; }
            else throw new IllegalArgumentException("不支持的 encoding");
        }
        CharBuffer chars = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset));
        return new Decoded(chars.toString(), label);
    }
}
