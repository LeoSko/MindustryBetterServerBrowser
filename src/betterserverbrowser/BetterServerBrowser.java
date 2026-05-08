package betterserverbrowser;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.scene.event.Touchable;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.Tooltip;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.IntSeq;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.struct.SnapshotSeq;
import mindustry.Vars;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

/**
 * Better Server Browser — replaces the vanilla "Join Game" dialog with a
 * sortable / filterable / groupable table:
 *
 *  - p90 ping over a rolling 16-sample window (continuous re-ping with
 *    exponential backoff schedule: 1s..15min plateau).
 *  - favorites list, synced bidirectionally with vanilla "servers" key.
 *  - sortable columns, mode-chip filters, search, max-ping slider,
 *    group-by (none / mode / group), show-empty toggle.
 *  - row hover highlight, click-anywhere-on-row to connect.
 *  - custom connection dialog (host[:port]).
 *  - main-menu Reconnect button with live ping refresh of the last
 *    successfully-connected host.
 */
public class BetterServerBrowser extends Mod {

    // ---------- Server browser state ----------
    private BaseDialog serversDialog;
    private final Seq<BrowserEntry> browserEntries = new Seq<>();
    /** ip:port keys we've connected to this session, newest first. */
    private final Seq<String> recentConnections = new Seq<>();
    private static final int RECENT_LIMIT = 16;

    // ---- persisted prefs ----
    private final ObjectSet<String> cfgServersModeFilter = new ObjectSet<>();
    private int cfgServersMaxPing = 999;
    /** Active sort column: name | players | max | ping | mode | map. */
    private String cfgServersSort = "players";
    private boolean cfgServersSortDesc = true;
    private String cfgServersGroupBy = "mode";
    private boolean cfgServersShowEmpty = true;
    private String cfgServersSearch = "";
    private final Seq<String> cfgServersFavorites = new Seq<>();

    private Table browserList;
    private Table modeChipBar;
    private BrowserEntry hoveredBrowserEntry;
    private final ObjectMap<BrowserEntry, Label> rowAnchorByEntry = new ObjectMap<>();

    /** Set when a ping callback wants the row table refreshed; consumed
     *  on the next render tick so a flurry of concurrent pings causes
     *  exactly one rebuild instead of N. */
    private boolean browserRowsRefreshPending = false;

    /** Inter-ping intervals (ms). Pings at 1,2,3,4,5s; 7,9,11,13,15s;
     *  20,25,30,35,40s; 60,90,120,180,240s; 6,10,15min — then 15min cap. */
    private static final int[] PING_SCHEDULE_MS = {
        1000, 1000, 1000, 1000, 1000,
        2000, 2000, 2000, 2000, 2000,
        5000, 5000, 5000, 5000, 5000,
        20000, 30000, 30000, 60000, 60000,
        120000, 240000, 300000
    };
    private static final int PING_HISTORY_LIMIT = 16;
    private static final IntSeq pingP90Scratch = new IntSeq();

    // ---------- Reconnect-card state ----------
    private mindustry.net.Host reconnectLastHost;
    private float reconnectPingTimer;
    private boolean reconnectPingInFlight;
    private static final String RECONNECT_BTN_TAG = "» Reconnect";

    // ---------- Button-width measurement ----------
    private static final arc.graphics.g2d.GlyphLayout MEASURE_LAYOUT =
        new arc.graphics.g2d.GlyphLayout();
    private static final float BTN_LABEL_PAD = 6f;
    private static final float BTN_BG_INSET  = 4f;

    /** True when the current device should use the compact / touch-first
     *  layout: bigger tap targets, single-column cards, no hover. Triggered
     *  by Mindustry's mobile flag OR by a scene-coord width below 1100.
     *  The threshold is intentionally loose so sub-tablet phones in
     *  landscape and resized desktop windows both get the compact path. */
    private static boolean compactLayout() {
        if (Boolean.getBoolean("bsb.testForceCompact")) return true;
        if (Vars.mobile) return true;
        float w = sceneWidth();
        return w < 1100f;
    }

    /** Stage / scene width in UI coordinates. Falls back to a Scl-divided
     *  pixel width when the scene isn't attached yet (early init paths).
     *  Honors `-Dbsb.testWidth=...` so desktop screenshot tests can pretend
     *  the stage is phone-sized. */
    private static float sceneWidth() {
        String forced = System.getProperty("bsb.testWidth");
        if (forced != null && !forced.isEmpty()) {
            try { return Float.parseFloat(forced); } catch (Exception ignored) {}
        }
        if (Core.scene != null && Core.scene.getWidth() > 1f) return Core.scene.getWidth();
        float uiScale = Scl.scl(1f);
        float pxW = Core.graphics.getWidth();
        return uiScale > 0f ? pxW / uiScale : pxW;
    }

    // ============================================================
    // Mod entry point
    // ============================================================
    @Override
    public void init() {
        Events.on(mindustry.game.EventType.ClientLoadEvent.class, e -> {
            // Test hook: shrink the window to phone-ish dimensions before
            // the browser opens so the screenshot loop sees the real
            // compact layout. Gated; ignored without -Dbsb.testWidth.
            String tw = System.getProperty("bsb.testWidth");
            String th = System.getProperty("bsb.testHeight");
            if (tw != null && th != null) {
                try { Core.graphics.setWindowSize(Integer.parseInt(tw), Integer.parseInt(th)); }
                catch (Throwable ignored) {}
            }
            try { addReconnectMenuCard(); } catch (Throwable t) { arc.util.Log.err("[bsb] addReconnectMenuCard failed", t); }
            try { installServerBrowserMenu(); } catch (Throwable t) { arc.util.Log.err("[bsb] installServerBrowserMenu failed", t); }
            // Test hook: open the browser dialog immediately on game load so
            // screenshot loops don't have to drive the main menu. Gated by
            // a system property OR a marker file (Boolean.getBoolean is
            // unreachable on Android, so the marker file lets adb trigger
            // it from a desktop test rig).
            boolean autoOpen = Boolean.getBoolean("bsb.testAutoOpen");
            if (!autoOpen) {
                try {
                    arc.files.Fi data = arc.Core.settings.getDataDirectory();
                    if (data != null && data.child(".bsb-test").exists()) autoOpen = true;
                } catch (Throwable ignored) {}
            }
            if (autoOpen) {
                // Ensure vanilla join's "pick a name first" gate doesn't block
                // dialog mounts during automated tests.
                try {
                    String n = Core.settings.getString("name", "");
                    if (n == null || n.isEmpty()) Core.settings.put("name", "BSBTest");
                } catch (Throwable ignored) {}
                arc.Core.app.post(() -> {
                    try { showServerBrowser(); } catch (Throwable t) { arc.util.Log.err("[bsb] auto-open failed", t); }
                });
            }
        });
        // Persist last successful connection so the menu Reconnect tile
        // works across game restarts.
        Events.on(mindustry.game.EventType.ClientServerConnectEvent.class, e -> {
            if (e.ip == null || e.ip.isEmpty()) return;
            Core.settings.put("bsb.lastIp", e.ip);
            Core.settings.put("bsb.lastPort", e.port);
            recordRecentConnection(e.ip, e.port);
        });
    }

    // ============================================================
    // BrowserEntry
    // ============================================================
    /** One row in the browser — combines source identity + Host ping result. */
    private static class BrowserEntry {
        String source;
        String groupName;
        String ip;
        int port;
        mindustry.net.Host host;
        long lastPingedMs;
        boolean pinging;
        boolean favorite;
        final IntSeq pingHistory = new IntSeq();
        int pingStep = 0;
        long nextPingAt = 0L;
    }

    // ============================================================
    // Hover-painting Table subclass
    // ============================================================
    /** Custom Table for the server browser list. Paints a single row-
     *  spanning hover wash UNDER its cell children when a row's entry
     *  matches {@code hoveredBrowserEntry}. Static inner — d8 (Android
     *  dexer) chokes on the synthetic this$0 reference of non-static
     *  inner classes that extend Mindustry framework types. */
    private static final class BrowserListTable extends Table {
        private final BetterServerBrowser owner;
        BrowserListTable(BetterServerBrowser owner){ this.owner = owner; }
        @Override
        public void draw() {
            super.draw();
            owner.tickBrowserHover();
            owner.paintRowHover(this);
        }
    }

    private void paintRowHover(Table table) {
        if (hoveredBrowserEntry == null) return;
        Label anchor = rowAnchorByEntry.get(hoveredBrowserEntry);
        if (anchor == null || anchor.getScene() == null) return;
        arc.math.geom.Vec2 av = anchor.localToStageCoordinates(arc.util.Tmp.v1.set(0f, 0f));
        arc.math.geom.Vec2 tv = table.localToStageCoordinates(arc.util.Tmp.v2.set(0f, 0f));
        float rowY = av.y - 2f;
        float rowH = anchor.getHeight() + 4f;
        float rowX = tv.x;
        float rowW = table.getWidth();
        float parentA = arc.graphics.g2d.Draw.getColor().a;
        arc.graphics.g2d.Draw.color(1f, 1f, 1f, 0.10f * parentA);
        arc.graphics.g2d.Fill.crect(rowX, rowY, rowW, rowH);
        arc.graphics.g2d.Draw.color();
    }

