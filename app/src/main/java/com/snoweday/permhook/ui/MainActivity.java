package com.snoweday.permhook.ui;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;

import com.snoweday.permhook.PermHookApplication;
import com.snoweday.permhook.R;
import com.snoweday.permhook.data.LaunchRule;
import com.snoweday.permhook.data.RuleStore;

/** Material 3 rule editor for forced Oplus app-start confirmations. */
public final class MainActivity extends AppCompatActivity {
    private final ArrayList<LaunchRule> rules = new ArrayList<>();
    private final PermHookApplication.Listener serviceListener =
            service -> runOnUiThread(this::refreshFromStore);

    private RuleAdapter adapter;
    private TextView emptyView;
    private TextView serviceTitle;
    private TextView serviceDetail;

    @Override
    protected void onCreate(@Nullable android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildContentView();
        PermHookApplication.addListener(serviceListener);
        refreshFromStore();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFromStore();
    }

    @Override
    protected void onDestroy() {
        PermHookApplication.removeListener(serviceListener);
        super.onDestroy();
    }

    private void buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);
        toolbar.setSubtitle("Modern Xposed · system_server");
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        MaterialCardView serviceCard = new MaterialCardView(this);
        serviceCard.setUseCompatPadding(true);
        serviceCard.setStrokeWidth(dp(1));
        LinearLayout serviceContent = new LinearLayout(this);
        serviceContent.setOrientation(LinearLayout.VERTICAL);
        serviceContent.setPadding(dp(16), dp(12), dp(16), dp(12));
        serviceTitle = textView(16, true);
        serviceDetail = textView(13, false);
        serviceDetail.setAlpha(0.75f);
        serviceContent.addView(serviceTitle);
        serviceContent.addView(serviceDetail, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));
        serviceCard.addView(serviceContent);
        root.addView(serviceCard, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                16, 8, 16, 4));

        FrameLayout listFrame = new FrameLayout(this);
        RecyclerView recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setPadding(dp(12), dp(8), dp(12), dp(96));
        recyclerView.setClipToPadding(false);
        adapter = new RuleAdapter(this);
        recyclerView.setAdapter(adapter);
        listFrame.addView(recyclerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = textView(15, false);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(36), dp(36), dp(36), dp(96));
        emptyView.setText("还没有规则\n点击右下角按钮添加一条规则");
        listFrame.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FloatingActionButton addButton = new FloatingActionButton(this);
        addButton.setImageResource(R.drawable.ic_add);
        addButton.setContentDescription("添加规则");
        addButton.setOnClickListener(view -> showRuleEditor(null, -1));
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.END | Gravity.BOTTOM);
        addParams.setMargins(0, 0, dp(20), dp(20));
        listFrame.addView(addButton, addParams);

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        listParams.weight = 1;
        root.addView(listFrame, listParams);
        setContentView(root);
        setSupportActionBar(toolbar);
    }

    private void refreshFromStore() {
        if (adapter == null) {
            return;
        }
        XposedService service = PermHookApplication.getService();
        rules.clear();
        rules.addAll(RuleStore.load(getApplicationContext(), service));
        adapter.submit(rules);
        emptyView.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        updateServiceStatus(service);
    }

    private void persistRules() {
        XposedService service = PermHookApplication.getService();
        boolean synced = RuleStore.save(getApplicationContext(), service, rules);
        adapter.submit(rules);
        emptyView.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        updateServiceStatus(service);
        if (synced) {
            Toast.makeText(this, "规则已保存并同步到 Xposed", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "规则已保存在本地；连接 Xposed 后才会生效", Toast.LENGTH_LONG).show();
        }
    }

    private void updateServiceStatus(@Nullable XposedService service) {
        if (RuleStore.hasRemoteAccess(service)) {
            serviceTitle.setText("Xposed 服务已连接");
            serviceDetail.setText("规则会同步到 system_server，修改后无需重启系统服务。");
        } else {
            serviceTitle.setText("等待 Xposed 服务");
            serviceDetail.setText("当前仅保存本地副本；请在 Modern Xposed 中启用本模块并勾选 system 作用域。");
        }
    }

    private void showRuleEditor(@Nullable LaunchRule existing, int existingPosition) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), 0);

        InputField caller = addInput(form, "调用方包名（必填）", existing == null ? "" : existing.getCallerPackage());
        InputField target = addInput(form, "目标包名（必填）", existing == null ? "" : existing.getTargetPackage());
        InputField component = addInput(form, "组件（可选）", existing == null ? "" : existing.getComponent());
        InputField action = addInput(form, "Intent action（可选）", existing == null ? "" : existing.getAction());
        InputField label = addInput(form, "备注（可选）", existing == null ? "" : existing.getLabel());

        TextView hint = textView(12, false);
        hint.setAlpha(0.7f);
        hint.setText("包名支持 * 通配符；组件可填 target/.MainActivity 或完整 target/com.example.MainActivity。\n调用方和目标包必须不同。");
        form.addView(hint, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4));

        SwitchMaterial enabled = new SwitchMaterial(this);
        enabled.setText("规则启用");
        enabled.setChecked(existing == null || existing.isEnabled());
        form.addView(enabled, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? "添加启动规则" : "编辑启动规则")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(view -> {
                caller.layout.setError(null);
                target.layout.setError(null);
                String callerPackage = valueOf(caller.edit);
                String targetPackage = valueOf(target.edit);
                String componentValue = valueOf(component.edit);
                String actionValue = valueOf(action.edit);
                String labelValue = valueOf(label.edit);
                if (!isPackagePattern(callerPackage)) {
                    caller.layout.setError("请输入有效包名或 * 通配符");
                    return;
                }
                if (!isPackagePattern(targetPackage)) {
                    target.layout.setError("请输入有效包名或 * 通配符");
                    return;
                }
                if (!LaunchRule.ANY.equals(callerPackage) && callerPackage.equals(targetPackage)) {
                    target.layout.setError("目标包必须与调用方包不同");
                    return;
                }

                LaunchRule updated = existing == null
                        ? LaunchRule.create(callerPackage, targetPackage, componentValue,
                        actionValue, enabled.isChecked(), labelValue)
                        : existing.withValues(callerPackage, targetPackage, componentValue,
                        actionValue, enabled.isChecked(), labelValue);
                if (existingPosition < 0) {
                    rules.add(updated);
                } else if (existingPosition < rules.size()) {
                    rules.set(existingPosition, updated);
                }
                persistRules();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void confirmDelete(int position) {
        if (position < 0 || position >= rules.size()) {
            return;
        }
        LaunchRule rule = rules.get(position);
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除这条规则？")
                .setMessage(rule.summary())
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    rules.remove(position);
                    persistRules();
                })
                .show();
    }

    private InputField addInput(LinearLayout form, String hint, String value) {
        TextInputLayout layout = new TextInputLayout(this);
        layout.setHint(hint);
        layout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        edit.setText(value);
        edit.setSelection(edit.length());
        layout.addView(edit, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        form.addView(layout, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        return new InputField(layout, edit);
    }

    private static String valueOf(TextInputEditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private static boolean isPackagePattern(String value) {
        return !value.isEmpty() && value.matches("[A-Za-z0-9_.*]+") && !value.contains("..")
                && !value.startsWith(".") && !value.endsWith(".");
    }

    private TextView textView(float sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(sizeSp);
        if (bold) {
            view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams marginParams(int width, int height,
            int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class RuleAdapter extends RecyclerView.Adapter<RuleAdapter.RuleViewHolder> {
        private final Context context;
        private final ArrayList<LaunchRule> items = new ArrayList<>();

        RuleAdapter(Context context) {
            this.context = context;
        }

        void submit(List<LaunchRule> newItems) {
            items.clear();
            if (newItems != null) {
                items.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        @Override
        public RuleViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(context);
            card.setUseCompatPadding(true);
            card.setStrokeWidth(dp(1));
            RecyclerView.LayoutParams cardParams = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(10);
            card.setLayoutParams(cardParams);

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(12), dp(12), dp(8));

            LinearLayout topLine = new LinearLayout(context);
            topLine.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = textView(16, true);
            topLine.addView(title, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            SwitchMaterial enabled = new SwitchMaterial(context);
            enabled.setContentDescription("启用规则");
            topLine.addView(enabled, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView details = textView(13, false);
            details.setAlpha(0.82f);
            details.setTextIsSelectable(true);
            content.addView(topLine);
            content.addView(details, marginParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 0));

            MaterialButton delete = new MaterialButton(context);
            delete.setText("删除");
            delete.setAllCaps(false);
            delete.setTextSize(12);
            content.addView(delete, marginParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2, 0, 0));
            card.addView(content);
            return new RuleViewHolder(card, title, details, enabled, delete);
        }

        @Override
        public void onBindViewHolder(RuleViewHolder holder, int position) {
            LaunchRule rule = items.get(position);
            holder.title.setText(rule.getLabel().isEmpty()
                    ? "规则 " + (position + 1) : rule.getLabel());
            holder.details.setText(rule.summary());
            holder.enabled.setOnCheckedChangeListener(null);
            holder.enabled.setChecked(rule.isEnabled());
            holder.enabled.setOnCheckedChangeListener((button, checked) -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION && current < rules.size()) {
                    rules.set(current, rules.get(current).withEnabled(checked));
                    persistRules();
                }
            });
            holder.delete.setOnClickListener(view -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION) {
                    confirmDelete(current);
                }
            });
            holder.itemView.setOnClickListener(view -> {
                int current = holder.getBindingAdapterPosition();
                if (current != RecyclerView.NO_POSITION && current < rules.size()) {
                    showRuleEditor(rules.get(current), current);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class RuleViewHolder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView details;
            final SwitchMaterial enabled;
            final MaterialButton delete;

            RuleViewHolder(View itemView, TextView title, TextView details,
                    SwitchMaterial enabled, MaterialButton delete) {
                super(itemView);
                this.title = title;
                this.details = details;
                this.enabled = enabled;
                this.delete = delete;
            }
        }
    }

    private static final class InputField {
        final TextInputLayout layout;
        final TextInputEditText edit;

        InputField(TextInputLayout layout, TextInputEditText edit) {
            this.layout = layout;
            this.edit = edit;
        }
    }
}
