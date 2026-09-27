package com.ice.scribe.stt;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 模型仓库：扫描、导入、删除 whisper 模型文件。
 *
 * <p>模型放在两个位置，都是应用私有目录，不需要任何存储权限：
 * <ul>
 *   <li>内部：{@code /data/data/<pkg>/files/models}</li>
 *   <li>外部：{@code /sdcard/Android/data/<pkg>/files/models} —— 可以直接用数据线拷大模型进来</li>
 * </ul>
 */
public final class ModelStore {

    private static final String TAG = "ModelStore";
    private static final String DIR = "models";
    /** 小于 1MB 的「模型」肯定是错的（最小 tiny 也有 75MB） */
    private static final long MIN_BYTES = 1024L * 1024L;

    public static final class Model {
        public final File file;
        public final String displayName;
        public final long bytes;

        Model(File file) {
            this.file = file;
            this.displayName = TranscribeEngine.displayNameOf(file.getName());
            this.bytes = file.length();
        }

        public String sizeText() {
            return humanSize(bytes);
        }
    }

    private final Context ctx;

    public ModelStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 内部私有目录，导入的模型默认放这儿。 */
    public File internalDir() {
        return new File(ctx.getFilesDir(), DIR);
    }

    /** 外部私有目录，方便用数据线拷大文件。 */
    public File externalDir() {
        File ext = ctx.getExternalFilesDir(null);
        return ext == null ? null : new File(ext, DIR);
    }

    /** 外部目录的展示路径，用来在界面上提示用户。 */
    public String externalPathHint() {
        File d = externalDir();
        return d == null ? "" : d.getAbsolutePath();
    }

    public List<File> searchDirs() {
        List<File> dirs = new ArrayList<>();
        dirs.add(internalDir());
        File ext = externalDir();
        if (ext != null) dirs.add(ext);
        return dirs;
    }

    /** 列出两个目录下所有模型，按名字排序。 */
    public List<Model> list() {
        List<Model> out = new ArrayList<>();
        for (File dir : searchDirs()) {
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && looksLikeModel(f)) {
                    out.add(new Model(f));
                }
            }
        }
        out.sort(Comparator.comparing(m -> m.displayName));
        return out;
    }

    public Model find(String path) {
        if (path == null) return null;
        for (Model m : list()) {
            if (m.file.getAbsolutePath().equals(path)) return m;
        }
        return null;
    }

    /** .bin / .gguf 都当模型；懂命名的人不会放错。 */
    private static boolean looksLikeModel(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.endsWith(".bin") || n.endsWith(".gguf");
    }

    /**
     * 从系统文件选择器导入：把内容复制进内部目录。
     *
     * @param progress 已复制的字节数回调（用于进度条），可为 null
     */
    public File importFrom(Uri uri, Progress progress) throws IOException {
        String name = queryName(uri);
        if (name == null || name.isEmpty()) name = "whisper-model.bin";
        if (!looksLikeModel(new File(name))) {
            name = name + ".bin";
        }
        File dir = internalDir();
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建模型目录");
        File dst = uniqueFile(dir, name);

        long total = querySize(uri);
        long copied = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("无法读取所选文件");
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                copied += n;
                if (progress != null) progress.onProgress(copied, total);
            }
            out.flush();
        } catch (IOException e) {
            // 复制失败就别留下半截文件，否则列表里会出现一个永远加载不了的模型
            dst.delete();
            throw e;
        }

        if (copied < MIN_BYTES) {
            dst.delete();
            throw new IOException("文件太小（" + humanSize(copied) + "），不是 whisper 模型");
        }
        if (!magicLooksBinary(dst)) {
            dst.delete();
            throw new IOException("文件内容不像模型，可能选错了文件");
        }
        Log.i(TAG, "导入完成: " + dst.getName() + " " + humanSize(copied));
        return dst;
    }

    public boolean delete(File f) {
        return f != null && f.isFile() && f.delete();
    }

    /** 模型存放位置的总占用。 */
    public long totalBytes() {
        long sum = 0;
        for (Model m : list()) sum += m.bytes;
        return sum;
    }

    public interface Progress {
        void onProgress(long copied, long total);
    }

    // ---- 工具 ----

    private String queryName(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignore) {
            // 某些 provider 不支持 query，退回按路径取名字
        }
        String last = uri.getLastPathSegment();
        if (last == null) return null;
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    private long querySize(Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignore) {
            // 拿不到大小就按未知处理，进度条改成不确定态
        }
        return -1;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    /** 模型是二进制；如果开头全是可打印字符（比如误选了 html），直接判错。 */
    private static boolean magicLooksBinary(File f) {
        byte[] head = new byte[8];
        try (FileInputStream in = new FileInputStream(f)) {
            int n = in.read(head);
            if (n <= 0) return false;
            int printable = 0;
            for (int i = 0; i < n; i++) {
                int b = head[i] & 0xff;
                if (b >= 0x20 && b < 0x7f) printable++;
            }
            return printable < n;
        } catch (IOException e) {
            return false;
        }
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    /** 给内置模型下载列表用的常用模型名（体积为近似值）。 */
    public static List<String> recommendedNames() {
        return Arrays.asList(
                "ggml-tiny.bin", "ggml-base.bin", "ggml-small.bin",
                "ggml-medium.bin", "ggml-large-v3-turbo.bin");
    }
}