    // ============================================================
    // Config persistence
    // ============================================================
    private void loadServersConfig() {
        if (Core.settings == null) return;
        String csv = Core.settings.getString("bsb.modeFilter", "");
        cfgServersModeFilter.clear();
        if (!csv.isEmpty()) for (String m : csv.split(",")) {
            String t = m.trim().toLowerCase();
            if (!t.isEmpty()) cfgServersModeFilter.add(t);
        }
        cfgServersMaxPing = Core.settings.getInt("bsb.maxPing", cfgServersMaxPing);
        cfgServersSort = Core.settings.getString("bsb.sort", cfgServersSort);
        cfgServersSortDesc = Core.settings.getBool("bsb.sortDesc", cfgServersSortDesc);
        cfgServersGroupBy = Core.settings.getString("bsb.groupBy", cfgServersGroupBy);
        cfgServersShowEmpty = Core.settings.getBool("bsb.showEmpty", cfgServersShowEmpty);
        cfgServersSearch = Core.settings.getString("bsb.search", cfgServersSearch);
        recentConnections.clear();
        String rcsv = Core.settings.getString("bsb.recent", "");
        if (!rcsv.isEmpty()) for (String e : rcsv.split(",")) {
            String t = e.trim();
            if (!t.isEmpty()) recentConnections.add(t);
        }
        loadFavoritesFromVanilla();
    }

    private void saveServersConfig() {
        if (Core.settings == null) return;
        StringBuilder mf = new StringBuilder();
        for (String s : cfgServersModeFilter) {
            if (mf.length() > 0) mf.append(',');
            mf.append(s);
        }
        Core.settings.put("bsb.modeFilter", mf.toString());
        Core.settings.put("bsb.maxPing", cfgServersMaxPing);
        Core.settings.put("bsb.sort", cfgServersSort);
        Core.settings.put("bsb.sortDesc", cfgServersSortDesc);
        Core.settings.put("bsb.groupBy", cfgServersGroupBy);
        Core.settings.put("bsb.showEmpty", cfgServersShowEmpty);
        Core.settings.put("bsb.search", cfgServersSearch == null ? "" : cfgServersSearch);
        StringBuilder rc = new StringBuilder();
        for (int i = 0; i < recentConnections.size; i++) {
            if (rc.length() > 0) rc.append(',');
            rc.append(recentConnections.get(i));
        }
        Core.settings.put("bsb.recent", rc.toString());
    }

    @SuppressWarnings("unchecked")
    private void loadFavoritesFromVanilla() {
        try {
            Seq<mindustry.ui.dialogs.JoinDialog.Server> vanilla =
                Core.settings.getJson("servers", Seq.class,
                    mindustry.ui.dialogs.JoinDialog.Server.class, Seq::new);
            ObjectSet<String> seen = new ObjectSet<>();
            Seq<String> ordered = new Seq<>();
            for (String key : cfgServersFavorites) {
                for (mindustry.ui.dialogs.JoinDialog.Server s : vanilla) {
                    if ((s.ip + ":" + s.port).equals(key)) {
                        ordered.add(key);
                        seen.add(key);
                        break;
                    }
                }
            }
            for (mindustry.ui.dialogs.JoinDialog.Server s : vanilla) {
                String key = s.ip + ":" + s.port;
                if (!seen.contains(key)) ordered.add(key);
            }
            cfgServersFavorites.clear();
            cfgServersFavorites.addAll(ordered);
        } catch (Throwable t) {
            arc.util.Log.err("[bsb] reading vanilla 'servers' favorites failed", t);
        }
    }

    private void saveFavoritesToVanilla() {
        try {
            Seq<mindustry.ui.dialogs.JoinDialog.Server> out = new Seq<>();
            for (String key : cfgServersFavorites) {
                int idx = key.lastIndexOf(':');
                if (idx <= 0) continue;
                mindustry.ui.dialogs.JoinDialog.Server s =
                    new mindustry.ui.dialogs.JoinDialog.Server();
                s.ip = key.substring(0, idx);
                try { s.port = Integer.parseInt(key.substring(idx + 1)); }
                catch (Exception ex) { continue; }
                out.add(s);
            }
            Core.settings.putJson("servers", mindustry.ui.dialogs.JoinDialog.Server.class, out);
        } catch (Throwable t) {
            arc.util.Log.err("[bsb] writing vanilla 'servers' favorites failed", t);
        }
    }

    private boolean isFavorite(String ip, int port) {
        return cfgServersFavorites.contains(ip + ":" + port);
    }

    private void toggleFavorite(BrowserEntry e) {
        String key = e.ip + ":" + e.port;
        if (cfgServersFavorites.contains(key)) cfgServersFavorites.remove(key);
        else cfgServersFavorites.add(key);
        e.favorite = cfgServersFavorites.contains(key);
        saveFavoritesToVanilla();
        refreshBrowserRows();
    }

    private void moveFavorite(String key, int delta) {
        int i = cfgServersFavorites.indexOf(key);
        int j = i + delta;
        if (i < 0 || j < 0 || j >= cfgServersFavorites.size) return;
        cfgServersFavorites.set(i, cfgServersFavorites.get(j));
        cfgServersFavorites.set(j, key);
        saveFavoritesToVanilla();
        refreshBrowserRows();
    }

    private void recordRecentConnection(String ip, int port) {
        if (ip == null || ip.isEmpty() || port <= 0) return;
        String key = ip + ":" + port;
        recentConnections.remove(key);
        recentConnections.insert(0, key);
        while (recentConnections.size > RECENT_LIMIT) recentConnections.remove(recentConnections.size - 1);
        saveServersConfig();
    }

    // ============================================================
    // Vanilla JoinDialog interception
    // ============================================================
    private void installServerBrowserMenu() {
        if (Vars.ui == null) return;
        loadServersConfig();
        Events.run(Trigger.update, () -> {
            if (Vars.ui == null || Vars.ui.join == null) return;
            tickBrowserPings();
            if (!Vars.ui.join.isShown()) return;
            if (serversDialog != null && serversDialog.isShown()) return;
            Vars.ui.join.hide();
            showServerBrowser();
        });
    }

    // ============================================================
    // Main browser dialog
    // ============================================================
    private void showServerBrowser() {
        if (serversDialog != null && serversDialog.isShown()) return;
        loadServersConfig();
        serversDialog = new BaseDialog("Server browser");
        // Fill the stage before any cell sizing runs so .growX() on inner
        // cells gets a real width to grow into. Setting fillParent after
        // show() left the dialog at a content-fit size on some Android
        // devices, which is why the toolbar overflowed off-screen.
        serversDialog.setFillParent(true);
        serversDialog.cont.top();
        // BaseDialog's outer table wraps cont in a cell with non-zero pad
        // (default ~6) which produces the visible "gap" below the title
        // and around our content. Zero the cell + cont's defaults so rows
        // sit flush.
        try {
            @SuppressWarnings("rawtypes")
            arc.scene.ui.layout.Cell contCell = serversDialog.getCell(serversDialog.cont);
            if (contCell != null) contCell.pad(0f);
        } catch (Throwable ignored) {}
        serversDialog.cont.defaults().growX().pad(0f);

        boolean compact = compactLayout();
        float btnH = compact ? 44f : 28f;
        float searchH = compact ? 44f : 32f;
        float toolbarPad = compact ? 8f : 6f;

        int variant = getLayoutVariant();
        if (!compact) variant = 1;
        switch (variant) {
            case 2: buildToolbarV2(btnH, searchH, toolbarPad); break;
            case 3: buildToolbarV3(btnH, searchH, toolbarPad); break;
            case 4: buildToolbarV4(btnH, searchH, toolbarPad); break;
            case 5: buildToolbarV5(btnH, searchH, toolbarPad); break;
            default: buildToolbarV1(compact, btnH, searchH, toolbarPad); break;
        }

        Table list = new BrowserListTable(this);
        list.top().left();
        // Pane must not scroll horizontally — content extending past the
        // viewport (long server names, accidental wide cells) would
        // otherwise drag cont's prefWidth out and leave the right side of
        // the dialog showing the menu underneath.
        @SuppressWarnings("rawtypes")
        arc.scene.ui.layout.Cell paneCell = serversDialog.cont.pane(list).grow().maxWidth(sceneWidth());
        paneCell.pad(0f);
        arc.scene.ui.ScrollPane spane = (arc.scene.ui.ScrollPane) paneCell.get();
        spane.setScrollingDisabled(true, false);
        spane.setForceScroll(false, true);
        serversDialog.cont.row();
        browserList = list;

        serversDialog.addCloseButton();
        serversDialog.show();
        if (Core.scene != null) {
            float sw = Core.scene.getWidth();
            float sh = Core.scene.getHeight();
            serversDialog.setSize(sw, sh);
            serversDialog.setPosition(0f, 0f);
        }
        serversDialog.invalidateHierarchy();

        collectAndPingServers();
    }

    /** Read the desktop test variant from system property
     *  `bsb.testLayout` (1..5), or — on Android where -D properties don't
     *  reach dex'd code — from a marker file `.bsb-layout` under the
     *  Mindustry data dir whose first byte is the digit 1..5. Default 3
     *  (collapsible Filters panel — gives the most server-list space). */
    private int getLayoutVariant() {
        try {
            String prop = System.getProperty("bsb.testLayout");
            if (prop != null && !prop.isEmpty()) {
                int v = Integer.parseInt(prop.trim());
                if (v >= 1 && v <= 5) return v;
            }
        } catch (Exception ignored) {}
        try {
            arc.files.Fi data = arc.Core.settings.getDataDirectory().child(".bsb-layout");
            if (data.exists()) {
                String s = data.readString().trim();
                if (!s.isEmpty()) {
                    int v = Integer.parseInt(s.substring(0, 1));
                    if (v >= 1 && v <= 5) return v;
                }
            }
        } catch (Throwable ignored) {}
        return 3;
    }

