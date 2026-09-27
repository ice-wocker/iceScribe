package com.ice.scribe.data;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量设置存储：上次用的模型、语言、线程数。 */
public final class Prefs {

    private static final String FILE = "icescribe";

    private static final String K_MODEL = "model_path";
    private static final String K_LANG = "lang";
    private static final String K_THREADS = "threads";
    private static final String K_TRANSLATE = "translate";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String modelPath() {
        return sp.getString(K_MODEL, null);
    }

    public void setModelPath(String path) {
        sp.edit().putString(K_MODEL, path).apply();
    }

    /** "auto" / "zh" / "en" */
    public String lang() {
        return sp.getString(K_LANG, "zh");
    }

    public void setLang(String lang) {
        sp.edit().putString(K_LANG, lang).apply();
    }

    public int threads() {
        return sp.getInt(K_THREADS, 0);
    }

    public void setThreads(int n) {
        sp.edit().putInt(K_THREADS, n).apply();
    }

    public boolean translate() {
        return sp.getBoolean(K_TRANSLATE, false);
    }

    public void setTranslate(boolean b) {
        sp.edit().putBoolean(K_TRANSLATE, b).apply();
    }
}