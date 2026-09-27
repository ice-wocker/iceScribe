package com.ice.scribe;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.ice.scribe.audio.AudioImport;
import com.ice.scribe.audio.Recorder;
import com.ice.scribe.audio.WavFile;
import com.ice.scribe.data.HistoryStore;
import com.ice.scribe.data.Prefs;
import com.ice.scribe.rec.RecordService;
import com.ice.scribe.stt.ModelStore;
import com.ice.scribe.stt.TranscribeCallback;
import com.ice.scribe.stt.TranscribeEngine;
import com.ice.scribe.ui.ModelDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：录音 → 离线转写 → 编辑 / 复制 / 分享 / 存档。
 *
 * <p>整条链路都在本机完成，不联网、不上传。
 */
public final class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_RECORD_PERM = 100;
    private static final int REQ_IMPORT_AUDIO = 101;
    private static final int REQ_IMPORT_MODEL = 102;

    private static final String[] LANG_VALUES = {"zh", "auto", "en"};
    private static final int[] THREAD_OPTIONS = {2, 3, 4, 6, 8};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Prefs prefs;
    private ModelStore models;
    private HistoryStore history;
    private TranscribeEngine engine;
    private Recorder recorder;

    private TextView tvSub;
    private TextView tvStatus;
    private TextView tvTimer;
    private TextView tvEngine;
    private TextView tvCount;
    private TextView tvBottom;
    private TextView tvHistoryEmpty;
    private EditText etResult;
    private ImageButton btnRecord;
    private Button btnStop;
    private Button btnImport;
    private Button btnModel;
    private Button btnCopy;
    private Button btnShare;
    private Button btnSave;
    private Button btnClearText;
    private Button btnClearHistory;
    private LinearLayout llHistory;
    private ProgressBar pb;
    private Spinner spLang;
    private Spinner spThreads;
    private Switch swTranslate;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (recorder != null && recorder.isRecording()) {
                tvTimer.setText(formatMs(recorder.elapsedMs()));
                ui.postDelayed(this, 200);
            }
        }
    };

    /** 当前转写结果对应的历史记录 id，用于「保存」时覆盖同一条。 */
    private String lastSavedId;
    /** 最近一次音频时长，写进历史用。 */
    private long lastDurationMs;
    private boolean ignoreSpinnerCallbacks;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        models = new ModelStore(this);
        history = new HistoryStore(this);
        engine = new TranscribeEngine();
        bindViews();
        applyInsets();
        setupSpinners();
        setupActions();
        renderHistory();
        restoreModel();
        tvEngine.setText(getString(R.string.engine_version, TranscribeEngine.engineVersion()));
        tvBottom.setText(engine.isReady()
                ? getString(R.string.model_loaded) + " · " + engine.modelName()
                : getString(R.string.model_hint));
    }

    // ---- 视图 ----

    private void bindViews() {
        tvSub = findViewById(R.id.tvSub);
        tvStatus = findViewById(R.id.tvStatus);
        tvTimer = findViewById(R.id.tvTimer);
        tvEngine = findViewById(R.id.tvEngine);
        tvCount = findViewById(R.id.tvCount);
        tvBottom = findViewById(R.id.tvBottom);
        tvHistoryEmpty = findViewById(R.id.tvHistoryEmpty);
        etResult = findViewById(R.id.etResult);
        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        btnImport = findViewById(R.id.btnImport);
        btnModel = findViewById(R.id.btnModel);
        btnCopy = findViewById(R.id.btnCopy);
        btnShare = findViewById(R.id.btnShare);
        btnSave = findViewById(R.id.btnSave);
        btnClearText = findViewById(R.id.btnClearText);
        btnClearHistory = findViewById(R.id.btnClearHistory);
        llHistory = findViewById(R.id.llHistory);
        pb = findViewById(R.id.pb);
        spLang = findViewById(R.id.spLang);
        spThreads = findViewById(R.id.spThreads);
        swTranslate = findViewById(R.id.swTranslate);
    }

    /** 全面屏下把内容避开状态栏和导航栏。 */
    private void applyInsets() {
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = 0, bottom = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void setupSpinners() {
        ignoreSpinnerCallbacks = true;

        String[] langLabels = {getString(R.string.opt_lang_zh), getString(R.string.opt_lang_auto),
                getString(R.string.opt_lang_en)};
        spLang.setAdapter(pillAdapter(langLabels));
        String saved = prefs.lang();
        int li = 0;
        for (int i = 0; i < LANG_VALUES.length; i++) if (LANG_VALUES[i].equals(saved)) li = i;
        spLang.setSelection(li);

        List<String> threadLabels = new ArrayList<>();
        for (int n : THREAD_OPTIONS) threadLabels.add(n + " 线程");
        spThreads.setAdapter(pillAdapter(threadLabels.toArray(new String[0])));
        int cores = Runtime.getRuntime().availableProcessors();
        int def = prefs.threads() > 0 ? prefs.threads() : defaultThreads(cores);
        int ti = 0;
        for (int i = 0; i < THREAD_OPTIONS.length; i++) if (THREAD_OPTIONS[i] == def) ti = i;
        spThreads.setSelection(ti);

        swTranslate.setChecked(prefs.translate());
        ignoreSpinnerCallbacks = false;

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (ignoreSpinnerCallbacks) return;
                prefs.setLang(LANG_VALUES[spLang.getSelectedItemPosition()]);
                prefs.setThreads(THREAD_OPTIONS[spThreads.getSelectedItemPosition()]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spLang.setOnItemSelectedListener(l);
        spThreads.setOnItemSelectedListener(l);
        swTranslate.setOnCheckedChangeListener((b, checked) ->
                prefs.setTranslate(checked));
    }

    /** 手机上 4 线程通常最快；核多的机型适当加一点。 */
    private static int defaultThreads(int cores) {
        if (cores >= 8) return 6;
        if (cores >= 6) return 4;
        return Math.max(2, cores - 1);
    }

    private ArrayAdapter<String> pillAdapter(String[] labels) {
        return new ArrayAdapter<String>(this, R.layout.item_spinner, labels) {
            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = LayoutInflater.from(MainActivity.this)
                        .inflate(R.layout.item_spinner_dropdown, parent, false);
                ((TextView) v.findViewById(R.id.tvSpinText)).setText(getItem(position));
                return v;
            }
        };
    }

    private void setupActions() {
        btnRecord.setOnClickListener(v -> onRecordClicked());
        btnStop.setOnClickListener(v -> stopRecordingAndTranscribe());
        btnImport.setOnClickListener(v -> pickAudio());
        btnModel.setOnClickListener(v -> showModelDialog());
        btnCopy.setOnClickListener(v -> copyResult());
        btnShare.setOnClickListener(v -> shareResult());
        btnSave.setOnClickListener(v -> saveResult(true));
        btnClearText.setOnClickListener(v -> {
            etResult.setText("");
            lastSavedId = null;
            updateCount();
        });
        btnClearHistory.setOnClickListener(v -> confirmClearHistory());
    }

    // ---- 模型 ----

    private void restoreModel() {
        String path = prefs.modelPath();
        if (path == null) return;
        if (!new File(path).isFile()) {
            prefs.setModelPath(null);
            return;
        }
        loadModel(path);
    }

    private void loadModel(String path) {
        tvBottom.setText(R.string.model_loading);
        pb.setVisibility(View.VISIBLE);
        pb.setIndeterminate(true);
        engine.load(path, new TranscribeEngine.LoadCallback() {
            @Override
            public void onLoaded(String modelName) {
                prefs.setModelPath(engine.modelPath());
                pb.setVisibility(View.GONE);
                btnModel.setText(R.string.model_switch);
                tvBottom.setText(getString(R.string.model_loaded) + " · " + modelName);
                Toast.makeText(MainActivity.this, getString(R.string.model_loaded) + "：" + modelName,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onFailed(String message) {
                pb.setVisibility(View.GONE);
                tvBottom.setText(getString(R.string.model_failed) + "：" + message);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showModelDialog() {
        ModelDialog.show(this, models, engine.modelPath(), path -> {
            if (path == null) {
                pickModelFile();
            } else {
                loadModel(path);
            }
        });
    }

    private void pickModelFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "*/*"});
        try {
            startActivityForResult(i, REQ_IMPORT_MODEL);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void importModel(Uri uri) {
        pb.setVisibility(View.VISIBLE);
        pb.setIndeterminate(true);
        tvBottom.setText("正在导入模型…");
        io.execute(() -> {
            try {
                File f = models.importFrom(uri, (copied, total) -> ui.post(() -> {
                    if (total > 0) {
                        pb.setIndeterminate(false);
                        pb.setProgress((int) (copied * 100 / total));
                        tvBottom.setText("正在导入模型… " + ModelStore.humanSize(copied)
                                + " / " + ModelStore.humanSize(total));
                    }
                }));
                ui.post(() -> {
                    pb.setVisibility(View.GONE);
                    loadModel(f.getAbsolutePath());
                });
            } catch (Exception e) {
                Log.e(TAG, "导入失败", e);
                ui.post(() -> {
                    pb.setVisibility(View.GONE);
                    tvBottom.setText(getString(R.string.err_import) + "：" + e.getMessage());
                    Toast.makeText(this, getString(R.string.err_import) + "：" + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---- 录音 ----

    private void onRecordClicked() {
        if (!engine.isReady()) {
            Toast.makeText(this, "先导入一个 whisper 模型再录音", Toast.LENGTH_LONG).show();
            showModelDialog();
            return;
        }
        if (recorder != null && recorder.isRecording()) {
            stopRecordingAndTranscribe();
            return;
        }
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            // 通知权限只有 Android 13+ 才有，低版本别去要一个不存在的权限
            String[] wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO};
            requestPermissions(wanted, REQ_RECORD_PERM);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        recorder = new Recorder(this, new Recorder.Listener() {
            @Override
            public void onLevel(float level) {
                float s = 1f + Math.min(0.12f, level * 0.18f);
                btnRecord.setScaleX(s);
                btnRecord.setScaleY(s);
            }

            @Override
            public void onElapsed(long ms) {
                tvTimer.setText(formatMs(ms));
            }

            @Override
            public void onError(String message) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                ui.post(() -> resetRecordUi());
            }
        });
        if (!recorder.start()) {
            Toast.makeText(this, recorder.errorMessage(), Toast.LENGTH_LONG).show();
            recorder = null;
            return;
        }
        RecordService.start(this);
        btnRecord.setImageResource(R.drawable.ic_stop);
        btnRecord.setBackgroundResource(R.drawable.bg_record_active);
        btnStop.setEnabled(true);
        tvStatus.setText(R.string.rec_recording);
        tvSub.setText(R.string.rec_tip);
        tvTimer.setText("00:00");
        pb.setVisibility(View.GONE);
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void stopRecordingAndTranscribe() {
        if (recorder == null || !recorder.isRecording()) return;
        long ms = recorder.elapsedMs();
        File wav = recorder.stop();
        RecordService.stop(this);
        resetRecordUi();

        if (wav == null || ms < 600) {
            Toast.makeText(this, R.string.stt_too_short, Toast.LENGTH_SHORT).show();
            tvStatus.setText(R.string.rec_idle);
            return;
        }
        startTranscribe(wav, ms);
    }

    private void resetRecordUi() {
        ui.removeCallbacks(ticker);
        btnRecord.setImageResource(R.drawable.ic_mic);
        btnRecord.setBackgroundResource(R.drawable.bg_record);
        btnRecord.setScaleX(1f);
        btnRecord.setScaleY(1f);
        btnStop.setEnabled(false);
        if (tvStatus.getText().toString().equals(getString(R.string.rec_recording))) {
            tvStatus.setText(R.string.rec_idle);
        }
        tvSub.setText(R.string.app_sub);
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        // 录音时按返回 = 停止并转写，而不是把录音丢掉；manifest 里已关掉预测式返回，
        // 所以这里在所有版本上都能收到回调
        if (recorder != null && recorder.isRecording()) {
            stopRecordingAndTranscribe();
            return;
        }
        super.onBackPressed();
    }

    // ---- 导入音频 ----

    private void pickAudio() {
        if (!engine.isReady()) {
            Toast.makeText(this, "先导入一个 whisper 模型", Toast.LENGTH_LONG).show();
            showModelDialog();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, REQ_IMPORT_AUDIO);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void importAudio(Uri uri) {
        pb.setVisibility(View.VISIBLE);
        pb.setIndeterminate(true);
        tvBottom.setText("正在解析音频…");
        tvStatus.setText("正在解析音频…");
        io.execute(() -> {
            File out = new File(getCacheDir(), "import_16k.wav");
            try {
                AudioImport.to16kWav(this, uri, out, pct -> ui.post(() -> {
                    pb.setIndeterminate(false);
                    pb.setProgress(pct);
                    tvBottom.setText("正在解析音频… " + pct + "%");
                }));
                long frames = out.length() / 2;
                long ms = frames * 1000 / WavFile.RATE_16K;
                if (ms < 600) throw new IllegalStateException("音频太短，没听到内容");
                ui.post(() -> startTranscribe(out, ms));
            } catch (Exception e) {
                Log.e(TAG, "解析音频失败", e);
                ui.post(() -> {
                    pb.setVisibility(View.GONE);
                    tvStatus.setText(R.string.rec_idle);
                    tvBottom.setText("解析失败：" + e.getMessage());
                    Toast.makeText(this, "解析失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---- 转写 ----

    private void startTranscribe(File wav, long durationMs) {
        setBusy(true);
        lastDurationMs = durationMs;
        lastSavedId = null;
        tvStatus.setText(R.string.stt_running);
        tvBottom.setText(R.string.stt_running);
        pb.setVisibility(View.VISIBLE);
        pb.setIndeterminate(true);

        String lang = LANG_VALUES[spLang.getSelectedItemPosition()];
        int threads = THREAD_OPTIONS[spThreads.getSelectedItemPosition()];
        final long started = System.currentTimeMillis();

        engine.transcribe(wav, lang, threads, swTranslate.isChecked(), new TranscribeCallback() {
            @Override
            public void onProgress(String stage, int percent) {
                if (percent > 0) {
                    pb.setIndeterminate(false);
                    pb.setProgress(percent);
                }
                tvStatus.setText(stage);
                tvBottom.setText(stage);
            }

            @Override
            public void onDone(String text, long elapsedMs) {
                setBusy(false);
                pb.setVisibility(View.GONE);
                tvStatus.setText(R.string.rec_idle);
                if (TextUtils.isEmpty(text)) {
                    tvBottom.setText("没识别出内容，换个模型或靠近麦克风再试");
                    Toast.makeText(MainActivity.this, "没识别出内容", Toast.LENGTH_LONG).show();
                    return;
                }
                etResult.setText(text);
                etResult.setSelection(text.length());
                updateCount();

                double dur = durationMs / 1000.0;
                String rate = dur > 0 ? String.format(Locale.US, "%.2f× 实时", dur / (elapsedMs / 1000.0)) : "";
                tvBottom.setText(String.format(Locale.US, "%s · 用时 %.1fs · %s",
                        getString(R.string.stt_done, text.length()), elapsedMs / 1000.0, rate));

                // 自动存一条，免得转写结果因为误触而丢掉
                saveResult(false);
            }

            @Override
            public void onError(String message) {
                setBusy(false);
                pb.setVisibility(View.GONE);
                tvStatus.setText(R.string.rec_idle);
                tvBottom.setText(message);
                if (!"已取消".equals(message)) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });

        // 底部的进度条要显示「已用时间」，让用户知道还在跑
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!engine.isBusy()) return;
                if (!pb.isIndeterminate()) return;
                tvBottom.setText(getString(R.string.stt_running) + " "
                        + formatMs(System.currentTimeMillis() - started));
                ui.postDelayed(this, 500);
            }
        }, 500);
    }

    private void setBusy(boolean busy) {
        btnRecord.setEnabled(!busy);
        btnImport.setEnabled(!busy);
        btnStop.setEnabled(!busy && recorder != null && recorder.isRecording());
        if (busy) {
            btnRecord.setAlpha(0.5f);
            btnImport.setAlpha(0.5f);
        } else {
            btnRecord.setAlpha(1f);
            btnImport.setAlpha(1f);
        }
    }

    // ---- 结果操作 ----

    private void updateCount() {
        int n = etResult.getText().length();
        tvCount.setText(n == 0 ? "" : n + " 字");
    }

    private void copyResult() {
        String t = etResult.getText().toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.stt_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("iceScribe", t));
        Toast.makeText(this, R.string.stt_copy_done, Toast.LENGTH_SHORT).show();
    }

    private void shareResult() {
        String t = etResult.getText().toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.stt_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, t);
        startActivity(Intent.createChooser(i, getString(R.string.stt_share)));
    }

    private void saveResult(boolean notify) {
        String t = etResult.getText().toString().trim();
        if (t.isEmpty()) {
            Toast.makeText(this, R.string.stt_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        HistoryStore.Item it = new HistoryStore.Item();
        it.id = lastSavedId != null ? lastSavedId : String.valueOf(System.currentTimeMillis());
        it.text = t;
        it.timeMs = System.currentTimeMillis();
        it.durationMs = lastDurationMs;
        it.wavPath = recorder != null && recorder.file() != null
                ? recorder.file().getAbsolutePath() : null;
        it.modelName = engine.modelName();
        it.lang = prefs.lang();

        // 已经存过就更新那条，避免每点一次保存就多一条（也不会误删录音文件）
        history.update(it);
        lastSavedId = it.id;
        renderHistory();
        if (notify) Toast.makeText(this, R.string.stt_saved, Toast.LENGTH_SHORT).show();
    }

    // ---- 历史 ----

    private void renderHistory() {
        llHistory.removeAllViews();
        List<HistoryStore.Item> items = history.all();
        tvHistoryEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (HistoryStore.Item it : items) {
            View row = inflater.inflate(R.layout.item_history, llHistory, false);
            TextView text = row.findViewById(R.id.tvHistText);
            TextView meta = row.findViewById(R.id.tvHistMeta);
            text.setText(it.summary());
            meta.setText(it.timeText() + " · " + it.durationText()
                    + (TextUtils.isEmpty(it.modelName) ? "" : " · " + it.modelName));
            row.findViewById(R.id.btnHistDelete).setOnClickListener(v -> {
                history.remove(it.id);
                if (it.id.equals(lastSavedId)) lastSavedId = null;
                renderHistory();
            });
            row.setOnClickListener(v -> {
                etResult.setText(it.text);
                etResult.setSelection(etResult.getText().length());
                lastSavedId = it.id;
                updateCount();
                tvBottom.setText(getString(R.string.stt_done, it.text.length()));
            });
            llHistory.addView(row);
        }
    }

    private void confirmClearHistory() {
        if (history.size() == 0) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.history_title)
                .setMessage(R.string.confirm_clear_history)
                .setPositiveButton(R.string.history_clear, (d, w) -> {
                    history.clear();
                    lastSavedId = null;
                    renderHistory();
                    Toast.makeText(this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- 权限与结果 ----

    private boolean hasPermission(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_RECORD_PERM) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
            return;
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // 被永久拒绝：指路到设置页
            new AlertDialog.Builder(this)
                    .setTitle(R.string.rec_permission)
                    .setMessage("需要麦克风权限才能录音。请在系统设置里打开。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            Toast.makeText(this, R.string.rec_permission, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_MODEL) {
            importModel(uri);
        } else if (requestCode == REQ_IMPORT_AUDIO) {
            importAudio(uri);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (recorder != null && recorder.isRecording()) {
            recorder.stop();
            RecordService.stop(this);
        }
        engine.shutdown();
        io.shutdown();
    }

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }
}