    /** Make the search field — shared by every layout variant. */
    private arc.scene.ui.TextField makeSearchField() {
        arc.scene.ui.TextField search = new arc.scene.ui.TextField(cfgServersSearch);
        search.setMessageText("server name…");
        search.changed(() -> {
            cfgServersSearch = search.getText();
            saveServersConfig();
            refreshBrowserRows();
        });
        return search;
    }

    /** Refresh + Custom buttons. Caller owns layout. Returns the buttons so
     *  layouts can stick them in different parents. */
    private TextButton makeRefreshBtn() {
        TextButton b = new TextButton("↻ Refresh", Styles.defaultt);
        b.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
        b.clicked(this::collectAndPingServers);
        return b;
    }

    private TextButton makeCustomBtn() {
        TextButton b = new TextButton("+ Custom", Styles.defaultt);
        b.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
        b.clicked(this::showCustomConnectDialog);
        return b;
    }

    /** Single-glyph icon button for tight toolbars. */
    private TextButton makeIconBtn(char glyph, String tooltip, Runnable onClick) {
        TextButton b = new TextButton(String.valueOf(glyph), Styles.defaultt);
        b.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
        b.clicked(() -> onClick.run());
        if (tooltip != null && !tooltip.isEmpty()) addTooltip(b, tooltip);
        return b;
    }

    /** Builds the chip pane (mode chips, h-scrollable). */
    private void addModeChipPane(Table parent, float toolbarPad, float height) {
        modeChipBar = new Table();
        modeChipBar.defaults().padRight(6f);
        arc.scene.ui.ScrollPane chipPane = new arc.scene.ui.ScrollPane(modeChipBar);
        chipPane.setScrollingDisabled(false, true);
        chipPane.setOverscroll(false, false);
        chipPane.setFadeScrollBars(true);
        parent.add(chipPane).left().growX().maxWidth(sceneWidth() - 12f).height(height).padBottom(toolbarPad).row();
        rebuildModeChips();
    }

    /** Slider + value label, full-width row. */
    private void addSliderRow(Table parent, float toolbarPad, boolean compact) {
        Table tb = new Table();
        tb.left();
        tb.add("Max ping").padRight(6f);
        arc.scene.ui.Slider sl = new arc.scene.ui.Slider(50, 999, 10, false);
        sl.setValue(cfgServersMaxPing);
        Label v = new Label(cfgServersMaxPing + " ms");
        sl.changed(() -> {
            cfgServersMaxPing = (int) sl.getValue();
            v.setText(cfgServersMaxPing + " ms");
            saveServersConfig();
            refreshBrowserRows();
        });
        if (compact) {
            tb.add(sl).growX().height(34f);
            tb.add(v).padLeft(8f).width(70f);
        } else {
            tb.add(sl).width(260f);
            tb.add(v).padLeft(6f).width(80f);
        }
        parent.add(tb).left().growX().padBottom(toolbarPad).row();
    }

    private CheckBox makeShowEmptyCheckbox() {
        CheckBox cb = new CheckBox(" Show empty servers");
        cb.setChecked(cfgServersShowEmpty);
        cb.changed(() -> { cfgServersShowEmpty = cb.isChecked(); saveServersConfig(); refreshBrowserRows(); });
        return cb;
    }

    // ============================================================
    // Layout V1 — current baseline (search / group / refresh+custom /
    // mode-chips / slider / show-empty separate rows). Stable, well-known.
    // ============================================================
    private void buildToolbarV1(boolean compact, float btnH, float searchH, float toolbarPad) {
        Table tb1 = new Table();
        tb1.defaults().padRight(8f);
        tb1.add("Search").padRight(4f);
        arc.scene.ui.TextField search = makeSearchField();
        if (compact) {
            tb1.add(search).growX().height(searchH);
            serversDialog.cont.add(tb1).left().growX().padBottom(toolbarPad).row();
            Table tb2 = new Table();
            tb2.defaults().padRight(6f);
            tb2.left();
            tb2.add("Group:").padRight(6f);
            addGroupChips(tb2, btnH, true);
            serversDialog.cont.add(tb2).left().padBottom(toolbarPad).row();
            Table tb3 = new Table();
            tb3.defaults().padRight(8f);
            tb3.left();
            tb3.add(makeRefreshBtn()).height(btnH).growX();
            tb3.add(makeCustomBtn()).height(btnH).growX();
            serversDialog.cont.add(tb3).left().growX().padBottom(toolbarPad).row();
        } else {
            tb1.add(search).width(260f).height(searchH);
            tb1.add("Group:").padLeft(16f).padRight(4f);
            addGroupChips(tb1, btnH, false);
            tb1.add(makeRefreshBtn()).padLeft(16f).width(measureButtonWidth(makeRefreshBtn())).height(btnH);
            tb1.add(makeCustomBtn()).width(measureButtonWidth(makeCustomBtn())).height(btnH);
            serversDialog.cont.add(tb1).left().padBottom(toolbarPad).row();
        }
        addModeChipPane(serversDialog.cont, toolbarPad, 120f);
        addSliderRow(serversDialog.cont, toolbarPad, compact);
        serversDialog.cont.add(makeShowEmptyCheckbox()).left().padBottom(toolbarPad).row();
    }

