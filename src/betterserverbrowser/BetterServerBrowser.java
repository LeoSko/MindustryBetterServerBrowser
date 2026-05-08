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
        serversDialog.cont.defaults().growX();

        boolean compact = compactLayout();
        float btnH = compact ? 44f : 28f;
        float searchH = compact ? 44f : 32f;
        float toolbarPad = compact ? 8f : 6f;

        // Row 1: search field. On compact it gets the full row, with the
        // text input growing to the available width so phones don't
        // truncate it. On desktop it shares a row with the toolbar buttons.
        Table tb1 = new Table();
        tb1.defaults().padRight(8f);
        tb1.add("Search").padRight(4f);
        arc.scene.ui.TextField search = new arc.scene.ui.TextField(cfgServersSearch);
        search.setMessageText("server name…");
        search.changed(() -> {
            cfgServersSearch = search.getText();
            saveServersConfig();
            refreshBrowserRows();
        });
        if (compact) {
            tb1.add(search).growX().height(searchH);
            serversDialog.cont.add(tb1).left().growX().padBottom(toolbarPad).row();
        } else {
            tb1.add(search).width(260f).height(searchH);
        }

        if (compact) {
            // Row 2 (compact): group toggles only.
            Table tb2 = new Table();
            tb2.defaults().padRight(6f);
            tb2.left();
            tb2.add("Group:").padRight(6f);
            addGroupChips(tb2, btnH, true);
            serversDialog.cont.add(tb2).left().padBottom(toolbarPad).row();

            // Row 3 (compact): refresh + custom — split so each is a fat
            // touch target instead of cramming them onto row 2.
            Table tb3 = new Table();
            tb3.defaults().padRight(8f);
            tb3.left();
            TextButton refresh = new TextButton("↻ Refresh", Styles.defaultt);
            refresh.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
            refresh.clicked(this::collectAndPingServers);
            tb3.add(refresh).height(btnH).growX();
            TextButton addBtn = new TextButton("+ Custom", Styles.defaultt);
            addBtn.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
            addBtn.clicked(this::showCustomConnectDialog);
            tb3.add(addBtn).height(btnH).growX();
            serversDialog.cont.add(tb3).left().growX().padBottom(toolbarPad).row();
        } else {
            // Desktop: search + group chips + refresh + custom on one row.
            tb1.add("Group:").padLeft(16f).padRight(4f);
            addGroupChips(tb1, btnH, false);
            TextButton refresh = new TextButton("↻ Refresh", Styles.defaultt);
            refresh.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
            refresh.clicked(this::collectAndPingServers);
            tb1.add(refresh).padLeft(16f).width(measureButtonWidth(refresh)).height(btnH);
            TextButton addBtn = new TextButton("+ Custom", Styles.defaultt);
            addBtn.getLabelCell().pad(2f, BTN_LABEL_PAD, 2f, BTN_LABEL_PAD);
            addBtn.clicked(this::showCustomConnectDialog);
            tb1.add(addBtn).width(measureButtonWidth(addBtn)).height(btnH);
            serversDialog.cont.add(tb1).left().padBottom(toolbarPad).row();
        }

        modeChipBar = new Table();
        modeChipBar.defaults().padRight(6f);
        // Embed in a horizontally-scrollable pane so a long mode list can't
        // drag cont past the dialog viewport — Table-based wrap math kept
        // computing prefWidths wider than the actual visible area on
        // Android (font scaling vs Scl mismatch in Cell sizing).
        arc.scene.ui.ScrollPane chipPane = new arc.scene.ui.ScrollPane(modeChipBar);
        chipPane.setScrollingDisabled(false, true);
        chipPane.setOverscroll(false, false);
        chipPane.setFadeScrollBars(true);
        serversDialog.cont.add(chipPane).left().growX().maxWidth(sceneWidth() - 12f).height(120f).padBottom(toolbarPad).row();
        rebuildModeChips();

        // Slider + show-empty. On compact the checkbox lives on its own row
        // so the slider gets the full width to drag on a phone.
        Table tb4 = new Table();
        tb4.left();
        tb4.add("Max ping").padRight(6f);
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
            tb4.add(sl).growX().height(34f);
            tb4.add(v).padLeft(8f).width(70f);
            serversDialog.cont.add(tb4).left().growX().padBottom(toolbarPad).row();
            CheckBox emptyCb = new CheckBox(" Show empty servers");
            emptyCb.setChecked(cfgServersShowEmpty);
            emptyCb.changed(() -> { cfgServersShowEmpty = emptyCb.isChecked(); saveServersConfig(); refreshBrowserRows(); });
            serversDialog.cont.add(emptyCb).left().padBottom(toolbarPad).row();
        } else {
            tb4.add(sl).width(260f);
            tb4.add(v).padLeft(6f).width(80f);
            CheckBox emptyCb = new CheckBox(" Show empty servers");
            emptyCb.setChecked(cfgServersShowEmpty);
            emptyCb.changed(() -> { cfgServersShowEmpty = emptyCb.isChecked(); saveServersConfig(); refreshBrowserRows(); });
            tb4.add(emptyCb).padLeft(20f);
            serversDialog.cont.add(tb4).left().padBottom(toolbarPad).row();
        }

        Table list = new BrowserListTable(this);
        list.top().left();
        // Pane must not scroll horizontally — content extending past the
        // viewport (long server names, accidental wide cells) would
        // otherwise drag cont's prefWidth out and leave the right side of
        // the dialog showing the menu underneath.
        arc.scene.ui.ScrollPane spane = serversDialog.cont.pane(list).grow().maxWidth(sceneWidth()).get();
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
        modeChipBar.clear();
        modeChipBar.left();
        modeChipBar.add("Modes:").padRight(6f).padLeft(6f);
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
        // Single horizontal row — the parent ScrollPane handles overflow by
        // letting the user swipe through chips. Avoids Table wrap math
        // having to predict the exact runtime chip widths under Scl scale.
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
