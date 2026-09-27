package com.ice.scribe.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 转写历史：落盘成一个小 JSON 文件，最多保留 {@value #MAX} 条。 */
public final class HistoryStore {

    private static final String TAG = "HistoryStore";
    private static final String FILE = "history.json";
    private static final int MAX = 200;

    public static final class Item {
        public String id;
        public String text;
        public long timeMs;
        public long durationMs;
        public String wavPath;
        public String modelName;
        public String lang;

        public String timeText() {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(timeMs));
        }

        public String durationText() {
            long s = durationMs / 1000;
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        }

        public String summary() {
            if (text == null) return "";
            String t = text.replace('\n', ' ').trim();
            return t.length() > 80 ? t.substring(0, 80) + "…" : t;
        }
    }

    private final Context ctx;
    private final List<Item> items = new ArrayList<>();

    public HistoryStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        load();
    }

    private File file() {
        return new File(ctx.getFilesDir(), FILE);
    }

    public List<Item> all() {
        return new ArrayList<>(items);
    }

    public int size() {
        return items.size();
    }

    public void add(Item it) {
        if (it.id == null) it.id = String.valueOf(System.currentTimeMillis());
        items.add(0, it);
        while (items.size() > MAX) items.remove(items.size() - 1);
        save();
    }

    /** 同 id 就替换，否则新增。替换不会删音频文件。 */
    public void update(Item it) {
        if (it.id != null) {
            for (int i = 0; i < items.size(); i++) {
                if (it.id.equals(items.get(i).id)) {
                    items.set(i, it);
                    save();
                    return;
                }
            }
        }
        add(it);
    }

    public void remove(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).id)) {
                deleteAudio(items.get(i));
                items.remove(i);
                break;
            }
        }
        save();
    }

    public void clear() {
        for (Item it : items) deleteAudio(it);
        items.clear();
        save();
    }

    private void deleteAudio(Item it) {
        if (it.wavPath == null) return;
        try {
            File f = new File(it.wavPath);
            // 只删自己录音目录里的文件，避免误删用户导入的原始音频
            if (f.getAbsolutePath().contains("/recordings/")) f.delete();
        } catch (Exception ignore) {
            // 删不掉也没关系
        }
    }

    private void load() {
        File f = file();
        if (!f.isFile()) return;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            JSONArray arr = new JSONArray(new String(buf, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.id = o.optString("id");
                it.text = o.optString("text");
                it.timeMs = o.optLong("time");
                it.durationMs = o.optLong("dur");
                it.wavPath = o.isNull("wav") ? null : o.optString("wav");
                it.modelName = o.optString("model");
                it.lang = o.optString("lang");
                items.add(it);
            }
            Log.i(TAG, "读到 " + items.size() + " 条历史");
        } catch (Exception e) {
            Log.e(TAG, "历史读取失败，忽略", e);
        }
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", it.id);
                o.put("text", it.text == null ? "" : it.text);
                o.put("time", it.timeMs);
                o.put("dur", it.durationMs);
                o.put("wav", it.wavPath == null ? JSONObject.NULL : it.wavPath);
                o.put("model", it.modelName == null ? "" : it.modelName);
                o.put("lang", it.lang == null ? "" : it.lang);
            } catch (Exception ignore) {
                // put 不会失败，这里只是让编译器闭嘴
            }
            arr.put(o);
        }
        File f = file();
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            Log.e(TAG, "历史写入失败", e);
            return;
        }
        // 先写临时文件再改名，避免写一半被系统杀掉导致记录全丢
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }
}