    // ============================================================
    // Layout V2 — Two-row tight: search + icon refresh + icon custom on
    // row 1; group chips + mode chips fused into one h-scroll strip on
    // row 2. Slider + show-empty share row 3 (slider growX, empty inline).
    // ============================================================
    private void buildToolbarV2(float btnH, float searchH, float toolbarPad) {
        Table tb1 = new Table();
        tb1.defaults().padRight(6f);
        tb1.add(makeSearchField()).growX().height(searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.refresh, "Refresh", this::collectAndPingServers))
            .size(searchH, searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.add, "Add custom server", this::showCustomConnectDialog))
            .size(searchH, searchH);
        serversDialog.cont.add(tb1).left().growX().padBottom(toolbarPad).row();

        // Combined chip strip: group toggles up front + mode chips after.
        modeChipBar = new Table();
        modeChipBar.defaults().padRight(6f);
        arc.scene.ui.ScrollPane chipPane = new arc.scene.ui.ScrollPane(modeChipBar);
        chipPane.setScrollingDisabled(false, true);
        chipPane.setOverscroll(false, false);
        chipPane.setFadeScrollBars(true);
        serversDialog.cont.add(chipPane).left().growX().maxWidth(sceneWidth() - 12f).height(120f).padBottom(toolbarPad).row();
        rebuildModeChipsV2();

        Table tb3 = new Table();
        tb3.left();
        tb3.add("Max ping").padRight(6f);
        arc.scene.ui.Slider sl = new arc.scene.ui.Slider(50, 999, 10, false);
        sl.setValue(cfgServersMaxPing);
        Label v = new Label(cfgServersMaxPing + " ms");
        sl.changed(() -> { cfgServersMaxPing = (int) sl.getValue(); v.setText(cfgServersMaxPing + " ms"); saveServersConfig(); refreshBrowserRows(); });
        tb3.add(sl).growX().height(34f);
        tb3.add(v).padLeft(6f).width(70f);
        tb3.add(makeShowEmptyCheckbox()).padLeft(12f);
        serversDialog.cont.add(tb3).left().growX().padBottom(toolbarPad).row();
    }

    private void rebuildModeChipsV2() {
        if (modeChipBar == null) return;
        modeChipBar.clear();
        modeChipBar.left();
        addGroupChips(modeChipBar, 56f, true);
        modeChipBar.add(new Label("[#666]│[]")).padLeft(8f).padRight(8f);
        rebuildModeChipsCommon();
    }

    // ============================================================
    // Layout V3 — Collapsible Filters panel: row 1 has search, refresh,
    // custom, and a "Filters ▾" toggle. Filters panel (group/modes/slider/
    // show-empty) hides by default; tap toggle to expand/collapse.
    // ============================================================
    private boolean filtersV3Expanded = false;
    private Table filtersV3Panel;

    private void buildToolbarV3(float btnH, float searchH, float toolbarPad) {
        filtersV3Expanded = false; // always start collapsed when (re)opening
        Table tb1 = new Table();
        tb1.defaults().padRight(6f);
        tb1.add(makeSearchField()).growX().height(searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.refresh, "Refresh", this::collectAndPingServers))
            .size(searchH, searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.add, "Add custom server", this::showCustomConnectDialog))
            .size(searchH, searchH);
        // Square icon button to match the other two — keeps the toolbar
        // visually uniform. Glyph swaps between settings and back-arrow
        // (open/close) so users see the toggle state without text labels.
        TextButton filtersToggle = new TextButton(String.valueOf(mindustry.gen.Iconc.settings), Styles.defaultt);
        filtersToggle.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
        addTooltip(filtersToggle, "Filters");
        tb1.add(filtersToggle).size(searchH, searchH);
        serversDialog.cont.add(tb1).left().growX().pad(0f).row();

        filtersV3Panel = new Table();
        filtersV3Panel.defaults().growX().pad(0f);
        filtersV3Panel.left();
        // Compact filter content — group toggles fused into chip strip,
        // slider+show-empty share a row. Two rows total.
        modeChipBar = new Table();
        modeChipBar.defaults().padRight(6f);
        arc.scene.ui.ScrollPane innerChipPane = new arc.scene.ui.ScrollPane(modeChipBar);
        innerChipPane.setScrollingDisabled(false, true);
        innerChipPane.setOverscroll(false, false);
        innerChipPane.setFadeScrollBars(true);
        filtersV3Panel.add(innerChipPane).left().growX().maxWidth(sceneWidth() - 12f).height(64f).pad(0f).row();
        rebuildModeChipsV3();

        Table sliderRow = new Table();
        sliderRow.left();
        sliderRow.add("Max ping").padRight(6f);
        arc.scene.ui.Slider sl = new arc.scene.ui.Slider(50, 999, 10, false);
        sl.setValue(cfgServersMaxPing);
        Label v = new Label(cfgServersMaxPing + " ms");
        sl.changed(() -> { cfgServersMaxPing = (int) sl.getValue(); v.setText(cfgServersMaxPing + " ms"); saveServersConfig(); refreshBrowserRows(); });
        sliderRow.add(sl).growX().height(34f);
        sliderRow.add(v).padLeft(6f).width(70f);
        sliderRow.add(makeShowEmptyCheckbox()).padLeft(12f);
        filtersV3Panel.add(sliderRow).left().growX().pad(0f).row();

        // Add panel directly to cont — no ScrollPane wrap. Content is
        // short (2 rows ~150 px); scroll wasn't actually needed and the
        // pane added invisible top/bottom decoration that produced the
        // visible "gap" on phones.
        @SuppressWarnings("rawtypes")
        final arc.scene.ui.layout.Cell cellRef =
            serversDialog.cont.add(filtersV3Panel).left().growX().pad(0f);
        serversDialog.cont.row();
        Runnable applyVisibility = () -> {
            filtersV3Panel.visible = filtersV3Expanded;
            if (filtersV3Expanded) {
                float pref = filtersV3Panel.getPrefHeight();
                cellRef.height(pref).pad(0f);
            } else {
                cellRef.size(0f, 0f).pad(0f);
            }
            serversDialog.cont.invalidateHierarchy();
        };
        applyVisibility.run();
        filtersToggle.clicked(() -> {
            filtersV3Expanded = !filtersV3Expanded;
            filtersToggle.setText(String.valueOf(filtersV3Expanded ? mindustry.gen.Iconc.cancel : mindustry.gen.Iconc.settings));
            applyVisibility.run();
        });
    }

    // ============================================================
    // Layout V4 — Single mega-strip: search + icon buttons on row 1; one
    // long horizontal-scroll strip below combining group / sort / modes /
    // show-empty into one tap-to-toggle list. No slider row.
    // ============================================================
    private void buildToolbarV4(float btnH, float searchH, float toolbarPad) {
        Table tb1 = new Table();
        tb1.defaults().padRight(6f);
        tb1.add(makeSearchField()).growX().height(searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.refresh, "Refresh", this::collectAndPingServers))
            .size(searchH, searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.add, "Custom", this::showCustomConnectDialog))
            .size(searchH, searchH);
        serversDialog.cont.add(tb1).left().growX().padBottom(toolbarPad).row();

        // One long h-scroll strip with everything packed in.
        modeChipBar = new Table();
        modeChipBar.defaults().padRight(6f);
        arc.scene.ui.ScrollPane chipPane = new arc.scene.ui.ScrollPane(modeChipBar);
        chipPane.setScrollingDisabled(false, true);
        chipPane.setOverscroll(false, false);
        chipPane.setFadeScrollBars(true);
        serversDialog.cont.add(chipPane).left().growX().maxWidth(sceneWidth() - 12f).height(120f).padBottom(toolbarPad).row();
        rebuildModeChipsV4();

        addSliderRow(serversDialog.cont, toolbarPad, true);
    }

    private void rebuildModeChipsV4() {
        if (modeChipBar == null) return;
        modeChipBar.clear();
        modeChipBar.left();
        addGroupChips(modeChipBar, 56f, true);
        modeChipBar.add(new Label("[#666]│[]")).padLeft(8f).padRight(8f);
        // Show-empty as a toggle chip
        TextButton emptyChip = new TextButton(" Show empty ", Styles.flatTogglet);
        emptyChip.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
        emptyChip.update(() -> emptyChip.setChecked(cfgServersShowEmpty));
        emptyChip.clicked(() -> { cfgServersShowEmpty = !cfgServersShowEmpty; saveServersConfig(); refreshBrowserRows(); });
        modeChipBar.add(emptyChip).height(56f);
        modeChipBar.add(new Label("[#666]│[]")).padLeft(8f).padRight(8f);
        rebuildModeChipsCommon();
    }

    // ============================================================
    // Layout V5 — Bottom-sheet style: row 1 has search + icon refresh +
    // icon custom + ⚙ Settings icon. Tap settings opens a separate
    // dialog with all filters. Row 2 = mode chip strip only. Maximum list
    // space, fewest fixed rows.
    // ============================================================
    private void buildToolbarV5(float btnH, float searchH, float toolbarPad) {
        Table tb1 = new Table();
        tb1.defaults().padRight(6f);
        tb1.add(makeSearchField()).growX().height(searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.refresh, "Refresh", this::collectAndPingServers))
            .size(searchH, searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.add, "Custom", this::showCustomConnectDialog))
            .size(searchH, searchH);
        tb1.add(makeIconBtn(mindustry.gen.Iconc.settings, "Filters", this::showFiltersDialog))
            .size(searchH, searchH);
        serversDialog.cont.add(tb1).left().growX().padBottom(toolbarPad).row();
        addModeChipPane(serversDialog.cont, toolbarPad, 120f);
    }

    private void showFiltersDialog() {
        BaseDialog d = new BaseDialog("Filters");
        d.cont.defaults().growX().padBottom(8f);
        Table groupRow = new Table();
        groupRow.left();
        groupRow.add("Group:").padRight(6f);
        addGroupChips(groupRow, 44f, true);
        d.cont.add(groupRow).left().row();
        addSliderRow(d.cont, 8f, true);
        d.cont.add(makeShowEmptyCheckbox()).left().row();
        d.addCloseButton();
        d.show();
    }

    /** Append the three group-toggle chips (none / mode / group) to a row.
     *  Extracted so the compact and desktop toolbar layouts can build the
     *  same chips into different parent tables. */
    private void addGroupChips(Table row, float btnH, boolean compact) {
        String[] groupKeys = {"none", "mode", "group"};
        char[] groupGlyphs = {
            mindustry.gen.Iconc.cancel,
            mindustry.gen.Iconc.modeAttack,
            mindustry.gen.Iconc.commandRally
        };
        for (int gi = 0; gi < groupKeys.length; gi++) {
            String key = groupKeys[gi];
            char glyph = groupGlyphs[gi];
            TextButton chip = new TextButton(String.valueOf(glyph), Styles.defaultt);
            chip.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
            chip.update(() -> chip.setColor(cfgServersGroupBy.equals(key)
                ? new Color(0.4f, 0.9f, 0.4f, 1f) : Color.white));
            chip.clicked(() -> { cfgServersGroupBy = key; saveServersConfig(); refreshBrowserRows(); });
            addTooltip(chip, "Group: " + key);
            float w = Math.max(measureButtonWidth(chip), compact ? 56f : 0f);
            row.add(chip).width(w).height(btnH);
        }
    }

    // ============================================================
    // Mode chip bar
    // ============================================================
    private void rebuildModeChips() {
        if (modeChipBar == null) return;
        // V2 / V3 / V4 prepend extra chips before mode chips (group toggles
        // + separators + show-empty toggle); dispatch so refreshBrowserRows()
        // re-renders the variant's full strip when filters change.
        int variant = getLayoutVariant();
        if (variant == 2) { rebuildModeChipsV2(); return; }
        if (variant == 3) { rebuildModeChipsV3(); return; }
        if (variant == 4) { rebuildModeChipsV4(); return; }
        modeChipBar.clear();
        modeChipBar.left();
        modeChipBar.add("Modes:").padRight(6f).padLeft(6f);
        rebuildModeChipsCommon();
    }

    /** V3 chip strip variant — group toggles fused inline before mode chips. */
    private void rebuildModeChipsV3() {
        if (modeChipBar == null) return;
        modeChipBar.clear();
        modeChipBar.left();
        addGroupChips(modeChipBar, 56f, true);
        modeChipBar.add(new Label("[#666]│[]")).padLeft(8f).padRight(8f);
        rebuildModeChipsCommon();
    }

    /** Append the actual mode toggle chips to {@code modeChipBar}. Used by
     *  every layout — the surrounding cells (label, group chips, dividers,
     *  show-empty toggle) are added by the variant-specific builder. */
    private void rebuildModeChipsCommon() {
        ObjectMap<String, String> keyToDisplay = new ObjectMap<>();
        for (mindustry.game.Gamemode g : mindustry.game.Gamemode.values()) {
            keyToDisplay.put(g.name().toLowerCase(), capitalize(g.name()));
        }
        for (BrowserEntry be : browserEntries) {
            if (be.host == null) continue;
            String disp = modeKey(be.host);
            String k = disp.toLowerCase();
            if (!keyToDisplay.containsKey(k)) keyToDisplay.put(k, disp);
        }
        Seq<String> ordered = new Seq<>();
        for (mindustry.game.Gamemode g : mindustry.game.Gamemode.values()) {
            String k = g.name().toLowerCase();
            if (keyToDisplay.containsKey(k)) ordered.add(k);
        }
        Seq<String> custom = new Seq<>();
        for (String k : keyToDisplay.keys()) {
            boolean isEnum = false;
            for (mindustry.game.Gamemode g : mindustry.game.Gamemode.values())
                if (g.name().toLowerCase().equals(k)) { isEnum = true; break; }
            if (!isEnum) custom.add(k);
        }
        custom.sort();
        ordered.addAll(custom);
        float padRight = 4f;
        for (String key : ordered) {
            String display = keyToDisplay.get(key);
            TextButton chip = new TextButton(display, Styles.flatTogglet);
            chip.update(() -> chip.setChecked(!cfgServersModeFilter.contains(key)));
            chip.clicked(() -> {
                if (cfgServersModeFilter.contains(key)) cfgServersModeFilter.remove(key);
                else cfgServersModeFilter.add(key);
                saveServersConfig();
                refreshBrowserRows();
            });
            chip.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
            chip.pack();
            float chipW = Math.max(measureButtonWidth(chip), chip.getPrefWidth());
            modeChipBar.add(chip).width(chipW).height(64f).padRight(padRight);
        }
    }

    // ============================================================
    // Custom connect dialog
    // ============================================================
    private void showCustomConnectDialog() {
        BaseDialog d = new BaseDialog("Custom connection");
        d.cont.top();
        d.cont.add("host[:port]").padBottom(4f).row();
        arc.scene.ui.TextField field = new arc.scene.ui.TextField();
        field.setMessageText("e.g. 1.2.3.4:6567");
        d.cont.add(field).width(360f).padBottom(8f).row();

        Table btns = new Table();
        TextButton connect = new TextButton("Connect", Styles.defaultt);
        connect.clicked(() -> {
            String[] parts = parseHostPort(field.getText());
            if (parts == null) return;
            int port = Integer.parseInt(parts[1]);
            d.hide();
            if (serversDialog != null) serversDialog.hide();
            recordRecentConnection(parts[0], port);
            Vars.ui.join.connect(parts[0], port);
        });
        TextButton fav = new TextButton("+ Favorite", Styles.defaultt);
        fav.clicked(() -> {
            String[] parts = parseHostPort(field.getText());
            if (parts == null) return;
            String key = parts[0] + ":" + parts[1];
            if (!cfgServersFavorites.contains(key)) cfgServersFavorites.add(key);
            saveFavoritesToVanilla();
            d.hide();
            collectAndPingServers();
        });
        TextButton cancel = new TextButton("Cancel", Styles.defaultt);
        cancel.clicked(d::hide);
        btns.add(connect).width(110f).padRight(6f);
        btns.add(fav).width(110f).padRight(6f);
        btns.add(cancel).width(80f);
        d.cont.add(btns);
        d.show();
    }

    private static String[] parseHostPort(String input) {
        if (input == null) return null;
        String t = input.trim();
        if (t.isEmpty()) return null;
        int colon = t.lastIndexOf(':');
        String host = t;
        int port = Vars.port;
        if (colon > 0) {
            host = t.substring(0, colon);
            try { port = Integer.parseInt(t.substring(colon + 1)); }
            catch (Exception ex) { return null; }
        }
        return new String[]{host, String.valueOf(port)};
    }

    // ============================================================
    // Server collection + community fetch
    // ============================================================
    private void collectAndPingServers() {
        browserEntries.clear();
        loadFavoritesFromVanilla();
        for (String key : cfgServersFavorites) {
            int idx = key.lastIndexOf(':');
            if (idx <= 0) continue;
            BrowserEntry e = new BrowserEntry();
            e.source = "favorite";
            e.favorite = true;
            e.ip = key.substring(0, idx);
            try { e.port = Integer.parseInt(key.substring(idx + 1)); } catch (Exception ex) { continue; }
            browserEntries.add(e);
            pingBrowserEntry(e);
        }
        for (String key : recentConnections) {
            int idx = key.lastIndexOf(':');
            if (idx <= 0) continue;
            BrowserEntry e = new BrowserEntry();
            e.source = "recent";
            e.ip = key.substring(0, idx);
            try { e.port = Integer.parseInt(key.substring(idx + 1)); } catch (Exception ex) { continue; }
            e.favorite = isFavorite(e.ip, e.port);
            boolean dup = false;
            for (int i = 0; i < browserEntries.size; i++) {
                BrowserEntry be = browserEntries.get(i);
                if (be.ip.equals(e.ip) && be.port == e.port) { dup = true; break; }
            }
            if (dup) continue;
            browserEntries.add(e);
            pingBrowserEntry(e);
        }
        refreshBrowserRows();
        fetchCommunityList(0);
    }

    private void fetchCommunityList(int idx) {
        String[] urls = Vars.serverJsonURLs;
        if (urls == null || idx >= urls.length) return;
        try {
            arc.util.Http.get(urls[idx])
                .error(t -> {
                    arc.util.Log.warn("[bsb] community fetch failed (" + urls[idx] + "): @", t.getMessage());
                    if (idx < urls.length - 1) fetchCommunityList(idx + 1);
                })
                .submit(res -> {
                    final String text = res.getResultAsString();
                    Core.app.post(() -> ingestCommunityJson(text));
                });
        } catch (Throwable t) {
            arc.util.Log.err("[bsb] community fetch threw", t);
        }
    }

    private void ingestCommunityJson(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            arc.util.serialization.Jval val = arc.util.serialization.Jval.read(text);
            val.asArray().each(child -> {
                String name = child.getString("name", "");
                String[] addresses;
                if (child.has("addresses")
                    || (child.has("address") && child.get("address").isArray())) {
                    addresses = (child.has("addresses")
                        ? child.get("addresses") : child.get("address"))
                        .asArray().map(arc.util.serialization.Jval::asString)
                        .toArray(String.class);
                } else {
                    addresses = new String[]{child.getString("address", "")};
                }
                for (String addr : addresses) {
                    if (addr == null || addr.isEmpty()) continue;
                    int port = Vars.port;
                    String ip = addr;
                    int colon = addr.lastIndexOf(':');
                    if (colon > 0) {
                        try {
                            port = Integer.parseInt(addr.substring(colon + 1));
                            ip = addr.substring(0, colon);
                        } catch (Exception ignored) {}
                    }
                    boolean dup = false;
                    for (int i = 0; i < browserEntries.size; i++) {
                        BrowserEntry be = browserEntries.get(i);
                        if (be.ip.equals(ip) && be.port == port) { dup = true; break; }
                    }
                    if (dup) continue;
                    BrowserEntry e = new BrowserEntry();
                    e.source = "community";
                    e.groupName = name;
                    e.ip = ip;
                    e.port = port;
                    e.favorite = isFavorite(e.ip, e.port);
                    browserEntries.add(e);
                    pingBrowserEntry(e);
                }
            });
            arc.util.Log.info("[bsb] community list parsed: @ entries", browserEntries.size);
            refreshBrowserRows();
        } catch (Throwable t) {
            arc.util.Log.err("[bsb] community JSON parse failed", t);
        }
    }

    // ============================================================
    // Ping driver
    // ============================================================
    private void pingBrowserEntry(BrowserEntry e) {
        if (e.pinging || Vars.net == null) return;
        e.pinging = true;
        Vars.net.pingHost(e.ip, e.port, host -> {
            e.host = host;
            e.lastPingedMs = System.currentTimeMillis();
            e.pinging = false;
            int rtt = host != null ? host.ping : -1;
            if (rtt >= 0) {
                e.pingHistory.add(rtt);
                if (e.pingHistory.size > PING_HISTORY_LIMIT) e.pingHistory.removeIndex(0);
            }
            advancePingSchedule(e);
            requestBrowserRowsRefresh();
        }, err -> {
            e.host = null;
            e.pinging = false;
            advancePingSchedule(e);
            requestBrowserRowsRefresh();
        });
    }

    private static void advancePingSchedule(BrowserEntry e) {
        int idx = Math.min(e.pingStep, PING_SCHEDULE_MS.length - 1);
        e.nextPingAt = System.currentTimeMillis() + PING_SCHEDULE_MS[idx];
        if (e.pingStep < PING_SCHEDULE_MS.length - 1) e.pingStep++;
    }

    private void requestBrowserRowsRefresh() {
        if (browserRowsRefreshPending) return;
        browserRowsRefreshPending = true;
        Core.app.post(() -> {
            browserRowsRefreshPending = false;
            refreshBrowserRows();
        });
    }

    private void tickBrowserPings() {
        if (serversDialog == null || !serversDialog.isShown()) return;
        long now = System.currentTimeMillis();
        for (int i = 0; i < browserEntries.size; i++) {
            BrowserEntry e = browserEntries.get(i);
            if (e.pinging) continue;
            if (now >= e.nextPingAt) pingBrowserEntry(e);
        }
    }

    private void tickBrowserHover() {
        if (browserList == null || browserList.getScene() == null) return;
        if (rowAnchorByEntry.size == 0) {
            hoveredBrowserEntry = null;
            return;
        }
        arc.math.geom.Vec2 local = browserList.screenToLocalCoordinates(
            arc.util.Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY()));
        BrowserEntry found = null;
        if (local.x >= 0f && local.x <= browserList.getWidth()) {
            for (ObjectMap.Entry<BrowserEntry, Label> ent : rowAnchorByEntry.entries()) {
                Label l = ent.value;
                float ly = l.y;
                float lh = l.getHeight();
                if (local.y >= ly - 2f && local.y <= ly + lh + 2f) {
                    found = ent.key;
                    break;
                }
            }
        }
        hoveredBrowserEntry = found;
    }

    private static int pingP90(BrowserEntry e) {
        int n = e.pingHistory.size;
        if (n <= 0) return -1;
        pingP90Scratch.clear();
        pingP90Scratch.addAll(e.pingHistory);
        pingP90Scratch.sort();
        int idx = Math.max(0, (int) Math.ceil(0.9 * n) - 1);
        if (idx >= n) idx = n - 1;
        return pingP90Scratch.get(idx);
    }

    // ============================================================
    // Row table refresh + cell rendering
    // ============================================================
    private void refreshBrowserRows() {
        if (browserList == null) return;
        rebuildModeChips();
        rowAnchorByEntry.clear();
        browserList.clear();
        String search = cfgServersSearch == null ? "" : cfgServersSearch.toLowerCase();
        Seq<BrowserEntry> filtered = new Seq<>();
        for (int i = 0; i < browserEntries.size; i++) {
            BrowserEntry e = browserEntries.get(i);
            boolean keep;
            if (e.favorite) keep = true;
            else if (e.host == null) keep = cfgServersShowEmpty;
            else if (hostPing(e) > cfgServersMaxPing) keep = false;
            else if (!cfgServersShowEmpty && e.host.players <= 0) keep = false;
            else if (cfgServersModeFilter.contains(modeKeyLower(e.host))) keep = false;
            else keep = true;
            if (keep && !search.isEmpty()) {
                String n = e.host != null && e.host.name != null
                    ? stripColors(e.host.name).toLowerCase()
                    : (e.ip + ":" + e.port).toLowerCase();
                if (!n.contains(search)) keep = false;
            }
            if (keep) filtered.add(e);
        }
        java.util.Comparator<BrowserEntry> inner;
        switch (cfgServersSort) {
            case "name":
                inner = (a, b) -> compareStr(hostName(a), hostName(b)); break;
            case "max":
                inner = (a, b) -> Integer.compare(hostMax(a), hostMax(b)); break;
            case "ping":
                inner = (a, b) -> Integer.compare(hostPing(a), hostPing(b)); break;
            case "mode":
                inner = (a, b) -> compareStr(
                    a.host != null ? modeKey(a.host) : "",
                    b.host != null ? modeKey(b.host) : ""); break;
            case "map":
                inner = (a, b) -> compareStr(
                    a.host != null ? a.host.mapname : "",
                    b.host != null ? b.host.mapname : ""); break;
            default:
                inner = (a, b) -> Integer.compare(hostPlayers(a), hostPlayers(b)); break;
        }
        if (cfgServersSortDesc) {
            java.util.Comparator<BrowserEntry> base = inner;
            inner = (a, b) -> base.compare(b, a);
        }
        final java.util.Comparator<BrowserEntry> finalInner = inner;
        filtered.sort((a, b) -> {
            String ga = browserGroupKey(a);
            String gb = browserGroupKey(b);
            int c = compareStr(ga, gb);
            if (c != 0) return c;
            return finalInner.compare(a, b);
        });
        boolean compactList = compactLayout();
        if (!compactList) {
            // Desktop column headers (clickable for sort). On compact /
            // mobile we render rows as stacked cards instead, so column
            // headers don't apply — sort options exposed via a small
            // sort row right after the toolbar.
            addSortHeader(browserList, "Server",                                       "name",    false);
            addSortHeader(browserList, mindustry.gen.Iconc.players + " Players ",      "players", true);
            addSortHeader(browserList, mindustry.gen.Iconc.modeAttack + " Mode",       "mode",    false);
            addSortHeader(browserList, mindustry.gen.Iconc.chartBar + " Ping",         "ping",    false);
            addSortHeader(browserList, mindustry.gen.Iconc.tree + " Map",              "map",     false);
            browserList.add(); browserList.add(); browserList.add();
            browserList.row();
        } else {
            // Compact: a single row of sort glyph-buttons. Tap one to
            // re-sort the card list. Glyph-only keeps the row narrow.
            Table sortBar = new Table();
            sortBar.left();
            String[] sortKeys = {"name","players","ping","mode","map"};
            char[] sortGlyphs = {
                mindustry.gen.Iconc.list,
                mindustry.gen.Iconc.players,
                mindustry.gen.Iconc.chartBar,
                mindustry.gen.Iconc.modeAttack,
                mindustry.gen.Iconc.tree
            };
            for (int i = 0; i < sortKeys.length; i++) {
                String key = sortKeys[i];
                char gl = sortGlyphs[i];
                TextButton sb = new TextButton(String.valueOf(gl), Styles.defaultt);
                sb.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
                sb.update(() -> sb.setColor(cfgServersSort.equals(key)
                    ? new Color(0.4f, 0.9f, 0.4f, 1f) : Color.white));
                sb.clicked(() -> {
                    if (cfgServersSort.equals(key)) cfgServersSortDesc = !cfgServersSortDesc;
                    else { cfgServersSort = key; cfgServersSortDesc = "players".equals(key); }
                    saveServersConfig();
                    refreshBrowserRows();
                });
                sortBar.add(sb).width(48f).height(44f).padRight(4f);
            }
            browserList.add(sortBar).colspan(8).left().padBottom(6f).row();
        }

        if (cfgServersFavorites.size > 0) {
            Label favHdr = new Label("[gold]★ Favorites[]");
            enableMarkup(favHdr);
            browserList.add(favHdr).colspan(8).left().padTop(4f).padBottom(2f).row();
            int favsRendered = 0;
            for (String key : cfgServersFavorites) {
                BrowserEntry match = null;
                for (int i = 0; i < filtered.size; i++) {
                    BrowserEntry be = filtered.get(i);
                    if ((be.ip + ":" + be.port).equals(key)) { match = be; break; }
                }
                if (match != null) {
                    appendBrowserRowCells(browserList, match);
                    filtered.remove(match, true);
                    favsRendered++;
                }
            }
            // Visual separator + dim 'Other servers' header so favorites
            // are clearly cut off from the rest of the list. Without this,
            // a starred server and a community one bleed into each other
            // when group=none.
            if (favsRendered > 0 && !filtered.isEmpty()) {
                Label sep = new Label("[#444]──────────────[]");
                enableMarkup(sep);
                browserList.add(sep).colspan(8).left().padTop(8f).padBottom(2f).row();
                Label otherHdr = new Label("[#888]Other servers[]");
                enableMarkup(otherHdr);
                browserList.add(otherHdr).colspan(8).left().padBottom(4f).row();
            }
        }

        boolean grouping = !"none".equals(cfgServersGroupBy);
        String lastGroup = null;
        for (BrowserEntry e : filtered) {
            String groupKey = browserGroupKey(e);
            if (grouping && "[ pinging ]".equals(groupKey)) continue;
            if (grouping && !groupKey.equals(lastGroup)) {
                lastGroup = groupKey;
                Label hdr = new Label("[#cccccc]" + browserGroupDisplay(e) + "[]");
                enableMarkup(hdr);
                browserList.add(hdr).colspan(8).left().padTop(8f).padBottom(2f).row();
            }
            appendBrowserRowCells(browserList, e);
        }
        if (filtered.isEmpty() && cfgServersFavorites.size == 0) {
            Label l = new Label("[gray]No servers match the current filters.[]");
            enableMarkup(l);
            browserList.add(l).colspan(8).pad(20f);
        }
    }

    private void appendBrowserRowCells(Table list, BrowserEntry e) {
        boolean compact = compactLayout();
        if (compact) { appendBrowserCardRow(list, e); return; }
        Label name, players, mode, ping, map;
        if (e.host != null) {
            name    = new Label("[white]" + stripColors(e.host.name) + "[]");
            String pcol = e.host.players == 0 ? "[#888]" : "[white]";
            players = new Label(pcol + e.host.players + "[#666]/[white]"
                             + e.host.playerLimit + "[]");
            String mname = e.host.modeName != null ? e.host.modeName
                          : (e.host.mode != null ? capitalize(e.host.mode.name()) : "?");
            mode    = new Label("[#bbbbbb]" + mname + "[]");
            int p90 = pingP90(e);
            int shown = p90 >= 0 ? p90 : e.host.ping;
            ping    = new Label(pingColor(shown) + shown + "ms[]");
            map     = new Label("[#888]" + (e.host.mapname != null ? e.host.mapname : "?") + "[]");
        } else {
            name    = new Label("[#888]" + e.ip + ":" + e.port + "[]");
            players = new Label("[#666]—[]");
            mode    = new Label("[#666]—[]");
            ping    = new Label("[#666]" + (e.pingStep == 0 ? "ping…" : "n/a") + "[]");
            map     = new Label("[#666]—[]");
        }
        for (Label l : new Label[]{name, players, mode, ping, map}) {
            enableMarkup(l);
            l.setAlignment(arc.util.Align.left);
            attachConnectClick(l, e);
        }
        rowAnchorByEntry.put(e, name);

        list.add(name)   .left().padRight(10f).padTop(2f).padBottom(2f);
        list.add(players).left().padRight(10f).padTop(2f).padBottom(2f);
        list.add(mode)   .left().padRight(10f).padTop(2f).padBottom(2f);
        list.add(ping)   .left().padRight(10f).padTop(2f).padBottom(2f);
        list.add(map)    .left().padRight(10f).padTop(2f).padBottom(2f);

        float btnW = 28f;
        float btnH = 24f;
        TextButton starBtn = new TextButton(e.favorite ? "★" : "☆", Styles.cleart);
        starBtn.getLabelCell().pad(0f, 0f, 0f, 0f);
        starBtn.setSize(btnW, btnH);
        starBtn.clicked(() -> toggleFavorite(e));
        list.add(starBtn).size(btnW, btnH).padLeft(2f);

        if (e.favorite && cfgServersFavorites.size >= 2) {
            String key = e.ip + ":" + e.port;
            int idx = cfgServersFavorites.indexOf(key);
            boolean canUp   = idx > 0;
            boolean canDown = idx >= 0 && idx < cfgServersFavorites.size - 1;
            if (canUp) {
                TextButton up = new TextButton("▲", Styles.cleart);
                up.getLabelCell().pad(0f, 0f, 0f, 0f);
                up.setSize(btnW, btnH);
                up.clicked(() -> moveFavorite(key, -1));
                list.add(up).size(btnW, btnH).padLeft(2f);
            } else {
                list.add().size(btnW, btnH).padLeft(2f);
            }
            if (canDown) {
                TextButton down = new TextButton("▼", Styles.cleart);
                down.getLabelCell().pad(0f, 0f, 0f, 0f);
                down.setSize(btnW, btnH);
                down.clicked(() -> moveFavorite(key, +1));
                list.add(down).size(btnW, btnH).padLeft(2f);
            } else {
                list.add().size(btnW, btnH).padLeft(2f);
            }
        } else {
            list.add().size(btnW, btnH).padLeft(2f);
            list.add().size(btnW, btnH).padLeft(2f);
        }
        list.row();
    }

    /** Compact / mobile row: a vertical card with two lines of content
     *  spanning the full table width. Eliminates the 8-column desktop
     *  layout that doesn't fit on phone screens.
     *
     *    line 1: [name (growX, ellipsises overflow)]   [★ fav button]
     *    line 2: [players · mode · ping · map] (small, dim separators)
     *
     *  Tap anywhere on the card → connect. ★ has its own click. ▲/▼
     *  re-order favorites only when this server is starred. */
    private void appendBrowserCardRow(Table list, BrowserEntry e) {
        Label name, players, mode, ping, map;
        if (e.host != null) {
            name    = new Label("[white]" + stripColors(e.host.name) + "[]");
            String pcol = e.host.players == 0 ? "[#888]" : "[white]";
            players = new Label(pcol + e.host.players + "[#666]/[white]"
                             + e.host.playerLimit + "[]");
            String mname = e.host.modeName != null ? e.host.modeName
                          : (e.host.mode != null ? capitalize(e.host.mode.name()) : "?");
            mode    = new Label("[#bbbbbb]" + mname + "[]");
            int p90 = pingP90(e);
            int shown = p90 >= 0 ? p90 : e.host.ping;
            ping    = new Label(pingColor(shown) + shown + "ms[]");
            map     = new Label("[#888]" + (e.host.mapname != null ? e.host.mapname : "?") + "[]");
        } else {
            name    = new Label("[#888]" + e.ip + ":" + e.port + "[]");
            players = new Label("[#666]—[]");
            mode    = new Label("[#666]—[]");
            ping    = new Label("[#666]" + (e.pingStep == 0 ? "ping…" : "n/a") + "[]");
            map     = new Label("[#666]—[]");
        }
        for (Label l : new Label[]{name, players, mode, ping, map}) {
            enableMarkup(l);
            l.setAlignment(arc.util.Align.left);
        }
        rowAnchorByEntry.put(e, name);

        Table card = new Table();
        card.left();
        card.touchable = Touchable.enabled;
        card.clicked(() -> {
            recordRecentConnection(e.ip, e.port);
            serversDialog.hide();
            Vars.ui.join.connect(e.ip, e.port);
        });

        // Long server names blow out the card width and drag the whole
        // dialog past the viewport. Force ellipsis on the name + cap the
        // card width to the scene so cells inside have a bounded growX.
        try { name.setEllipsis(true); } catch (Throwable ignored) {}
        // Line 1: server name (growX) + favorite star (right).
        Table line1 = new Table();
        line1.add(name).left().growX().minWidth(0f);
        TextButton starBtn = new TextButton(e.favorite ? "★" : "☆", Styles.cleart);
        starBtn.getLabelCell().pad(0f, 0f, 0f, 0f);
        starBtn.clicked(() -> toggleFavorite(e));
        line1.add(starBtn).size(44f, 44f).right();
        card.add(line1).growX().padBottom(2f).row();

        // Line 2: dim metadata strip with bullet separators. Build a
        // single Label so the line wraps cleanly when content overruns
        // the card width on very narrow screens.
        StringBuilder sb = new StringBuilder();
        if (e.host != null) {
            sb.append(((Label) players).getText()).append("  [#444]·[]  ");
            sb.append(((Label) mode).getText()).append("  [#444]·[]  ");
            sb.append(((Label) ping).getText());
            if (e.host.mapname != null && !e.host.mapname.isEmpty()) {
                sb.append("  [#444]·[]  ").append(((Label) map).getText());
            }
        } else {
            sb.append(((Label) ping).getText());
        }
        Label meta = new Label(sb.toString());
        enableMarkup(meta);
        meta.setFontScale(0.85f);
        card.add(meta).left().growX().row();

        // Favorite reorder buttons under the card when applicable. Tiny
        // row only renders for starred servers when ≥2 favorites exist.
        if (e.favorite && cfgServersFavorites.size >= 2) {
            String key = e.ip + ":" + e.port;
            int idx = cfgServersFavorites.indexOf(key);
            boolean canUp   = idx > 0;
            boolean canDown = idx >= 0 && idx < cfgServersFavorites.size - 1;
            Table favOps = new Table();
            favOps.left();
            if (canUp) {
                TextButton up = new TextButton("▲ up", Styles.flatt);
                up.clicked(() -> moveFavorite(key, -1));
                favOps.add(up).height(36f).padRight(6f);
            }
            if (canDown) {
                TextButton dn = new TextButton("▼ down", Styles.flatt);
                dn.clicked(() -> moveFavorite(key, +1));
                favOps.add(dn).height(36f);
            }
            card.add(favOps).left().padTop(4f);
        }

        // Fixed maxWidth so a single long server name can't blow the card
        // (and therefore the whole dialog content table) past the viewport.
        float cardMaxW = sceneWidth() - 24f;
        list.add(card).colspan(8).left().growX().maxWidth(cardMaxW).padTop(6f).padBottom(6f).row();
    }

    private void attachConnectClick(Label l, BrowserEntry e) {
        l.touchable = Touchable.enabled;
        l.clicked(() -> {
            recordRecentConnection(e.ip, e.port);
            serversDialog.hide();
            Vars.ui.join.connect(e.ip, e.port);
        });
    }

    private void addSortHeader(Table t, String label, String key, boolean naturalDesc) {
        TextButton hb = new TextButton(label, Styles.defaultt);
        hb.getLabel().setAlignment(arc.util.Align.left);
        hb.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
        @SuppressWarnings("unchecked")
        final Cell<TextButton>[] cellRef = new Cell[1];
        hb.update(() -> {
            String txt = label;
            if (cfgServersSort.equals(key)) txt += cfgServersSortDesc ? "  ▼" : "  ▲";
            if (!hb.getText().toString().equals(txt)) {
                hb.setText(txt);
                hb.invalidate();
            }
            if (cellRef[0] != null) {
                float w = measureButtonWidth(hb);
                cellRef[0].width(w);
            }
            hb.setColor(cfgServersSort.equals(key)
                ? new Color(0.4f, 0.9f, 0.4f, 1f) : Color.white);
        });
        hb.clicked(() -> {
            if (cfgServersSort.equals(key)) {
                cfgServersSortDesc = !cfgServersSortDesc;
            } else {
                cfgServersSort = key;
                cfgServersSortDesc = naturalDesc;
            }
            saveServersConfig();
            refreshBrowserRows();
        });
        cellRef[0] = t.add(hb).left().padRight(8f).padBottom(2f);
        if ("players".equals(key)) cellRef[0].minWidth(150f);
    }

    // ============================================================
    // Reconnect menu card
    // ============================================================
    private void addReconnectMenuCard() {
        if (Vars.ui == null || Vars.ui.menufrag == null) return;
        Vars.ui.menufrag.addButton(buildReconnectLabel(), Icon.refresh, this::showReconnectDialog);
        Events.run(Trigger.update, () -> {
            if (Vars.state == null || !Vars.state.isMenu()) return;
            tickReconnectPing();
            arc.scene.ui.TextButton btn = findReconnectMenuButton();
            if (btn != null) {
                String text = buildReconnectLabel();
                if (!text.equals(btn.getText().toString())) {
                    btn.setText(text);
                    adjustReconnectButtonSize(btn, text);
                }
            }
        });
    }

    /** Cached width/height applied to the reconnect cell so we don't
     *  invalidate the menu hierarchy every frame. */
    private float reconnectBtnAppliedW = 0f, reconnectBtnAppliedH = 0f;

    /** Resize the menu cell hosting the reconnect button to fit its
     *  current multi-line label. Vanilla MenuFragment hard-codes every
     *  cell at 230×70 so a long server name + stats line wrapped onto
     *  4 lines and overflowed visually. We measure the longest line via
     *  the button's font, then mutate the parent Cell's width/height.
     *  Capped at sane maxima so this can't blow the menu sidebar wider
     *  than a couple of vanilla buttons. */
    private void adjustReconnectButtonSize(arc.scene.ui.TextButton btn, String text) {
        if (btn == null || btn.parent == null) return;
        if (!(btn.parent instanceof Table)) return;
        Table tbl = (Table) btn.parent;
        Cell c = tbl.getCell(btn);
        if (c == null) return;
        arc.graphics.g2d.Font font = btn.getStyle() != null ? btn.getStyle().font : null;
        if (font == null) return;
        // Approximate icon + side-padding allowance: the button has a
        // refresh icon on the left + its own internal margin. 70 = icon
        // cell width + a small slack.
        float iconAndPad = 70f;
        // Pixel width of widest line.
        float maxW = 0f;
        String[] lines = text.split("\n");
        for (String line : lines) {
            MEASURE_LAYOUT.setText(font, line);
            if (MEASURE_LAYOUT.width > maxW) maxW = MEASURE_LAYOUT.width;
        }
        float wantW = Math.max(230f, Math.min(420f, maxW + iconAndPad));
        // Height grows with line count; vanilla single-line cells are 70.
        float lineH = font.getLineHeight();
        float wantH = Math.max(70f, lines.length * lineH + 16f);
        // Skip relayout when nothing meaningful changed — invalidating
        // the hierarchy every frame stutters the menu.
        if (Math.abs(reconnectBtnAppliedW - wantW) < 0.5f
            && Math.abs(reconnectBtnAppliedH - wantH) < 0.5f) {
            return;
        }
        reconnectBtnAppliedW = wantW;
        reconnectBtnAppliedH = wantH;
        c.width(wantW).height(wantH);
        btn.invalidate();
        tbl.invalidateHierarchy();
    }

    private static arc.scene.ui.TextButton findReconnectMenuButton() {
        if (Vars.ui == null || Vars.ui.menuGroup == null) return null;
        return findTextButtonByPrefix(Vars.ui.menuGroup, RECONNECT_BTN_TAG);
    }

    private static arc.scene.ui.TextButton findTextButtonByPrefix(arc.scene.Group g, String prefix) {
        if (g == null) return null;
        SnapshotSeq<arc.scene.Element> kids = g.getChildren();
        for (int i = 0; i < kids.size; i++) {
            arc.scene.Element c = kids.get(i);
            if (c instanceof arc.scene.ui.TextButton) {
                arc.scene.ui.TextButton tb = (arc.scene.ui.TextButton) c;
                CharSequence s = tb.getText();
                if (s != null && s.toString().startsWith(prefix)) return tb;
            }
            if (c instanceof arc.scene.Group) {
                arc.scene.ui.TextButton f = findTextButtonByPrefix((arc.scene.Group) c, prefix);
                if (f != null) return f;
            }
        }
        return null;
    }

    private String buildReconnectLabel() {
        String ip = Core.settings != null ? Core.settings.getString("bsb.lastIp", "") : "";
        int port = Core.settings != null ? Core.settings.getInt("bsb.lastPort", 0) : 0;
        String savedName = Core.settings != null ? Core.settings.getString("bsb.lastName", "") : "";
        if (ip.isEmpty() || port <= 0) return RECONNECT_BTN_TAG;
        StringBuilder sb = new StringBuilder(RECONNECT_BTN_TAG);
        sb.append("\n");
        sb.append(!savedName.isEmpty() ? stripColors(savedName) : ip + ":" + port);
        if (reconnectLastHost != null) {
            sb.append("  ").append(reconnectLastHost.players)
              .append("/").append(reconnectLastHost.playerLimit)
              .append("  ").append(reconnectLastHost.ping).append("ms");
        } else if (reconnectPingInFlight) {
            sb.append("  pinging…");
        }
        return sb.toString();
    }

    private void tickReconnectPing() {
        reconnectPingTimer += Core.graphics.getDeltaTime();
        if (reconnectPingTimer < 4f) return;
        reconnectPingTimer = 0f;
        if (reconnectPingInFlight) return;
        String ip = Core.settings.getString("bsb.lastIp", "");
        int port = Core.settings.getInt("bsb.lastPort", 0);
        if (ip.isEmpty() || port <= 0) return;
        if (Vars.net == null) return;
        reconnectPingInFlight = true;
        try {
            Vars.net.pingHost(ip, port, host -> {
                reconnectLastHost = host;
                reconnectPingInFlight = false;
                if (host.name != null && !host.name.isEmpty()) {
                    Core.settings.put("bsb.lastName", host.name);
                }
            }, ex -> reconnectPingInFlight = false);
        } catch (Exception ex) {
            reconnectPingInFlight = false;
        }
    }

    private void showReconnectDialog() {
        BaseDialog dialog = new BaseDialog("Reconnect");
        String ip = Core.settings.getString("bsb.lastIp", "");
        int port = Core.settings.getInt("bsb.lastPort", 0);
        String savedName = Core.settings.getString("bsb.lastName", "");

        if (ip.isEmpty() || port <= 0) {
            dialog.cont.add("No previously connected server.\nJoin one once and the reconnect option will remember it.")
                .pad(20f);
            dialog.addCloseButton();
            dialog.show();
            return;
        }

        reconnectPingTimer = 999f;
        tickReconnectPing();

        dialog.cont.pane(p -> {
            Table hdr = new Table();
            Label nm = new Label(!savedName.isEmpty() ? stripColors(savedName) : ip + ":" + port);
            nm.setColor(Color.white);
            hdr.add(nm).left();
            p.add(hdr).left().padBottom(8f).row();

            Label info = new Label("");
            info.setColor(Color.lightGray);
            info.update(() -> {
                tickReconnectPing();
                StringBuilder sb = new StringBuilder();
                if (reconnectLastHost != null) {
                    mindustry.net.Host h = reconnectLastHost;
                    sb.append("Players ").append(h.players).append("/").append(h.playerLimit)
                      .append("    Ping ").append(h.ping).append(" ms");
                    if (h.mapname != null && !h.mapname.isEmpty()) sb.append("\nMap   ").append(stripColors(h.mapname));
                    if (h.modeName != null && !h.modeName.isEmpty()) sb.append("\nMode  ").append(stripColors(h.modeName));
                    else if (h.mode != null) sb.append("\nMode  ").append(h.mode.name());
                    if (h.wave > 0) sb.append("\nWave  ").append(h.wave);
                } else if (reconnectPingInFlight) {
                    sb.append("pinging…");
                } else {
                    sb.append("(server offline or unreachable)");
                }
                info.setText(sb.toString());
            });
            p.add(info).left().padBottom(8f).row();

            Label foot = new Label(ip + ":" + port);
            foot.setColor(Color.gray);
            p.add(foot).left();
        }).pad(20f).maxWidth(520f);

        dialog.cont.row();
        Table footer = new Table();
        footer.button("Connect", Icon.refresh, () -> {
            dialog.hide();
            Vars.ui.join.connect(ip, port);
        }).width(180f).padRight(8f);
        footer.button("Cancel", Icon.cancel, dialog::hide).width(180f);
        dialog.cont.add(footer).padTop(10f);

        dialog.addCloseButton();
        dialog.show();
    }

    // ============================================================
    // Helpers
    // ============================================================
    private static float measureButtonWidth(TextButton b) {
        if (b == null || b.getLabel() == null) return 0f;
        Label l = b.getLabel();
        arc.graphics.g2d.Font font = l.getStyle().font;
        if (font == null) return l.getPrefWidth() + (BTN_LABEL_PAD + BTN_BG_INSET) * 2f;
        float scale = l.getFontScaleX();
        float prevScale = font.getData().scaleX;
        font.getData().setScale(scale);
        MEASURE_LAYOUT.setText(font, l.getText());
        float textW = MEASURE_LAYOUT.width;
        font.getData().setScale(prevScale);
        return textW + BTN_LABEL_PAD * 2f + BTN_BG_INSET * 2f;
    }

    private static String modeKey(mindustry.net.Host h) {
        if (h == null) return "?";
        if (h.modeName != null && !h.modeName.isEmpty()) return h.modeName;
        if (h.mode != null) return h.mode.name();
        return "?";
    }

    private static String modeKeyLower(mindustry.net.Host h) {
        return modeKey(h).toLowerCase();
    }

    private String browserGroupKey(BrowserEntry e) {
        switch (cfgServersGroupBy) {
            case "mode":
                if (e.host == null) return "[ pinging ]";
                return modeKey(e.host).toLowerCase();
            case "group":
                String g = e.groupName != null && !e.groupName.isEmpty()
                    ? e.groupName : capitalize(e.source == null ? "?" : e.source);
                return g.toLowerCase();
            default:
                return "";
        }
    }

    private String browserGroupDisplay(BrowserEntry e) {
        switch (cfgServersGroupBy) {
            case "mode":  return e.host != null ? modeKey(e.host) : "[ pinging ]";
            case "group": return e.groupName != null && !e.groupName.isEmpty()
                ? e.groupName : capitalize(e.source == null ? "?" : e.source);
            default:      return "";
        }
    }

    private static String pingColor(int p) {
        if (p < 80) return "[lime]";
        if (p < 200) return "[yellow]";
        return "[red]";
    }

    private static String hostName(BrowserEntry e) { return e.host != null ? stripColors(e.host.name) : e.ip; }
    private static int hostPlayers(BrowserEntry e) { return e.host != null ? e.host.players : -1; }
    private static int hostMax(BrowserEntry e) { return e.host != null ? e.host.playerLimit : -1; }
    private static int hostPing(BrowserEntry e) {
        int p90 = pingP90(e);
        if (p90 >= 0) return p90;
        return e.host != null ? e.host.ping : 9999;
    }

    private static int compareStr(String a, String b) {
        return (a == null ? "" : a).compareToIgnoreCase(b == null ? "" : b);
    }

    private static String stripColors(String s) {
        if (s == null) return "";
        return s.replaceAll("\\[[^\\]]*\\]", "");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void enableMarkup(Label l) {
        try {
            if (l != null && l.getStyle() != null && l.getStyle().font != null) {
                l.getStyle().font.getData().markupEnabled = true;
            }
        } catch (Throwable ignored) {}
    }

    private static void addTooltip(arc.scene.Element target, String text) {
        Tooltip tip = new Tooltip(t -> {
            t.background(mindustry.gen.Tex.button);
            t.margin(Scl.scl(6f));
            t.defaults().left();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Label l = new Label(lines[i]);
                if (i > 0) l.setColor(Color.lightGray);
                t.add(l).left();
                if (i < lines.length - 1) t.row();
            }
        });
        tip.allowMobile = true;
        target.addListener(tip);
    }
}
