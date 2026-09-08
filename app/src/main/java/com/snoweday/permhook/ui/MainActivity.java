package com.snoweday.permhook.ui;

import android.content.Context;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
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

/** Material 3 rule editor for Oplus app-start confirmation operations. */
public final class MainActivity extends AppCompatActivity {
    private static final String TAG = "PermHookUi";
    private static final String RULE_REQUIREMENT_HINT =
            "调用方包名、目标包名至少填写一项；空白项会自动补为 *。";
    private static final String RULE_EDITOR_HINT = RULE_REQUIREMENT_HINT
            + "\n包名支持 * 通配符。"
            + "\n操作可选择经过 Activity 确认或不经过 Activity 直接启动。"
            + "\n调用方和目标包必须不同。";

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
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), systemBars.top,
                    view.getPaddingRight(), systemBars.bottom);
            return insets;
        });

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
        addButton.setOnClickListener(view -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            try {
                showRuleEditor(null, -1);
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to open rule editor", error);
                Toast.makeText(this, "无法打开规则编辑器，请稍后重试", Toast.LENGTH_LONG).show();
            }
        });
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.END | Gravity.BOTTOM);
        addParams.setMargins(0, 0, dp(20), dp(20));
        listFrame.addView(addButton, addParams);

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        listParams.weight = 1;
        root.addView(listFrame, listParams);
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
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
        if (isFinishing() || isDestroyed()) {
            return;
        }
        try {
            showMaterialRuleEditor(existing, existingPosition);
        } catch (RuntimeException error) {
            Log.e(TAG, "Material rule editor could not be shown; using fallback", error);
            try {
                showFallbackRuleEditor(existing, existingPosition);
            } catch (RuntimeException fallbackError) {
                Log.e(TAG, "Fallback rule editor could not be shown", fallbackError);
                Toast.makeText(this, "无法打开规则编辑器，请稍后重试", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showMaterialRuleEditor(@Nullable LaunchRule existing, int existingPosition) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), 0, dp(24), 0);

        InputField caller = addInput(form, "调用方包名", existing == null ? "" : existing.getCallerPackage());
        InputField target = addInput(form, "目标包名", existing == null ? "" : existing.getTargetPackage());
        OperationSelector operation = addMaterialOperationSelector(
                form, existing == null || existing.requiresConfirmationActivity());
        InputField label = addInput(form, "备注（可选）", existing == null ? "" : existing.getLabel());

        TextView hint = textView(12, false);
        hint.setAlpha(0.7f);
        hint.setText(RULE_EDITOR_HINT);
        form.addView(hint, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4));

        SwitchMaterial enabled = new SwitchMaterial(this);
        enabled.setText("规则启用");
        enabled.setContentDescription("规则启用");
        enabled.setChecked(existing == null || existing.isEnabled());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setCustomTitle(editorTitle(
                        existing == null ? "添加启动规则" : "编辑启动规则", enabled))
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(view -> {
                String callerPackage = valueOf(caller.edit);
                String targetPackage = valueOf(target.edit);
                String labelValue = valueOf(label.edit);
                saveRule(dialog, existing, existingPosition, callerPackage, targetPackage,
                        operation.requiresConfirmation(), enabled.isChecked(), labelValue,
                        hint, caller.edit, target.edit, caller.layout, target.layout);
            });
        });
        dialog.show();
    }

    private void showFallbackRuleEditor(@Nullable LaunchRule existing, int existingPosition) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);

        EditText caller = addPlainInput(form, "调用方包名",
                existing == null ? "" : existing.getCallerPackage());
        EditText target = addPlainInput(form, "目标包名",
                existing == null ? "" : existing.getTargetPackage());
        OperationSelector operation = addFallbackOperationSelector(
                form, existing == null || existing.requiresConfirmationActivity());
        EditText label = addPlainInput(form, "备注（可选）",
                existing == null ? "" : existing.getLabel());

        TextView hint = textView(12, false);
        hint.setAlpha(0.7f);
        hint.setText(RULE_EDITOR_HINT);
        form.addView(hint, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 4, 0, 4));

        android.widget.Switch enabled = new android.widget.Switch(this);
        enabled.setText("规则启用");
        enabled.setContentDescription("规则启用");
        enabled.setChecked(existing == null || existing.isEnabled());

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(form, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(editorTitle(
                        existing == null ? "添加启动规则" : "编辑启动规则", enabled))
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(view -> {
                saveRule(dialog, existing, existingPosition, valueOf(caller), valueOf(target),
                        operation.requiresConfirmation(), enabled.isChecked(),
                        valueOf(label),
                        hint, caller, target, null, null);
            });
        });
        dialog.show();
    }

    private LinearLayout editorTitle(String title, View enabledControl) {
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(24), dp(8), dp(8), dp(4));

        TextView titleView = textView(20, true);
        titleView.setText(title);
        titleBar.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        titleBar.addView(enabledControl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return titleBar;
    }

    private OperationSelector addMaterialOperationSelector(
            LinearLayout form, boolean requiresConfirmation) {
        return addOperationSelector(form, requiresConfirmation, true);
    }

    private OperationSelector addFallbackOperationSelector(
            LinearLayout form, boolean requiresConfirmation) {
        return addOperationSelector(form, requiresConfirmation, false);
    }

    private OperationSelector addOperationSelector(
            LinearLayout form, boolean requiresConfirmation, boolean material) {
        TextView operationLabel = textView(14, true);
        operationLabel.setText("操作");
        form.addView(operationLabel, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 8, 0, 0));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton confirm = material
                ? new MaterialRadioButton(this) : new RadioButton(this);
        confirm.setText("经过 Activity 确认");
        confirm.setContentDescription("经过 Activity 确认");
        RadioButton bypass = material
                ? new MaterialRadioButton(this) : new RadioButton(this);
        bypass.setText("不经过 Activity 确认");
        bypass.setContentDescription("不经过 Activity 确认");
        group.addView(confirm, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        group.addView(bypass, new RadioGroup.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        confirm.setChecked(requiresConfirmation);
        bypass.setChecked(!requiresConfirmation);
        form.addView(group, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                0, 0, 0, 0));
        return new OperationSelector(confirm);
    }

    private void saveRule(AlertDialog dialog, @Nullable LaunchRule existing, int existingPosition,
            String callerPackage, String targetPackage,
            boolean requiresConfirmation, boolean enabled, String labelValue,
            TextView requirementHint, EditText callerEdit,
            EditText targetEdit, @Nullable TextInputLayout callerLayout,
            @Nullable TextInputLayout targetLayout) {
        clearError(callerEdit, callerLayout);
        clearError(targetEdit, targetLayout);
        if (callerPackage.isEmpty() && targetPackage.isEmpty()) {
            highlightRequirementHint(requirementHint);
            return;
        }
        if (callerPackage.isEmpty()) {
            callerPackage = LaunchRule.ANY;
            callerEdit.setText(callerPackage);
        }
        if (targetPackage.isEmpty()) {
            targetPackage = LaunchRule.ANY;
            targetEdit.setText(targetPackage);
        }
        if (!isPackagePattern(callerPackage)) {
            setError(callerEdit, callerLayout, "请输入有效包名或 * 通配符");
            return;
        }
        if (!isPackagePattern(targetPackage)) {
            setError(targetEdit, targetLayout, "请输入有效包名或 * 通配符");
            return;
        }
        if (!LaunchRule.ANY.equals(callerPackage) && callerPackage.equals(targetPackage)) {
            setError(targetEdit, targetLayout, "目标包必须与调用方包不同");
            return;
        }

        LaunchRule updated = existing == null
                ? LaunchRule.create(callerPackage, targetPackage,
                requiresConfirmation, enabled, labelValue)
                : existing.withValues(callerPackage, targetPackage,
                requiresConfirmation, enabled, labelValue);
        if (existingPosition < 0) {
            rules.add(updated);
        } else if (existingPosition < rules.size()) {
            rules.set(existingPosition, updated);
        }
        try {
            persistRules();
            dialog.dismiss();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to save rule", error);
            Toast.makeText(this, "规则保存失败，请稍后重试", Toast.LENGTH_LONG).show();
        }
    }

    private static void clearError(EditText edit, @Nullable TextInputLayout layout) {
        edit.setError(null);
        if (layout != null) {
            layout.setError(null);
        }
    }

    private static void setError(EditText edit, @Nullable TextInputLayout layout, String message) {
        if (layout != null) {
            layout.setError(message);
        } else {
            edit.setError(message);
        }
    }

    private static void highlightRequirementHint(TextView hint) {
        String text = hint.getText().toString();
        int end = text.indexOf('\n');
        if (end < 0) {
            end = text.length();
        }
        SpannableString highlighted = new SpannableString(text);
        int backgroundColor = com.google.android.material.color.MaterialColors.getColor(
                hint, com.google.android.material.R.attr.colorSecondaryContainer);
        int foregroundColor = com.google.android.material.color.MaterialColors.getColor(
                hint, com.google.android.material.R.attr.colorOnSecondaryContainer);
        highlighted.setSpan(new BackgroundColorSpan(backgroundColor), 0, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        highlighted.setSpan(new ForegroundColorSpan(foregroundColor), 0, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        hint.setText(highlighted);
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

    private EditText addPlainInput(LinearLayout form, String hint, String value) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL);
        edit.setText(value);
        edit.setSelection(edit.length());
        form.addView(edit, marginParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 8, 0, 0));
        return edit;
    }

    private static String valueOf(TextInputEditText edit) {
        return edit.getText() == null ? "" : edit.getText().toString().trim();
    }

    private static String valueOf(EditText edit) {
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

    private static final class OperationSelector {
        private final RadioButton confirmationOption;

        OperationSelector(RadioButton confirmationOption) {
            this.confirmationOption = confirmationOption;
        }

        boolean requiresConfirmation() {
            return confirmationOption.isChecked();
        }
    }
}
