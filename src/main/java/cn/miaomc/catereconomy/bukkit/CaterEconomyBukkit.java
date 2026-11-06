package cn.miaomc.catereconomy.bukkit;

import cn.miaomc.catereconomy.core.Account;
import cn.miaomc.catereconomy.core.EconomyService;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class CaterEconomyBukkit extends JavaPlugin implements Listener {
    private volatile EconomyService service;
    private Settings settings;
    private Messages messages;
    private Commands commands;
    private PayGui payGui;
    private VaultHook vault;
    private Object placeholders;
    private final List<BukkitTask> tasks = new ArrayList<BukkitTask>();
    private volatile boolean warnedLoopback;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!new File(getDataFolder(), "messages.yml").exists()) saveResource("messages.yml", false);
        if (!load()) {
            getLogger().severe("配置有误，插件未启动。修改后执行 /catereconomy reload 或重启服务器。");
        }
        commands = new Commands(this);
        for (String name : new String[] {"money", "pay", "eco", "baltop", "paylog", "catereconomy"}) {
            PluginCommand command = getCommand(name);
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        getServer().getPluginManager().registerEvents(this, this);
        payGui = new PayGui(this);
        getServer().getPluginManager().registerEvents(payGui, this);
    }

    @Override
    public void onDisable() {
        shutdown();
    }

    /** 读取配置并启动服务；失败返回 false，旧服务已停止。 */
    boolean load() {
        shutdown();
        try {
            reloadConfig();
            Settings next = new Settings(getConfig(), getDataFolder());
            InputStream defaults = getResource("messages.yml");
            Messages nextMessages =
                    new Messages(YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml")), defaults);
            EconomyService nextService =
                    new EconomyService(
                            new BukkitPlatform(this),
                            getLogger(),
                            next.serverName,
                            next.storages,
                            next.currencies,
                            next.defaultCurrency);
            for (String error : nextService.start()) getLogger().severe(error);
            settings = next;
            messages = nextMessages;
            service = nextService;
            cn.miaomc.catereconomy.api.CaterEconomyAPI.bind(nextService);
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml：" + e.getMessage());
            return false;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            service.loadAsync(p.getUniqueId());
            String ip = ipOf(p);
            if (ip != null) service.recordIp(p.getUniqueId(), ip);
        }
        long refresh = settings.refreshSeconds * 20L;
        // 在主线程收集在线玩家，数据库查询由存储线程完成。
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> current().refreshOnline(), refresh, refresh));
        long top = settings.topRefreshSeconds * 20L;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> current().refreshTop(settings.topSize), 40L, top));
        // 每小时清理 3 天前的每日计数和超出 window-days 的 IP 记录；计数按日期分行，零点后自动从 0 开始。
        tasks.add(
                Bukkit.getScheduler()
                        .runTaskTimer(this, () -> current().purgeDaily(settings.daysAgo(3), settings.ipSince()), 20L * 30, 20L * 60 * 60));
        if (settings.keepDays > 0) {
            long day = 20L * 60 * 60 * 24;
            tasks.add(
                    Bukkit.getScheduler()
                            .runTaskTimerAsynchronously(
                                    this,
                                    () -> current().purgeRecords(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(settings.keepDays)),
                                    20L * 60,
                                    day));
        }
        if (settings.vaultEnabled && getServer().getPluginManager().getPlugin("Vault") != null) {
            vault = VaultHook.register(this, settings.vaultCurrency);
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            placeholders = PlaceholderHook.register(this, settings.gemsPlaceholders, settings.ecoPlaceholders);
        }
        getLogger().info("已启用 " + service.currencies().size() + " 种货币，服务器名 " + settings.serverName);
        return true;
    }

    private void shutdown() {
        if (payGui != null) payGui.closeAll();
        for (BukkitTask task : tasks) task.cancel();
        tasks.clear();
        if (vault != null) {
            vault.unregister();
            vault = null;
        }
        if (placeholders != null) {
            PlaceholderHook.unregister(placeholders);
            placeholders = null;
        }
        EconomyService old = service;
        service = null;
        cn.miaomc.catereconomy.api.CaterEconomyAPI.bind(null);
        if (old != null) old.close();
    }

    EconomyService current() {
        EconomyService s = service;
        if (s == null) throw new IllegalStateException("CaterEconomy 未启动");
        return s;
    }

    EconomyService serviceOrNull() {
        return service;
    }

    Settings settings() {
        return settings;
    }

    Messages messages() {
        return messages;
    }

    Commands commands() {
        return commands;
    }

    PayGui payGui() {
        return payGui;
    }

    // ---------------------------------------------------------------- 玩家缓存

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        EconomyService s = service;
        if (s == null || event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        s.loadBlocking(event.getUniqueId(), settings.preloadTimeoutMillis);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        EconomyService s = service;
        if (s == null) return;
        Player p = event.getPlayer();
        if (!s.isLoaded(p.getUniqueId())) s.loadAsync(p.getUniqueId());
        s.updateName(new Account(p.getUniqueId(), p.getName()));
        String ip = ipOf(p);
        if (ip != null) s.recordIp(p.getUniqueId(), ip);
    }

    /** 玩家当前 IP；在 same-ip.ignore-ips 中的地址（如未开启 IP 转发时的 127.0.0.1）返回 null。 */
    String ipOf(Player p) {
        if (p.getAddress() == null || p.getAddress().getAddress() == null) return null;
        String ip = p.getAddress().getAddress().getHostAddress();
        if (settings.ignoredIps.contains(ip)) {
            if (!warnedLoopback) {
                warnedLoopback = true;
                getLogger().warning("玩家 IP 为 " + ip + "，已按 same-ip.ignore-ips 忽略。如果在 BungeeCord 后面，请确认 spigot.yml 的 bungeecord: true，否则同 IP 限制不会生效。");
            }
            return null;
        }
        return ip;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        EconomyService s = service;
        if (s != null) s.unload(event.getPlayer().getUniqueId());
        if (commands != null) commands.forget(event.getPlayer().getUniqueId());
    }

    static final class BukkitPlatform implements EconomyService.Platform {
        private final JavaPlugin plugin;

        BukkitPlatform(JavaPlugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void sync(Runnable task) {
            if (!plugin.isEnabled()) return;
            if (Bukkit.isPrimaryThread()) task.run();
            else Bukkit.getScheduler().runTask(plugin, task);
        }

        @Override
        public Account online(String name) {
            Player p = Bukkit.getPlayerExact(name);
            return p == null ? null : new Account(p.getUniqueId(), p.getName());
        }

        @Override
        public Collection<Account> onlineAccounts() {
            List<Account> out = new ArrayList<Account>();
            for (Player p : new ArrayList<Player>(Bukkit.getOnlinePlayers())) out.add(new Account(p.getUniqueId(), p.getName()));
            return out;
        }
    }
}
