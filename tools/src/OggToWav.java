import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Converts Ogg Vorbis assets to 16-bit PCM WAV.
 *
 * <p>The iOS audio backend (ObjectAL / AVAudioPlayer) cannot decode Ogg Vorbis
 * while the Android backend could, so every {@code .ogg} shipped in
 * {@code assets/Audio} has to be re-encoded. Instead of adding an external
 * encoder dependency we drive the JOrbis decoder that already ships inside the
 * game, keeping the port pure Java.
 *
 * <p>Decoding follows the canonical JOrbis three-stage loop
 * ({@link SyncState} pages -> {@link StreamState} packets -> {@link DspState}
 * samples), which is what {@code com.jcraft.jorbis.DecodeExample} demonstrates.
 *
 * <p>Usage: {@code java OggToWav <inputDir> <outputDir>}
 */
public final class OggToWav {

    private static final int READ_CHUNK = 4096;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: OggToWav <inputDir> <outputDir>");
            System.exit(2);
        }
        File inRoot = new File(args[0]);
        File outRoot = new File(args[1]);
        if (!inRoot.isDirectory()) {
            System.err.println("not a directory: " + inRoot);
            System.exit(2);
        }

        List<File> sources = collect(inRoot);
        int converted = 0;
        int failed = 0;
        long bytesOut = 0L;
        long started = System.currentTimeMillis();

        for (File source : sources) {
            String rel = relativize(inRoot, source);
            File target = new File(outRoot, rel.substring(0, rel.length() - 4) + ".wav");
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                System.err.println("cannot create " + parent);
                failed++;
                continue;
            }
            try {
                int written = convert(source, target);
                bytesOut += written;
                converted++;
                System.out.println("ok   " + rel + " -> " + written + " B");
            } catch (Exception e) {
                System.err.println("FAIL " + rel + " : " + e);
                failed++;
            }
        }

        long ms = System.currentTimeMillis() - started;
        System.out.println("done: converted=" + converted + " failed=" + failed
                + " bytes=" + bytesOut + " ms=" + ms);
    }

    private static List<File> collect(File root) {
        List<File> out = new ArrayList<File>();
        File[] children = root.listFiles();
        if (children == null) {
            return out;
        }
        Arrays.sort(children);
        for (File child : children) {
            if (child.isDirectory()) {
                out.addAll(collect(child));
            } else if (child.getName().toLowerCase().endsWith(".ogg")) {
                out.add(child);
            }
        }
        return out;
    }

    private static String relativize(File root, File file) {
        String base = root.getAbsolutePath();
        String full = file.getAbsolutePath();
        if (full.startsWith(base)) {
            String rel = full.substring(base.length());
            if (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            return rel.replace(File.separatorChar, '/');
        }
        return file.getName();
    }

    /** Decodes one Ogg Vorbis file and writes it as a PCM WAV. */
    private static int convert(File source, File target) throws Exception {
        InputStream in = new FileInputStream(source);
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        int channels;
        int rate;
        try {
            SyncState oy = new SyncState();
            StreamState os = new StreamState();
            Page og = new Page();
            Packet op = new Packet();
            Info vi = new Info();
            Comment vc = new Comment();
            DspState vd = new DspState();
            Block vb = new Block(vd);

            oy.init();

            // SyncState.buffer(n) allocates its backing array, so it must be
            // called before oy.data is touched.
            int offset = oy.buffer(READ_CHUNK);
            int read = in.read(oy.data, offset, READ_CHUNK);
            oy.wrote(read);
            if (oy.pageout(og) != 1) {
                throw new IllegalStateException("not an Ogg bitstream");
            }
            os.init(og.serialno());
            vi.init();
            vc.init();
            if (os.pagein(og) < 0) {
                throw new IllegalStateException("bad first page");
            }
            if (os.packetout(op) != 1) {
                throw new IllegalStateException("no initial header");
            }
            if (vi.synthesis_headerin(vc, op) < 0) {
                throw new IllegalStateException("not Vorbis audio");
            }

            int headers = 0;
            while (headers < 2) {
                int result = oy.pageout(og);
                if (result == 0) {
                    offset = oy.buffer(READ_CHUNK);
                    read = in.read(oy.data, offset, READ_CHUNK);
                    oy.wrote(read);
                    if (read == 0 && headers < 2) {
                        throw new IllegalStateException("EOF before all headers");
                    }
                    continue;
                }
                if (result == 1) {
                    os.pagein(og);
                    while (headers < 2) {
                        int packet = os.packetout(op);
                        if (packet == 0) {
                            break;
                        }
                        if (packet == -1) {
                            throw new IllegalStateException("corrupt secondary header");
                        }
                        vi.synthesis_headerin(vc, op);
                        headers++;
                    }
                }
            }

            channels = vi.channels;
            rate = vi.rate;

            vd.synthesis_init(vi);
            vb.init(vd);

            float[][][] pcmOut = new float[1][][];
            int[] pcmOffsets = new int[channels];
            int convSize = READ_CHUNK / channels;
            byte[] convBuffer = new byte[convSize * channels * 2];

            boolean eos = false;
            while (!eos) {
                int result = oy.pageout(og);
                if (result == 0) {
                    offset = oy.buffer(READ_CHUNK);
                    read = in.read(oy.data, offset, READ_CHUNK);
                    oy.wrote(read);
                    if (read == 0) {
                        break;
                    }
                    continue;
                }
                if (result == -1) {
                    continue;
                }
                os.pagein(og);
                while (true) {
                    int packet = os.packetout(op);
                    if (packet == 0) {
                        if (og.eos() != 0) {
                            eos = true;
                        }
                        break;
                    }
                    if (packet == -1) {
                        continue;
                    }
                    if (vb.synthesis(op) == 0) {
                        vd.synthesis_blockin(vb);
                    }
                    while (true) {
                        int samples = vd.synthesis_pcmout(pcmOut, pcmOffsets);
                        if (samples <= 0) {
                            break;
                        }
                        float[][] channelData = pcmOut[0];
                        if (samples > convSize) {
                            samples = convSize;
                        }
                        for (int ch = 0; ch < channels; ch++) {
                            int src = pcmOffsets[ch];
                            int dst = ch * 2;
                            for (int i = 0; i < samples; i++) {
                                int value = (int) ((double) channelData[ch][src + i] * 32767.0);
                                if (value > 32767) {
                                    value = 32767;
                                }
                                if (value < -32768) {
                                    value = -32768;
                                }
                                if (value < 0) {
                                    value |= 32768;
                                }
                                convBuffer[dst] = (byte) value;
                                convBuffer[dst + 1] = (byte) (value >>> 8);
                                dst += channels * 2;
                            }
                        }
                        pcm.write(convBuffer, 0, channels * 2 * samples);
                        vd.synthesis_read(samples);
                    }
                }
            }

            os.clear();
            vb.clear();
            vd.clear();
            vi.clear();
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // best effort while unwinding
            }
        }

        byte[] samples = pcm.toByteArray();
        FileOutputStream out = new FileOutputStream(target);
        try {
            out.write(header(samples.length, channels, rate));
            out.write(samples);
        } finally {
            out.close();
        }
        return samples.length + 44;
    }

    private static byte[] header(int dataLength, int channels, int rate) {
        int byteRate = rate * channels * 2;
        byte[] h = new byte[44];
        putAscii(h, 0, "RIFF");
        putInt(h, 4, 36 + dataLength);
        putAscii(h, 8, "WAVE");
        putAscii(h, 12, "fmt ");
        putInt(h, 16, 16);
        putShort(h, 20, 1);            // PCM
        putShort(h, 22, channels);
        putInt(h, 24, rate);
        putInt(h, 28, byteRate);
        putShort(h, 32, channels * 2); // block align
        putShort(h, 34, 16);           // bits per sample
        putAscii(h, 36, "data");
        putInt(h, 40, dataLength);
        return h;
    }

    private static void putAscii(byte[] dst, int offset, String value) {
        for (int i = 0; i < value.length(); i++) {
            dst[offset + i] = (byte) value.charAt(i);
        }
    }

    private static void putInt(byte[] dst, int offset, int value) {
        dst[offset] = (byte) (value & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        dst[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        dst[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void putShort(byte[] dst, int offset, int value) {
        dst[offset] = (byte) (value & 0xFF);
        dst[offset + 1] = (byte) ((value >>> 8) & 0xFF);
    }

    private OggToWav() {
    }
}
