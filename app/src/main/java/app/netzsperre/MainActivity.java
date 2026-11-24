package app.netzsperre;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Insets;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements Store.Listener {

    static final String EXTRA_ENABLE = "enable";
    private static final int REQ_VPN = 7;

    private static final class AppItem {
        String pkg;
        String label;
        String labelLower;
        boolean system;     // reiner Systemdienst ohne Startsymbol
    }

    private static final class Holder {
        ImageView icon;
        TextView name, sub;
        Toggle toggle;
        View row;
        String pkg;
    }

    private Store store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconLoading = new HashSet<>();
    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> shown = new ArrayList<>();
    private ColorMatrixColorFilter grey;

    private View root, content;
    private ImageView statusIcon;
    private TextView statusTitle, statusDetail, statusButton, tabAll, tabBlocked, empty;
    private EditText search;
    private ListView list;
    private ProgressBar progress;
    private final AppAdapter adapter = new AppAdapter();

    private boolean onlyBlocked;
    private String query = "";
    private boolean loaded;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = Store.get(this);
        setContentView(R.layout.activity_main);
        setupWindow();

        root = findViewById(R.id.root);
        content = findViewById(R.id.content);
        statusIcon = findViewById(R.id.statusIcon);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        statusButton = findViewById(R.id.statusButton);
        tabAll = findViewById(R.id.tabAll);
        tabBlocked = findViewById(R.id.tabBlocked);
        empty = findViewById(R.id.empty);
        search = findViewById(R.id.search);
        list = findViewById(R.id.list);
        progress = findViewById(R.id.progress);
        ImageButton menu = findViewById(R.id.menu);

        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        grey = new ColorMatrixColorFilter(m);

        View listCard = findViewById(R.id.listCard);
        listCard.setClipToOutline(true);

        root.setOnApplyWindowInsetsListener((v, in) -> applyInsets(in));

        list.setAdapter(adapter);
        list.setOnItemClickListener((p, view, pos, id) -> toggleApp(shown.get(pos), (Holder) view.getTag()));
        list.setOnItemLongClickListener((p, view, pos, id) -> {
            openAppInfo(shown.get(pos).pkg);
            return true;
        });

        statusButton.setOnClickListener(v -> {
            if (store.enabled() && (Store.tunnelUp || store.blocked().isEmpty())) {
                store.setEnabled(false);
                BlockVpnService.apply(this);
            } else {
                requestEnable();
            }
        });

        tabAll.setOnClickListener(v -> setTab(false));
        tabBlocked.setOnClickListener(v -> setTab(true));
        menu.setOnClickListener(this::showMenu);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                refilter();
            }
        });
        search.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                return true;
            }
            return false;
        });

        setTab(false);
        loadApps();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_ENABLE, false)) {
            i.removeExtra(EXTRA_ENABLE);
            if (!store.blocked().isEmpty()) requestEnable();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Store.addListener(this);
        // Falls die App nach einem Neustart geoeffnet wird: Tunnel wieder aufbauen.
        if (store.enabled() && !Store.tunnelUp && !store.blocked().isEmpty()
                && VpnService.prepare(this) == null) {
            BlockVpnService.apply(this);
        }
        refreshStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Store.removeListener(this);
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        loadApps(); // neu installierte oder entfernte Apps
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        root.requestApplyInsets(); // Fold auf- oder zugeklappt: Seitenraender neu berechnen
    }

    @Override
    public void onStoreChanged() {
        refreshStatus();
    }

    // ---------------------------------------------------------------- Fenster

    @SuppressWarnings("deprecation")
    private void setupWindow() {
        Window w = getWindow();
        boolean night = isNight();
        w.setStatusBarColor(0);
        w.setNavigationBarColor(0);
        if (Build.VERSION.SDK_INT >= 29) w.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
        } else {
            int f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!night) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(WindowInsets in) {
        int l, t, r, b;
        if (Build.VERSION.SDK_INT >= 30) {
            Insets s = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets ime = in.getInsets(WindowInsets.Type.ime());
            l = s.left; t = s.top; r = s.right; b = Math.max(s.bottom, ime.bottom);
            // Tastatur zu: Cursor im Suchfeld nicht weiter blinken lassen.
            if (!in.isVisible(WindowInsets.Type.ime()) && search.hasFocus()) search.clearFocus();
        } else {
            l = in.getSystemWindowInsetLeft(); t = in.getSystemWindowInsetTop();
            r = in.getSystemWindowInsetRight(); b = in.getSystemWindowInsetBottom();
        }
        // Auf breiten Bildschirmen (Fold innen, Tablet) bleibt der Inhalt mittig und lesbar breit.
        int width = getResources().getDisplayMetrics().widthPixels - l - r;
        int side = Math.max(dp(20), (width - dp(640)) / 2);
        content.setPadding(l + side, t + dp(4), r + side, b + dp(16));
        return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : in.consumeSystemWindowInsets();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- Status

    private void refreshStatus() {
        int n = blockedInstalledCount();
        boolean on = store.enabled();
        boolean hasBlocked = !store.blocked().isEmpty();

        if (on && Store.tunnelUp) {
            setStatus(R.drawable.ic_shield_on, R.drawable.bg_circle_accent, getString(R.string.st_on),
                    getResources().getQuantityString(R.plurals.st_on_detail, n, n), R.string.btn_pause, false);
        } else if (on && !hasBlocked) {
            setStatus(R.drawable.ic_shield_on, R.drawable.bg_circle_accent, getString(R.string.st_on_empty),
                    getString(R.string.st_on_empty_detail), R.string.btn_off, false);
        } else if (on && Store.lastError != null) {
            setStatus(R.drawable.ic_shield_warn, R.drawable.bg_circle_field, getString(R.string.st_error),
                    Store.lastError, R.string.btn_retry, true);
        } else if (on) {
            setStatus(R.drawable.ic_shield_on, R.drawable.bg_circle_accent, getString(R.string.st_starting),
                    getResources().getQuantityString(R.plurals.st_on_detail, n, n), R.string.btn_pause, false);
        } else {
            String detail = n > 0 ? getResources().getQuantityString(R.plurals.st_off_waiting, n, n)
                    : getString(R.string.st_off_detail);
            setStatus(R.drawable.ic_shield_off, R.drawable.bg_circle_field, getString(R.string.st_off),
                    detail, R.string.btn_on, true);
        }

        tabBlocked.setText(n > 0 ? getString(R.string.tab_blocked_n, n) : getString(R.string.tab_blocked));
        // Untertitel der sichtbaren Zeilen (gesperrt / pausiert) aktualisieren.
        for (int i = 0; i < list.getChildCount(); i++) {
            Object tag = list.getChildAt(i).getTag();
            if (tag instanceof Holder) {
                Holder h = (Holder) tag;
                AppItem a = find(h.pkg);
                if (a != null) bindState(h, a, false);
            }
        }
    }

    private void setStatus(int icon, int iconBg, String title, String detail, int button, boolean primary) {
        statusIcon.setImageResource(icon);
        statusIcon.setBackgroundResource(iconBg);
        statusTitle.setText(title);
        statusDetail.setText(detail);
        statusButton.setText(button);
        statusButton.setBackgroundResource(primary ? R.drawable.bg_pill_accent : R.drawable.bg_pill_neutral);
        statusButton.setTextColor(getColor(primary ? R.color.on_accent : R.color.text));
    }

    private int blockedInstalledCount() {
        if (!loaded) return store.blocked().size();
        int n = 0;
        for (AppItem a : all) if (store.isBlocked(a.pkg)) n++;
        return n;
    }

    private void requestEnable() {
        Intent consent;
        try {
            consent = VpnService.prepare(this);
        } catch (RuntimeException e) {
            consent = null;
        }
        if (consent != null) {
            try {
                startActivityForResult(consent, REQ_VPN);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Sollte es nicht geben; dann einfach versuchen.
            }
        }
        store.setEnabled(true);
        BlockVpnService.apply(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        if (resultCode == RESULT_OK) {
            store.setEnabled(true);
            BlockVpnService.apply(this);
        } else {
            store.setEnabled(false);
            Toast.makeText(this, R.string.denied, Toast.LENGTH_LONG).show();
        }
        refreshStatus();
    }

    // ---------------------------------------------------------------- Liste

    private void loadApps() {
        final boolean first = !loaded;
        if (first) progress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Set<String> launchable = new HashSet<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
                launchable.add(r.activityInfo.packageName);
            }
            List<PackageInfo> pkgs;
            try {
                pkgs = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
            } catch (RuntimeException e) {
                pkgs = new ArrayList<>();
            }
            List<AppItem> items = new ArrayList<>();
            for (PackageInfo p : pkgs) {
                if (p.packageName.equals(getPackageName()) || p.applicationInfo == null) continue;
                if (!wantsInternet(p)) continue; // ohne Internet-Berechtigung gibt es nichts zu sperren
                ApplicationInfo ai = p.applicationInfo;
                AppItem a = new AppItem();
                a.pkg = p.packageName;
                CharSequence l = ai.loadLabel(pm);
                a.label = l == null ? p.packageName : l.toString().trim();
                a.labelLower = a.label.toLowerCase(Locale.ROOT);
                boolean sysFlag = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                a.system = sysFlag && !launchable.contains(p.packageName);
                items.add(a);
            }
            Collator col = Collator.getInstance(Locale.GERMAN);
            items.sort((x, y) -> col.compare(x.label, y.label));
            ui.post(() -> {
                if (isDestroyed()) return;
                all.clear();
                all.addAll(items);
                loaded = true;
                progress.setVisibility(View.GONE);
                refilter();
                refreshStatus();
            });
        });
    }

    private static boolean wantsInternet(PackageInfo p) {
        if (p.requestedPermissions == null) return false;
        for (String s : p.requestedPermissions) {
            if ("android.permission.INTERNET".equals(s)) return true;
        }
        return false;
    }

    private void setTab(boolean blocked) {
        onlyBlocked = blocked;
        styleTab(tabAll, !blocked);
        styleTab(tabBlocked, blocked);
        refilter();
    }

    private void styleTab(TextView t, boolean on) {
        t.setBackgroundResource(on ? R.drawable.bg_tab_on : 0);
        t.setTextColor(getColor(on ? R.color.text : R.color.text2));
    }

    private void refilter() {
        shown.clear();
        boolean sys = store.showSystem();
        for (AppItem a : all) {
            boolean blocked = store.isBlocked(a.pkg);
            if (onlyBlocked && !blocked) continue;
            // Gesperrte Systemdienste immer zeigen, damit man sie wiederfindet.
            if (!onlyBlocked && a.system && !sys && !blocked) continue;
            if (!query.isEmpty() && !a.labelLower.contains(query) && !a.pkg.contains(query)) continue;
            shown.add(a);
        }
        adapter.notifyDataSetChanged();
        if (!loaded) {
            empty.setVisibility(View.GONE);
        } else if (shown.isEmpty()) {
            empty.setText(onlyBlocked && query.isEmpty() ? R.string.empty_blocked : R.string.empty_none);
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private AppItem find(String pkg) {
        for (AppItem a : shown) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void toggleApp(AppItem a, Holder h) {
        boolean block = !store.isBlocked(a.pkg);
        store.setBlocked(a.pkg, block);
        if (h != null && a.pkg.equals(h.pkg)) bindState(h, a, true);
        if (block && !store.enabled()) {
            requestEnable(); // erste Sperre schaltet den Schutz direkt ein
        } else {
            BlockVpnService.apply(this);
        }
        if (onlyBlocked && !block) ui.postDelayed(this::refilter, 220);
        refreshStatus();
    }

    private void bindState(Holder h, AppItem a, boolean animate) {
        boolean blocked = store.isBlocked(a.pkg);
        h.toggle.setChecked(blocked, animate);
        if (blocked) {
            h.sub.setText(store.enabled() ? R.string.row_blocked : R.string.row_paused);
            h.sub.setTextColor(getColor(store.enabled() ? R.color.danger : R.color.text2));
            h.icon.setColorFilter(grey);
            h.icon.setImageAlpha(150);
        } else {
            h.sub.setText(a.system ? getString(R.string.row_system) + " · " + a.pkg : a.pkg);
            h.sub.setTextColor(getColor(R.color.text2));
            h.icon.clearColorFilter();
            h.icon.setImageAlpha(255);
        }
        h.row.setContentDescription(a.label + ", " + getString(blocked ? R.string.row_blocked : R.string.row_allowed));
    }

    private void loadIcon(String pkg) {
        if (iconLoading.contains(pkg)) return;
        iconLoading.add(pkg);
        io.execute(() -> {
            Drawable d;
            try {
                d = getPackageManager().getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                d = getPackageManager().getDefaultActivityIcon();
            }
            final Drawable icon = d;
            ui.post(() -> {
                icons.put(pkg, icon);
                for (int i = 0; i < list.getChildCount(); i++) {
                    Object tag = list.getChildAt(i).getTag();
                    if (tag instanceof Holder && pkg.equals(((Holder) tag).pkg)) {
                        ((Holder) tag).icon.setImageDrawable(icon);
                    }
                }
            });
        });
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return shown.get(i).pkg.hashCode(); }
        @Override public boolean hasStableIds() { return true; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Holder h;
            if (v == null) {
                v = LayoutInflater.from(parent.getContext()).inflate(R.layout.row_app, parent, false);
                h = new Holder();
                h.icon = v.findViewById(R.id.icon);
                h.name = v.findViewById(R.id.name);
                h.sub = v.findViewById(R.id.sub);
                h.toggle = v.findViewById(R.id.toggle);
                h.row = v;
                v.setTag(h);
            } else {
                h = (Holder) v.getTag();
            }
            AppItem a = shown.get(pos);
            h.pkg = a.pkg;
            h.name.setText(a.label);
            Drawable d = icons.get(a.pkg);
            h.icon.setImageDrawable(d);
            if (d == null) loadIcon(a.pkg);
            bindState(h, a, false);
            return v;
        }
    }

    // ---------------------------------------------------------------- Menue

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        MenuItem sys = m.add(0, 1, 0, R.string.m_system);
        sys.setCheckable(true);
        sys.setChecked(store.showSystem());
        m.add(0, 2, 1, R.string.m_unblock_all).setEnabled(!store.blocked().isEmpty());
        m.add(0, 3, 2, R.string.m_always_on);
        m.add(0, 4, 3, R.string.m_about);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    store.setShowSystem(!store.showSystem());
                    refilter();
                    return true;
                case 2:
                    confirmUnblockAll();
                    return true;
                case 3:
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.always_on_title)
                            .setMessage(R.string.always_on_text)
                            .setPositiveButton(R.string.vpn_settings, (d, w) -> openVpnSettings())
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                    return true;
                case 4:
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.about_title)
                            .setMessage(R.string.about_text)
                            .setPositiveButton(R.string.ok, null)
                            .show();
                    return true;
            }
            return false;
        });
        pm.show();
    }

    private void confirmUnblockAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.unblock_all_q)
                .setMessage(R.string.unblock_all_msg)
                .setPositiveButton(R.string.yes_unblock, (d, w) -> {
                    store.clearBlocked();
                    BlockVpnService.apply(this);
                    refilter();
                    refreshStatus();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
        } catch (ActivityNotFoundException ignored) { }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }
}
