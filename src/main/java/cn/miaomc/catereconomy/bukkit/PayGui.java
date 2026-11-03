package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.Account;
import cn.miaomc.catereconomy.core.CurrencyDef;
import cn.miaomc.catereconomy.core.EconomyService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * /pay 转账菜单：选玩家 → 选货币 → 选金额（预设或聊天栏输入）→ 大额时确认。
 *
 * <p>只用 1.8 起就存在的 API；物品按新旧两种材质名依次尝试，1.8~26.x 都能显示。
 */
final class PayGui implements Listener {
    private static final int PAGE = 45;

    private final CaterEconomyBukkit plugin;
    /** 正在聊天栏输入自定义金额的玩家。 */
    private final Map<UUID, Chat> chatting = new ConcurrentHashMap<UUID, Chat>();

    PayGui(CaterEconomyBukkit plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- 菜单

    private enum Kind {
        PLAYERS,
        CURRENCIES,
        AMOUNTS,
        CONFIRM
    }

    /** 菜单状态；同时作为 InventoryHolder 用来识别本插件的菜单。 */
    private static final class Menu implements InventoryHolder {
        final Kind kind;
        int page;
        Account target;
        CurrencyDef currency;
        long amount;
        final Map<Integer, Object> slots = new HashMap<Integer, Object>();
        Inventory inventory;

        Menu(Kind kind) {
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Chat {
        final Account target;
        final CurrencyDef currency;
        final long expires;

        Chat(Account target, CurrencyDef currency, long expires) {
            this.target = target;
            this.currency = currency;
            this.expires = expires;
        }
    }

    private Messages m() {
        return plugin.messages();
    }

    /** 第一页：在线玩家头像。 */
    void openPlayers(Player viewer, int page) {
        List<Player> others = new ArrayList<Player>();
        for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(viewer) && viewer.canSee(p)) others.add(p);
        Collections.sort(others, Comparator.comparing(p -> p.getName().toLowerCase()));
        int pages = Math.max(1, (others.size() + PAGE - 1) / PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(Kind.PLAYERS);
        menu.page = current;
        Inventory inv = create(menu, m().raw("gui-players-title", "page", current + 1, "pages", pages));
        if (others.isEmpty()) inv.setItem(22, item(m().raw("gui-players-empty"), null, "BARRIER"));
        for (int i = 0; i < PAGE && current * PAGE + i < others.size(); i++) {
            Player p = others.get(current * PAGE + i);
            inv.setItem(i, head(p.getName(), m().raw("gui-player-name", "player", p.getName()), lines(m().raw("gui-player-lore", "player", p.getName()))));
            menu.slots.put(i, new Account(p.getUniqueId(), p.getName()));
        }
        bottomBar(menu, inv, current > 0, current < pages - 1, false);
        viewer.openInventory(inv);
    }

    /**
     * 第二页：可转账的货币，鼠标悬停显示税率、到账上限等。only 不为 null 时直接进入该货币的金额页。
     */
    void openCurrencies(Player viewer, Account target, CurrencyDef only) {
        EconomyService s = plugin.serviceOrNull();
        if (s == null) return;
        if (only != null) {
            openAmounts(viewer, target, only);
            return;
        }
        List<CurrencyDef> list = new ArrayList<CurrencyDef>();
        for (CurrencyDef c : s.currencies()) if (plugin.commands().payableFor(viewer, c)) list.add(c);
        if (list.isEmpty()) {
            viewer.closeInventory();
            viewer.sendMessage(m().get("gui-no-currency"));
            return;
        }
        if (list.size() == 1 && plugin.settings().guiSkipSingleCurrency) {
            openAmounts(viewer, target, list.get(0));
            return;
        }
        // 先异步查好收款人今日已收额，再回主线程打开菜单
        int day = plugin.settings().today();
        Map<String, CompletableFuture<Long>> received = new HashMap<String, CompletableFuture<Long>>();
        for (CurrencyDef c : list)
            received.put(c.id, c.dailyReceiveLimit == CurrencyDef.UNLIMITED ? CompletableFuture.completedFuture(0L) : s.receivedToday(c, target.uuid, day));
        s.onMain(
                CompletableFuture.allOf(received.values().toArray(new CompletableFuture[0])),
                (v, error) -> {
                    if (!viewer.isOnline()) return;
                    Menu menu = new Menu(Kind.CURRENCIES);
                    menu.target = target;
                    Inventory inv = create(menu, m().raw("gui-currencies-title", "player", target.name));
                    int[] layout = centered(list.size());
                    for (int i = 0; i < list.size(); i++) {
                        CurrencyDef c = list.get(i);
                        long got = error != null ? 0 : received.get(c.id).join();
                        inv.setItem(layout[i], item(m().raw("gui-currency-name", "currency", c.name), currencyLore(viewer, s, c, target, got, error != null), icon(c)));
                        menu.slots.put(layout[i], c);
                    }
                    bottomBar(menu, inv, false, false, true);
                    viewer.openInventory(inv);
                });
    }

    private List<String> currencyLore(Player viewer, EconomyService s, CurrencyDef c, Account target, long received, boolean failed) {
        Long mine = s.cached(viewer.getUniqueId(), c);
        String receiveLeft;
        if (c.dailyReceiveLimit == CurrencyDef.UNLIMITED) receiveLeft = m().raw("gui-unlimited");
        else if (failed) receiveLeft = "?";
        else receiveLeft = c.format(Math.max(0, c.dailyReceiveLimit - received));
        String sameIp;
        if (!c.sameIpEnabled || viewer.hasPermission("catereconomy.bypass.sameip")) sameIp = m().raw("gui-sameip-off");
        else if (c.sameIpMaxCount == 0) sameIp = m().raw("gui-sameip-blocked");
        else
            sameIp =
                    m().raw(
                            "gui-sameip-limit",
                            "count",
                            c.sameIpMaxCount == CurrencyDef.UNLIMITED ? m().raw("gui-unlimited") : String.valueOf(c.sameIpMaxCount),
                            "amount",
                            c.sameIpMaxAmount == CurrencyDef.UNLIMITED ? m().raw("gui-unlimited") : c.format(c.sameIpMaxAmount));
        boolean exempt = viewer.hasPermission("catereconomy.bypass.tax");
        return lines(
                m().raw(
                        "gui-currency-lore",
                        "balance",
                        mine == null ? "?" : c.format(mine),
                        "tax",
                        exempt ? "0" : c.taxPercent.stripTrailingZeros().toPlainString(),
                        "mode",
                        m().raw(c.taxPaidBySender ? "tax-mode-sender" : "tax-mode-receiver"),
                        "player",
                        target.name,
                        "receive_left",
                        receiveLeft,
                        "min",
                        c.format(Math.max(1, c.payMin)),
                        "max",
                        c.payMax > 0 ? c.format(c.payMax) : m().raw("gui-unlimited"),
                        "sameip",
                        sameIp));
    }

    /** 第三页：预设金额与自定义金额。 */
    void openAmounts(Player viewer, Account target, CurrencyDef cur) {
        Menu menu = new Menu(Kind.AMOUNTS);
        menu.target = target;
        menu.currency = cur;
        Inventory inv = create(menu, m().raw("gui-amounts-title", "currency", cur.name, "player", target.name));
        List<Long> presets = new ArrayList<Long>();
        for (String raw : plugin.settings().guiPresets) {
            try {
                long v = cur.parse(raw);
                if (v > 0 && v >= cur.payMin && (cur.payMax <= 0 || v <= cur.payMax)) presets.add(v);
            } catch (IllegalArgumentException ignored) {
                // 不符合该货币小数位的预设直接跳过
            }
        }
        boolean exempt = viewer.hasPermission("catereconomy.bypass.tax");
        int[] layout = centered(presets.size());
        for (int i = 0; i < presets.size(); i++) {
            long v = presets.get(i);
            long[] split = cur.split(v, exempt);
            inv.setItem(
                    layout[i],
                    item(
                            m().raw("gui-amount-name", "amount", cur.format(v)),
                            lines(m().raw("gui-amount-lore", "cost", cur.format(split[0]), "received", cur.format(split[1]), "tax", cur.format(split[2]), "player", target.name)),
                            "GOLD_NUGGET"));
            menu.slots.put(layout[i], v);
        }
        inv.setItem(40, item(m().raw("gui-custom-name"), lines(m().raw("gui-custom-lore")), "NAME_TAG"));
        menu.slots.put(40, "custom");
        bottomBar(menu, inv, false, false, true);
        viewer.openInventory(inv);
    }

    private void openConfirm(Player viewer, Account target, CurrencyDef cur, long amount) {
        Menu menu = new Menu(Kind.CONFIRM);
        menu.target = target;
        menu.currency = cur;
        menu.amount = amount;
        Inventory inv = create(menu, m().raw("gui-confirm-title"));
        long[] split = cur.split(amount, viewer.hasPermission("catereconomy.bypass.tax"));
        List<String> lore = lines(m().raw("gui-amount-lore", "cost", cur.format(split[0]), "received", cur.format(split[1]), "tax", cur.format(split[2]), "player", target.name));
        for (int slot : new int[] {19, 20, 21}) {
            inv.setItem(slot, pane(m().raw("gui-confirm-yes"), lore, "LIME_STAINED_GLASS_PANE", 5));
            menu.slots.put(slot, "yes");
        }
        inv.setItem(22, head(target.name, m().raw("gui-player-name", "player", target.name), lore));
        for (int slot : new int[] {23, 24, 25}) {
            inv.setItem(slot, pane(m().raw("gui-confirm-no"), null, "RED_STAINED_GLASS_PANE", 14));
            menu.slots.put(slot, "no");
        }
        viewer.openInventory(inv);
    }

    // ---------------------------------------------------------------- 点击

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof Menu) || !(event.getWhoClicked() instanceof Player)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        Player player = (Player) event.getWhoClicked();
        Menu menu = (Menu) holder;
        Object value = menu.slots.get(event.getRawSlot());
        if (value == null) return;
        // 物品操作在下一 tick 执行，避免在点击事件里切换界面导致的客户端物品残留
        Bukkit.getScheduler().runTask(plugin, () -> handle(player, menu, value));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void handle(Player player, Menu menu, Object value) {
        if ("close".equals(value)) {
            player.closeInventory();
            return;
        }
        if ("prev".equals(value) || "next".equals(value)) {
            openPlayers(player, menu.page + ("prev".equals(value) ? -1 : 1));
            return;
        }
        if ("back".equals(value)) {
            if (menu.kind == Kind.AMOUNTS) openCurrencies(player, menu.target, null);
            else openPlayers(player, 0);
            return;
        }
        switch (menu.kind) {
            case PLAYERS:
                openCurrencies(player, (Account) value, null);
                break;
            case CURRENCIES:
                openAmounts(player, menu.target, (CurrencyDef) value);
                break;
            case AMOUNTS:
                if ("custom".equals(value)) {
                    player.closeInventory();
                    chatting.put(player.getUniqueId(), new Chat(menu.target, menu.currency, System.currentTimeMillis() + plugin.settings().guiCustomTimeoutSeconds * 1000L));
                    player.sendMessage(
                            m().get(
                                    "gui-custom-prompt",
                                    "currency",
                                    menu.currency.name,
                                    "player",
                                    menu.target.name,
                                    "seconds",
                                    plugin.settings().guiCustomTimeoutSeconds,
                                    "decimals",
                                    menu.currency.decimals));
                } else submit(player, menu.target, menu.currency, (Long) value, true);
                break;
            case CONFIRM:
                player.closeInventory();
                if ("yes".equals(value)) submit(player, menu.target, menu.currency, menu.amount, false);
                else player.sendMessage(m().get("gui-cancelled"));
                break;
            default:
                break;
        }
    }

    /** 检查后执行转账；ask 为 true 且达到确认金额时先打开确认菜单。 */
    private void submit(Player player, Account target, CurrencyDef cur, long amount, boolean ask) {
        EconomyService s = plugin.serviceOrNull();
        Commands commands = plugin.commands();
        if (s == null || !commands.payableFor(player, cur)) {
            player.closeInventory();
            return;
        }
        if (!commands.precheck(player, m(), cur, amount)) {
            player.closeInventory();
            return;
        }
        if (ask && commands.needsConfirm(cur, amount)) {
            openConfirm(player, target, cur, amount);
            return;
        }
        player.closeInventory();
        commands.doPay(player, s, m(), cur, target, amount);
    }

    // ---------------------------------------------------------------- 自定义金额

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Chat chat = chatting.remove(event.getPlayer().getUniqueId());
        if (chat == null) return;
        event.setCancelled(true);
        String text = event.getMessage().trim();
        Player player = event.getPlayer();
        Bukkit.getScheduler()
                .runTask(
                        plugin,
                        () -> {
                            if (!player.isOnline()) return;
                            if (chat.expires < System.currentTimeMillis()) {
                                player.sendMessage(m().get("gui-custom-expired"));
                                return;
                            }
                            if (plugin.settings().guiCancelWords.contains(text.toLowerCase())) {
                                player.sendMessage(m().get("gui-cancelled"));
                                return;
                            }
                            long amount;
                            try {
                                amount = chat.currency.parse(text);
                            } catch (IllegalArgumentException e) {
                                player.sendMessage(m().get("invalid-amount", "decimals", chat.currency.decimals));
                                return;
                            }
                            submit(player, chat.target, chat.currency, amount, true);
                        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        chatting.remove(event.getPlayer().getUniqueId());
    }

    /** 重载时关闭所有打开的转账菜单。 */
    void closeAll() {
        chatting.clear();
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) p.closeInventory();
    }

    // ---------------------------------------------------------------- 物品

    private static Inventory create(Menu menu, String title) {
        // 1.8 的箱子标题最多 32 个字符
        String t = title.length() > 32 ? title.substring(0, 32) : title;
        Inventory inv = Bukkit.createInventory(menu, 54, t);
        menu.inventory = inv;
        return inv;
    }

    private void bottomBar(Menu menu, Inventory inv, boolean prev, boolean next, boolean back) {
        ItemStack filler = pane(" ", null, "GRAY_STAINED_GLASS_PANE", 7);
        for (int i = 45; i < 54; i++) inv.setItem(i, filler);
        if (prev) {
            inv.setItem(45, item(m().raw("gui-prev"), null, "ARROW"));
            menu.slots.put(45, "prev");
        }
        if (back) {
            inv.setItem(48, item(m().raw("gui-back"), null, "ARROW"));
            menu.slots.put(48, "back");
        }
        inv.setItem(49, item(m().raw("gui-close"), null, "BARRIER"));
        menu.slots.put(49, "close");
        if (next) {
            inv.setItem(53, item(m().raw("gui-next"), null, "ARROW"));
            menu.slots.put(53, "next");
        }
    }

    /** 把 n 个按钮居中放在第 2~4 行。 */
    private static int[] centered(int n) {
        int[] out = new int[n];
        int perRow = 7;
        for (int i = 0; i < n; i++) {
            int row = i / perRow;
            int inRow = Math.min(perRow, n - row * perRow);
            int start = 1 + (perRow - inRow) / 2;
            out[i] = 9 * (1 + row) + start + i % perRow;
        }
        return out;
    }

    private String icon(CurrencyDef c) {
        String configured = plugin.settings().guiIcons.get(c.id);
        return configured == null ? "EMERALD" : configured;
    }

    private static List<String> lines(String text) {
        return new ArrayList<String>(Arrays.asList(text.split("\n")));
    }

    private static Material material(String... names) {
        for (String n : names) {
            Material mat = Material.matchMaterial(n);
            if (mat != null) return mat;
        }
        return Material.STONE;
    }

    private static ItemStack item(String name, List<String> lore, String materialName) {
        ItemStack stack = new ItemStack(material(materialName, "PAPER"));
        return named(stack, name, lore);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack pane(String name, List<String> lore, String modern, int legacyData) {
        Material mat = Material.matchMaterial(modern);
        ItemStack stack = mat != null ? new ItemStack(mat) : new ItemStack(material("STAINED_GLASS_PANE"), 1, (short) legacyData);
        return named(stack, name, lore);
    }

    @SuppressWarnings("deprecation")
    private static ItemStack head(String owner, String name, List<String> lore) {
        Material modern = Material.matchMaterial("PLAYER_HEAD");
        ItemStack stack = modern != null ? new ItemStack(modern) : new ItemStack(material("SKULL_ITEM"), 1, (short) 3);
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof SkullMeta) ((SkullMeta) meta).setOwner(owner);
        stack.setItemMeta(meta);
        return named(stack, name, lore);
    }

    private static ItemStack named(ItemStack stack, String name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}
