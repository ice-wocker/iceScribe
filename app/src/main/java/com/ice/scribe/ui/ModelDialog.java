package com.ice.scribe.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ice.scribe.R;
import com.ice.scribe.stt.ModelStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型选择弹窗：列出已导入的模型，可选中、可长按删除，并提示模型该放哪儿。
 */
public final class ModelDialog {

    public interface OnPick {
        /** @param path null 表示只关掉了弹窗 */
        void onPick(String path);
    }

    private ModelDialog() {
    }

    public static void show(Activity activity, ModelStore store, String currentPath, OnPick onPick) {
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_models, null);

        TextView hint = root.findViewById(R.id.tvModelHint);
        TextView empty = root.findViewById(R.id.tvModelEmpty);
        TextView path = root.findViewById(R.id.tvModelPath);
        ListView list = root.findViewById(R.id.lvModels);

        String ext = store.externalPathHint();
        path.setText(ext.isEmpty() ? "" : activity.getString(R.string.model_import) + " → " + ext);
        hint.setText(activity.getString(R.string.model_hint) + "\n" + activity.getString(R.string.model_delete_hint));

        List<ModelStore.Model> models = new ArrayList<>(store.list());
        if (models.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        }

        Adapter adapter = new Adapter(activity, models, currentPath);
        list.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.model_choose_title)
                .setView(root)
                .setPositiveButton(R.string.model_import, (d, w) -> onPick.onPick(null))
                .setNegativeButton(R.string.cancel, null)
                .create();

        list.setOnItemClickListener((parent, view, position, id) ->
                onPick.onPick(models.get(position).file.getAbsolutePath()));

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            ModelStore.Model m = models.get(position);
            if (m.file.getAbsolutePath().equals(currentPath)) {
                Toast.makeText(activity, "正在使用这个模型，请先切换到别的模型", Toast.LENGTH_SHORT).show();
                return true;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(m.displayName)
                    .setMessage(activity.getString(R.string.confirm_delete_model) + "\n"
                            + m.file.getName() + " · " + m.sizeText())
                    .setPositiveButton(R.string.history_delete, (d, w) -> {
                        if (store.delete(m.file)) {
                            models.remove(position);
                            adapter.notifyDataSetChanged();
                            if (models.isEmpty()) {
                                empty.setVisibility(View.VISIBLE);
                                list.setVisibility(View.GONE);
                            }
                        } else {
                            Toast.makeText(activity, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        });

        dialog.show();
    }

    private static final class Adapter extends BaseAdapter {
        private final Context ctx;
        private final List<ModelStore.Model> data;
        private final String selectedPath;

        Adapter(Context ctx, List<ModelStore.Model> data, String selectedPath) {
            this.ctx = ctx;
            this.data = data;
            this.selectedPath = selectedPath;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(ctx).inflate(R.layout.item_model, parent, false);
            ModelStore.Model m = data.get(position);
            TextView name = v.findViewById(R.id.tvModelName);
            TextView meta = v.findViewById(R.id.tvModelMeta);
            ImageView check = v.findViewById(R.id.ivSelected);

            boolean selected = m.file.getAbsolutePath().equals(selectedPath);
            name.setText(m.displayName);
            name.setTextColor(ctx.getColor(selected ? R.color.brand : R.color.text));
            meta.setText(m.sizeText() + " · " + shortDir(ctx, m.file));
            check.setVisibility(selected ? View.VISIBLE : View.GONE);
            return v;
        }

        /** 标出模型在内部还是外部目录，方便用户知道该往哪儿拷。 */
        private static String shortDir(Context ctx, File f) {
            String p = f.getAbsolutePath();
            return p.contains("/Android/data/") || p.contains("/Android/obb/") ? "外部目录" : "内部目录";
        }
    }
}