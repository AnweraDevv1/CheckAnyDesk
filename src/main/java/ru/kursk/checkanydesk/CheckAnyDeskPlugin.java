package ru.kursk.checkanydesk;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import ru.kursk.checkanydesk.CheckSession;
import ru.kursk.checkanydesk.DurationParser;
import ru.kursk.checkanydesk.api.CheckResultEvent;

public final class CheckAnyDeskPlugin
extends JavaPlugin
implements Listener,
CommandExecutor,
TabCompleter {
    private static final String PERMISSION = "check.admin";
    private static final String REPORT_START_PERMISSION = "check.report";
    private static final String SETUP_PERMISSION = "check.setup";
    private static final long CHAT_REPEAT_MILLIS = 10000L;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+");
    private final Map<UUID, CheckSession> checksByTarget = new ConcurrentHashMap<UUID, CheckSession>();
    private final Map<UUID, BossBar> bossBars = new ConcurrentHashMap<UUID, BossBar>();
    private final Map<UUID, Long> lastReminderAt = new ConcurrentHashMap<UUID, Long>();
    private final Map<UUID, Long> lastMovementFlagAt = new ConcurrentHashMap<UUID, Long>();
    private File checksFile;
    private FileConfiguration checksData;
    private BukkitTask updateTask;

    public void onEnable() {
        this.saveDefaultConfig();
        this.copyMissingConfigDefaults();
        this.checksFile = new File(this.getDataFolder(), "active-checks.yml");
        this.checksData = YamlConfiguration.loadConfiguration((File)this.checksFile);
        this.loadChecks();
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        this.registerCommand("check");
        this.registerCommand("proverka");
        this.updateTask = Bukkit.getScheduler().runTaskTimer((Plugin)this, this::updateChecks, 20L, 20L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.restoreCheckFor(player, false);
        }
        this.getLogger().info("CheckAnyDesk enabled. Active checks restored: " + this.checksByTarget.size());
    }

    private void copyMissingConfigDefaults() {
        try (InputStream inputStream = this.getResource("config.yml");){
            if (inputStream == null) {
                return;
            }
            YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration((Reader)new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.getConfig().setDefaults((Configuration)yamlConfiguration);
            this.getConfig().options().copyDefaults(true);
            this.saveConfig();
        }
        catch (IOException iOException) {
            this.getLogger().warning("Could not update missing config defaults: " + iOException.getMessage());
        }
    }

    private void registerCommand(String string) {
        PluginCommand pluginCommand = this.getCommand(string);
        if (pluginCommand == null) {
            throw new IllegalStateException("Command /" + string + " is missing in plugin.yml");
        }
        pluginCommand.setExecutor((CommandExecutor)this);
        pluginCommand.setTabCompleter((TabCompleter)this);
    }

    public void onDisable() {
        if (this.updateTask != null) {
            this.updateTask.cancel();
        }
        this.saveChecks();
        for (BossBar bossBar : this.bossBars.values()) {
            bossBar.removeAll();
        }
        this.bossBars.clear();
    }

    public boolean onCommand(CommandSender commandSender, Command command, String string, String[] stringArray) {
        String string2;
        if (command.getName().equalsIgnoreCase("proverka")) {
            return this.handleCheckRoomCommand(commandSender, stringArray);
        }
        if (!commandSender.hasPermission(PERMISSION)) {
            this.send(commandSender, "messages.no-permission");
            return true;
        }
        if (!(commandSender instanceof Player)) {
            this.send(commandSender, "messages.only-player");
            return true;
        }
        Player player = (Player)commandSender;
        if (stringArray.length == 0) {
            this.sendUsage((CommandSender)player);
            return true;
        }
        switch (string2 = stringArray[0].toLowerCase(Locale.ROOT)) {
            case "add": {
                this.addTime(player, stringArray);
                break;
            }
            case "remove": 
            case "take": 
            case "subtract": {
                this.removeTime(player, stringArray);
                break;
            }
            case "stop": {
                this.stopCheck(player);
                break;
            }
            case "ban": {
                this.banForCheats(player);
                break;
            }
            default: {
                this.beginCheck(player, stringArray[0]);
            }
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender commandSender, Command command, String string, String[] stringArray) {
        if (command.getName().equalsIgnoreCase("proverka")) {
            if ((commandSender.hasPermission(SETUP_PERMISSION) || commandSender.hasPermission(PERMISSION)) && stringArray.length == 1) {
                return this.filter(List.of("set"), stringArray[0]);
            }
            return List.of();
        }
        if (!commandSender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (stringArray.length == 1) {
            ArrayList<String> arrayList = new ArrayList<String>(List.of("add", "remove", "stop", "ban"));
            for (Player player : Bukkit.getOnlinePlayers()) {
                arrayList.add(player.getName());
            }
            return this.filter(arrayList, stringArray[0]);
        }
        if (stringArray.length == 2 && (stringArray[0].equalsIgnoreCase("add") || stringArray[0].equalsIgnoreCase("remove") || stringArray[0].equalsIgnoreCase("take"))) {
            return this.filter(List.of("1m", "5m", "10m", "30m"), stringArray[1]);
        }
        return List.of();
    }

    private List<String> filter(Collection<String> collection, String string) {
        return collection.stream().filter(string2 -> string2.toLowerCase(Locale.ROOT).startsWith(string.toLowerCase(Locale.ROOT))).sorted(Comparator.naturalOrder()).toList();
    }

    private boolean handleCheckRoomCommand(CommandSender commandSender, String[] stringArray) {
        if (!(commandSender instanceof Player)) {
            this.send(commandSender, "messages.only-player");
            return true;
        }
        Player player = (Player)commandSender;
        if (!player.hasPermission(SETUP_PERMISSION) && !player.hasPermission(PERMISSION)) {
            this.send((CommandSender)player, "messages.no-permission");
            return true;
        }
        if (stringArray.length != 1 || !stringArray[0].equalsIgnoreCase("set")) {
            this.send((CommandSender)player, "messages.check-location-usage");
            return true;
        }
        Location location = player.getLocation().clone();
        this.getConfig().set("check-location.world", (Object)location.getWorld().getName());
        this.getConfig().set("check-location.x", (Object)location.getX());
        this.getConfig().set("check-location.y", (Object)location.getY());
        this.getConfig().set("check-location.z", (Object)location.getZ());
        this.getConfig().set("check-location.yaw", (Object)Float.valueOf(location.getYaw()));
        this.getConfig().set("check-location.pitch", (Object)Float.valueOf(location.getPitch()));
        this.saveConfig();
        this.send((CommandSender)player, "messages.check-location-set", Map.of("world", location.getWorld().getName(), "x", String.format(Locale.ROOT, "%.1f", location.getX()), "y", String.format(Locale.ROOT, "%.1f", location.getY()), "z", String.format(Locale.ROOT, "%.1f", location.getZ())));
        return true;
    }

    private Location getCheckLocation() {
        String string = this.getConfig().getString("check-location.world");
        if (string == null || string.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld((String)string);
        if (world == null) {
            this.getLogger().warning("Configured check room world is unavailable: " + string);
            return null;
        }
        return new Location(world, this.getConfig().getDouble("check-location.x"), this.getConfig().getDouble("check-location.y"), this.getConfig().getDouble("check-location.z"), (float)this.getConfig().getDouble("check-location.yaw"), (float)this.getConfig().getDouble("check-location.pitch"));
    }

    public boolean startCheckFromReport(Player player, Player player2) {
        if (!player.hasPermission(REPORT_START_PERMISSION)) {
            this.send((CommandSender)player, "messages.no-report-start-permission");
            return false;
        }
        return this.beginCheck(player, player2.getName());
    }

    private boolean beginCheck(Player player, String string) {
        Location location;
        Player player2 = Bukkit.getPlayerExact((String)string);
        if (player2 == null) {
            this.send((CommandSender)player, "messages.player-not-found", this.placeholder("player", string));
            return false;
        }
        if (player2.getUniqueId().equals(player.getUniqueId())) {
            this.send((CommandSender)player, "messages.cannot-check-self");
            return false;
        }
        if (this.checksByTarget.containsKey(player2.getUniqueId())) {
            this.send((CommandSender)player, "messages.target-already-checked", this.placeholder("player", player2.getName()));
            return false;
        }
        CheckSession checkSession = this.getCheckForModerator(player.getUniqueId());
        if (checkSession != null) {
            this.send((CommandSender)player, "messages.moderator-already-checking", this.placeholder("player", checkSession.targetName()));
            return false;
        }
        long l = this.getConfig().getLong("check-duration-minutes", 5L) * 60000L;
        if (l <= 0L) {
            l = 300000L;
        }
        if ((location = this.getCheckLocation()) != null) {
            player2.teleport(location);
        }
        long l2 = System.currentTimeMillis();
        CheckSession checkSession2 = new CheckSession(player2.getUniqueId(), player2.getName(), player.getUniqueId(), player.getName(), l2, Math.addExact(l2, l), l);
        this.checksByTarget.put(player2.getUniqueId(), checkSession2);
        this.saveChecks();
        this.restoreCheckFor(player2, true);
        this.send((CommandSender)player, "messages.check-started", this.placeholders("player", player2.getName(), "time", this.formatTime(l)));
        return true;
    }

    private void addTime(Player player, String[] stringArray) {
        if (stringArray.length != 2) {
            this.send((CommandSender)player, "messages.invalid-duration");
            return;
        }
        OptionalLong optionalLong = DurationParser.parse(stringArray[1]);
        if (optionalLong.isEmpty()) {
            this.send((CommandSender)player, "messages.invalid-duration");
            return;
        }
        CheckSession checkSession = this.getCheckForModerator(player.getUniqueId());
        if (checkSession == null) {
            this.send((CommandSender)player, "messages.no-active-check");
            return;
        }
        try {
            checkSession.addTime(optionalLong.getAsLong());
        }
        catch (ArithmeticException arithmeticException) {
            this.send((CommandSender)player, "messages.invalid-duration");
            return;
        }
        this.saveChecks();
        this.send((CommandSender)player, "messages.time-added", this.placeholders("player", checkSession.targetName(), "duration", this.formatTime(optionalLong.getAsLong())));
    }

    private void removeTime(Player player, String[] stringArray) {
        if (stringArray.length != 2) {
            this.send((CommandSender)player, "messages.invalid-duration");
            return;
        }
        OptionalLong optionalLong = DurationParser.parse(stringArray[1]);
        if (optionalLong.isEmpty()) {
            this.send((CommandSender)player, "messages.invalid-duration");
            return;
        }
        CheckSession checkSession = this.getCheckForModerator(player.getUniqueId());
        if (checkSession == null) {
            this.send((CommandSender)player, "messages.no-active-check");
            return;
        }
        try {
            checkSession.removeTime(optionalLong.getAsLong());
        }
        catch (ArithmeticException arithmeticException) {
            this.send((CommandSender)player, "messages.invalid-duration");
            return;
        }
        this.saveChecks();
        this.send((CommandSender)player, "messages.time-removed", this.placeholders("player", checkSession.targetName(), "duration", this.formatTime(optionalLong.getAsLong())));
        if (System.currentTimeMillis() >= checkSession.endsAt()) {
            this.punish(checkSession, 7, this.getMessagePlain("messages.ban-refusal-reason"), "messages.kick-refusal");
        }
    }

    private void stopCheck(Player player) {
        CheckSession checkSession = this.getCheckForModerator(player.getUniqueId());
        if (checkSession == null) {
            this.send((CommandSender)player, "messages.no-active-check");
            return;
        }
        Player player2 = Bukkit.getPlayer((UUID)checkSession.targetId());
        if (player2 != null) {
            this.releasePlayer(player2, checkSession, true);
        }
        this.removeCheck(checkSession);
        this.publishCheckResult(checkSession, CheckResultEvent.Result.PASSED);
        this.send((CommandSender)player, "messages.check-stopped-admin", this.placeholder("player", checkSession.targetName()));
    }

    private void banForCheats(Player player) {
        CheckSession checkSession = this.getCheckForModerator(player.getUniqueId());
        if (checkSession == null) {
            this.send((CommandSender)player, "messages.no-active-check");
            return;
        }
        this.punish(checkSession, 30, this.getMessagePlain("messages.ban-cheats-reason"), "messages.kick-cheats");
        this.send((CommandSender)player, "messages.check-banned-admin", this.placeholder("player", checkSession.targetName()));
    }

    private void updateChecks() {
        long l = System.currentTimeMillis();
        for (CheckSession checkSession : List.copyOf(this.checksByTarget.values())) {
            if (l >= checkSession.endsAt()) {
                this.punish(checkSession, 7, this.getMessagePlain("messages.ban-refusal-reason"), "messages.kick-refusal");
                continue;
            }
            this.updateBossBar(checkSession, l);
            Player player = Bukkit.getPlayer((UUID)checkSession.targetId());
            if (player == null || !player.isOnline()) continue;
            this.applyCheckEffects(player);
            this.showCheckTitle(player);
            if (l - this.lastReminderAt.getOrDefault(checkSession.targetId(), 0L) < 10000L) continue;
            this.sendCheckMessage(player);
        }
    }

    private void restoreCheckFor(Player player, boolean bl) {
        CheckSession checkSession = this.checksByTarget.get(player.getUniqueId());
        if (checkSession == null) {
            return;
        }
        if (System.currentTimeMillis() >= checkSession.endsAt()) {
            this.punish(checkSession, 7, this.getMessagePlain("messages.ban-refusal-reason"), "messages.kick-refusal");
            return;
        }
        player.closeInventory();
        this.applyCheckEffects(player);
        this.updateBossBar(checkSession, System.currentTimeMillis());
        this.showCheckTitle(player);
        if (bl) {
            this.sendCheckMessage(player);
        }
    }

    private void applyCheckEffects(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false, false), true);
        player.setVelocity(new Vector(0.0, 0.0, 0.0));
        player.setFallDistance(0.0f);
        player.leaveVehicle();
    }

    private void updateBossBar(CheckSession checkSession, long l) {
        BossBar bossBar = this.bossBars.computeIfAbsent(checkSession.targetId(), uUID -> Bukkit.createBossBar((String)"", (BarColor)BarColor.RED, (BarStyle)BarStyle.SOLID, (BarFlag[])new BarFlag[0]));
        this.addToBarIfOnline(bossBar, Bukkit.getPlayer((UUID)checkSession.targetId()));
        this.addToBarIfOnline(bossBar, Bukkit.getPlayer((UUID)checkSession.moderatorId()));
        long l2 = Math.max(0L, checkSession.endsAt() - l);
        double d = Math.max(0.0, Math.min(1.0, (double)l2 / (double)Math.max(1L, checkSession.totalMillis())));
        bossBar.setProgress(d);
        bossBar.setTitle(this.legacy("messages.bossbar-title", this.placeholders("player", checkSession.targetName(), "time", this.formatTime(l2))));
    }

    private void addToBarIfOnline(BossBar bossBar, Player player) {
        if (player != null && player.isOnline() && !bossBar.getPlayers().contains(player)) {
            bossBar.addPlayer(player);
        }
    }

    private void showCheckTitle(Player player) {
        Component component = this.component("messages.target-title");
        Component component2 = this.component("messages.target-title-subtitle");
        player.showTitle(Title.title((Component)component, (Component)component2, (Title.Times)Title.Times.times((Duration)Duration.ZERO, (Duration)Duration.ofMillis(1500L), (Duration)Duration.ZERO)));
    }

    private void sendCheckMessage(Player player) {
        for (String string : this.getConfig().getStringList("messages.checked-chat")) {
            player.sendMessage(this.makeUrlsClickable(string));
        }
        this.lastReminderAt.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private Component makeUrlsClickable(String string) {
        Matcher matcher = URL_PATTERN.matcher(string);
        if (!matcher.find()) {
            return LEGACY.deserialize(string);
        }
        TextComponent textComponent = Component.empty();
        int n = 0;
        do {
            textComponent = (TextComponent)textComponent.append((Component)LEGACY.deserialize(string.substring(n, matcher.start())));
            String string2 = matcher.group();
            textComponent = (TextComponent)textComponent.append(((TextComponent)Component.text((String)string2, (TextColor)NamedTextColor.BLUE).decorate(TextDecoration.UNDERLINED)).clickEvent(ClickEvent.openUrl((String)string2)));
            n = matcher.end();
        } while (matcher.find());
        return textComponent.append((Component)LEGACY.deserialize(string.substring(n)));
    }

    private void releasePlayer(Player player, CheckSession checkSession, boolean bl) {
        BossBar bossBar = this.bossBars.remove(checkSession.targetId());
        if (bossBar != null) {
            bossBar.removePlayer(player);
            bossBar.removeAll();
        }
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.clearTitle();
        player.sendMessage(this.component("messages.passed-chat"));
        player.showTitle(Title.title((Component)this.component("messages.passed-title"), (Component)this.component("messages.passed-title-subtitle"), (Title.Times)Title.Times.times((Duration)Duration.ofMillis(250L), (Duration)Duration.ofSeconds(3L), (Duration)Duration.ofMillis(250L))));
        if (bl) {
            this.givePassReward(player);
        }
    }

    private void givePassReward(Player player) {
        LocalDateTime localDateTime = LocalDateTime.now();
        ItemStack itemStack = new ItemStack(Material.NETHER_STAR);
        ItemMeta itemMeta = itemStack.getItemMeta();
        itemMeta.displayName(this.component("messages.reward-name", this.placeholder("player", player.getName())));
        ArrayList<TextComponent> arrayList = new ArrayList<TextComponent>();
        for (Object object : this.getConfig().getStringList("messages.reward-lore")) {
            arrayList.add(LEGACY.deserialize(this.replace((String)object, this.placeholders("player", player.getName(), "date", DATE_FORMAT.format(localDateTime), "time", TIME_FORMAT.format(localDateTime)))));
        }
        itemMeta.lore(arrayList);
        itemStack.setItemMeta(itemMeta);
        Map<Integer, ItemStack> hashMap = player.getInventory().addItem(itemStack);
        for (ItemStack itemStack2 : hashMap.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), itemStack2);
        }
    }

    private void punish(CheckSession checkSession, int n, String string, String string2) {
        this.punish(checkSession, n, string, string2, true);
    }

    private void punish(CheckSession checkSession, int n, String string, String string2, boolean bl) {
        Date date = Date.from(Instant.now().plus(Duration.ofDays(n)));
        Bukkit.getBanList((BanList.Type)BanList.Type.NAME).addBan(checkSession.targetName(), string, date, checkSession.moderatorName());
        this.removeCheck(checkSession);
        CheckResultEvent.Result result = n >= 30 ? CheckResultEvent.Result.CHEATS_BANNED : CheckResultEvent.Result.REFUSAL_BANNED;
        this.publishCheckResult(checkSession, result);
        if (!bl) {
            return;
        }
        Player player = Bukkit.getPlayer((UUID)checkSession.targetId());
        if (player != null && player.isOnline()) {
            player.kick(this.component(string2));
        }
    }

    public boolean isPlayerUnderCheck(UUID uUID) {
        return this.checksByTarget.containsKey(uUID);
    }

    private void publishCheckResult(CheckSession checkSession, CheckResultEvent.Result result) {
        Bukkit.getPluginManager().callEvent((Event)new CheckResultEvent(checkSession.targetId(), checkSession.targetName(), checkSession.moderatorId(), checkSession.moderatorName(), result));
    }

    private void removeCheck(CheckSession checkSession) {
        this.checksByTarget.remove(checkSession.targetId());
        this.lastReminderAt.remove(checkSession.targetId());
        this.lastMovementFlagAt.remove(checkSession.targetId());
        BossBar bossBar = this.bossBars.remove(checkSession.targetId());
        if (bossBar != null) {
            bossBar.removeAll();
        }
        this.saveChecks();
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onMove(PlayerMoveEvent playerMoveEvent) {
        CheckSession checkSession = this.checksByTarget.get(playerMoveEvent.getPlayer().getUniqueId());
        if (checkSession == null || playerMoveEvent.getTo() == null) {
            return;
        }
        if (playerMoveEvent.getFrom().getX() != playerMoveEvent.getTo().getX() || playerMoveEvent.getFrom().getY() != playerMoveEvent.getTo().getY() || playerMoveEvent.getFrom().getZ() != playerMoveEvent.getTo().getZ()) {
            playerMoveEvent.setCancelled(true);
            playerMoveEvent.getPlayer().setVelocity(new Vector(0.0, 0.0, 0.0));
            playerMoveEvent.getPlayer().setFallDistance(0.0f);
            this.flagMovement(checkSession);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onVelocity(PlayerVelocityEvent playerVelocityEvent) {
        if (this.isChecked(playerVelocityEvent.getPlayer())) {
            playerVelocityEvent.setVelocity(new Vector(0.0, 0.0, 0.0));
            playerVelocityEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onTeleport(PlayerTeleportEvent playerTeleportEvent) {
        if (this.isChecked(playerTeleportEvent.getPlayer())) {
            playerTeleportEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent playerInteractEvent) {
        if (this.blockInteraction(playerInteractEvent.getPlayer())) {
            playerInteractEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInteractEntity(PlayerInteractEntityEvent playerInteractEntityEvent) {
        if (this.blockInteraction(playerInteractEntityEvent.getPlayer())) {
            playerInteractEntityEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent playerArmorStandManipulateEvent) {
        if (this.blockInteraction(playerArmorStandManipulateEvent.getPlayer())) {
            playerArmorStandManipulateEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBlockBreak(BlockBreakEvent blockBreakEvent) {
        if (this.blockInteraction(blockBreakEvent.getPlayer())) {
            blockBreakEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBlockPlace(BlockPlaceEvent blockPlaceEvent) {
        if (this.blockInteraction(blockPlaceEvent.getPlayer())) {
            blockPlaceEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBucketFill(PlayerBucketFillEvent playerBucketFillEvent) {
        if (this.blockInteraction(playerBucketFillEvent.getPlayer())) {
            playerBucketFillEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onBucketEmpty(PlayerBucketEmptyEvent playerBucketEmptyEvent) {
        if (this.blockInteraction(playerBucketEmptyEvent.getPlayer())) {
            playerBucketEmptyEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onItemConsume(PlayerItemConsumeEvent playerItemConsumeEvent) {
        if (this.blockInteraction(playerItemConsumeEvent.getPlayer())) {
            playerItemConsumeEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onItemDrop(PlayerDropItemEvent playerDropItemEvent) {
        if (this.blockInteraction(playerDropItemEvent.getPlayer())) {
            playerDropItemEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onItemPickup(EntityPickupItemEvent entityPickupItemEvent) {
        Player player;
        LivingEntity livingEntity = entityPickupItemEvent.getEntity();
        if (livingEntity instanceof Player && this.blockInteraction(player = (Player)livingEntity)) {
            entityPickupItemEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onItemHeld(PlayerItemHeldEvent playerItemHeldEvent) {
        if (this.blockInteraction(playerItemHeldEvent.getPlayer())) {
            playerItemHeldEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onSwapHands(PlayerSwapHandItemsEvent playerSwapHandItemsEvent) {
        if (this.blockInteraction(playerSwapHandItemsEvent.getPlayer())) {
            playerSwapHandItemsEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onEditBook(PlayerEditBookEvent playerEditBookEvent) {
        if (this.blockInteraction(playerEditBookEvent.getPlayer())) {
            playerEditBookEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onFishing(PlayerFishEvent playerFishEvent) {
        if (this.blockInteraction(playerFishEvent.getPlayer())) {
            playerFishEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryOpen(InventoryOpenEvent inventoryOpenEvent) {
        Player player;
        HumanEntity humanEntity = inventoryOpenEvent.getPlayer();
        if (humanEntity instanceof Player && this.blockInteraction(player = (Player)humanEntity)) {
            inventoryOpenEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryClick(InventoryClickEvent inventoryClickEvent) {
        Player player;
        HumanEntity humanEntity = inventoryClickEvent.getWhoClicked();
        if (humanEntity instanceof Player && this.blockInteraction(player = (Player)humanEntity)) {
            inventoryClickEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onInventoryDrag(InventoryDragEvent inventoryDragEvent) {
        Player player;
        HumanEntity humanEntity = inventoryDragEvent.getWhoClicked();
        if (humanEntity instanceof Player && this.blockInteraction(player = (Player)humanEntity)) {
            inventoryDragEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onVehicleEnter(VehicleEnterEvent vehicleEnterEvent) {
        Player player;
        Entity entity = vehicleEnterEvent.getEntered();
        if (entity instanceof Player && this.blockInteraction(player = (Player)entity)) {
            vehicleEnterEvent.setCancelled(true);
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onCheckedPlayerCommand(PlayerCommandPreprocessEvent playerCommandPreprocessEvent) {
        if (this.isChecked(playerCommandPreprocessEvent.getPlayer())) {
            playerCommandPreprocessEvent.setCancelled(true);
            playerCommandPreprocessEvent.getPlayer().sendMessage(this.component("messages.command-blocked"));
        }
    }

    private boolean isChecked(Player player) {
        return this.checksByTarget.containsKey(player.getUniqueId());
    }

    private boolean blockInteraction(Player player) {
        if (!this.isChecked(player)) {
            return false;
        }
        player.sendActionBar(this.component("messages.interaction-blocked"));
        return true;
    }

    private void flagMovement(CheckSession checkSession) {
        long l;
        long l2 = System.currentTimeMillis();
        if (l2 - (l = this.lastMovementFlagAt.getOrDefault(checkSession.targetId(), 0L).longValue()) < 2000L) {
            return;
        }
        this.lastMovementFlagAt.put(checkSession.targetId(), l2);
        Player player = Bukkit.getPlayer((UUID)checkSession.moderatorId());
        if (player != null && player.isOnline()) {
            this.send((CommandSender)player, "messages.movement-flag", this.placeholder("player", checkSession.targetName()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent playerJoinEvent) {
        Bukkit.getScheduler().runTask((Plugin)this, () -> this.restoreCheckFor(playerJoinEvent.getPlayer(), true));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent playerQuitEvent) {
        CheckSession checkSession = this.checksByTarget.get(playerQuitEvent.getPlayer().getUniqueId());
        if (checkSession != null) {
            this.punish(checkSession, 7, this.getMessagePlain("messages.ban-refusal-reason"), "messages.kick-refusal", false);
            return;
        }
        for (BossBar bossBar : this.bossBars.values()) {
            bossBar.removePlayer(playerQuitEvent.getPlayer());
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST, ignoreCancelled=true)
    public void onCheckedPlayerChat(AsyncChatEvent asyncChatEvent) {
        CheckSession checkSession = this.checksByTarget.get(asyncChatEvent.getPlayer().getUniqueId());
        if (checkSession == null) {
            return;
        }
        asyncChatEvent.setCancelled(true);
        String string = PlainTextComponentSerializer.plainText().serialize(asyncChatEvent.message());
        String string2 = asyncChatEvent.getPlayer().getName();
        UUID uUID = asyncChatEvent.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTask((Plugin)this, () -> {
            Player player;
            CheckSession session = this.checksByTarget.get(uUID);
            if (session == null) {
                return;
            }
            if (this.isAutoBanMessage(string)) {
                Player player2 = Bukkit.getPlayer((UUID)session.moderatorId());
                if (player2 != null && player2.isOnline()) {
                    this.send((CommandSender)player2, "messages.auto-ban-confession", this.placeholder("player", session.targetName()));
                }
                this.punish(session, 30, this.getMessagePlain("messages.ban-cheats-reason"), "messages.kick-cheats");
                return;
            }
            Player player3 = Bukkit.getPlayer((UUID)session.moderatorId());
            if (player3 != null && player3.isOnline()) {
                Component component = this.component("messages.relay-prefix", this.placeholder("player", string2));
                player3.sendMessage(component.append((Component)Component.text((String)string, (TextColor)NamedTextColor.WHITE)));
            }
            if ((player = Bukkit.getPlayer((UUID)session.targetId())) != null && player.isOnline()) {
                player.sendMessage(this.component("messages.target-message-sent"));
            }
        });
    }

    private boolean isAutoBanMessage(String string) {
        return this.matchesAutoBanText(this.normalizeForDetection(string)) || this.matchesAutoBanText(this.normalizeForDetection(this.swapKeyboardLayout(string)));
    }

    private boolean matchesAutoBanText(String string) {
        String[] stringArray = new String[]{"ya", "ia", "i", "im", "iam", "ive", "iuse", "ihave"};
        String[] stringArray2 = new String[]{"chit", "chiter", "cheat", "cheater", "soft"};
        for (String string2 : stringArray) {
            for (String string3 : stringArray2) {
                if (!string.contains(string2 + string3)) continue;
                return true;
            }
        }
        for (String string4 : this.getConfig().getStringList("auto-ban-keywords")) {
            String string5 = this.normalizeForDetection(string4);
            if (string5.length() < 3 || !this.containsKeywordVariant(string, string5)) continue;
            return true;
        }
        return false;
    }

    private boolean containsKeywordVariant(String string, String string2) {
        if (string.contains(string2)) {
            return true;
        }
        if (string2.length() < 4) {
            return false;
        }
        for (int i = Math.max(3, string2.length() - 1); i <= string2.length() + 1; ++i) {
            int n = 0;
            while (n + i <= string.length()) {
                if (this.levenshtein(string.substring(n, n + i), string2) <= 1) {
                    return true;
                }
                ++n;
            }
        }
        return false;
    }

    private int levenshtein(String string, String string2) {
        int n;
        int[] nArray = new int[string2.length() + 1];
        for (n = 0; n <= string2.length(); ++n) {
            nArray[n] = n;
        }
        for (n = 1; n <= string.length(); ++n) {
            int[] nArray2 = new int[string2.length() + 1];
            nArray2[0] = n;
            for (int i = 1; i <= string2.length(); ++i) {
                int n2 = string.charAt(n - 1) == string2.charAt(i - 1) ? 0 : 1;
                nArray2[i] = Math.min(Math.min(nArray2[i - 1] + 1, nArray[i] + 1), nArray[i - 1] + n2);
            }
            nArray = nArray2;
        }
        return nArray[string2.length()];
    }

    private String normalizeForDetection(String string) {
        int n;
        String string2 = Normalizer.normalize(string, Normalizer.Form.NFD).toLowerCase(Locale.ROOT);
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < string2.length(); i += Character.charCount(n)) {
            n = string2.codePointAt(i);
            stringBuilder.append(this.transliterate((char)n));
        }
        return stringBuilder.toString().replaceAll("(.)\\1+", "$1");
    }

    private String transliterate(char c) {
        return switch (c) {
            case '4', '@', 'a', '\u0430' -> "a";
            case '6', 'b', '\u0431' -> "b";
            case 'v', '\u0432' -> "v";
            case 'g', '\u0433' -> "g";
            case 'd', '\u0434' -> "d";
            case '3', 'e', '\u0435', '\u044d', '\u0451' -> "e";
            case '\u0436' -> "zh";
            case 'z', '\u0437' -> "z";
            case '!', '1', 'i', '\u0438', '\u0439' -> "i";
            case 'k', '\u043a' -> "k";
            case 'l', '\u043b' -> "l";
            case 'm', '\u043c' -> "m";
            case 'n', '\u043d' -> "n";
            case '0', 'o', '\u043e' -> "o";
            case 'p', '\u043f' -> "p";
            case 'r', '\u0440' -> "r";
            case '$', 's', '\u0441' -> "s";
            case '7', 't', '\u0442' -> "t";
            case 'y', '\u0443' -> "y";
            case 'f', '\u0444' -> "f";
            case 'h', '\u0445' -> "h";
            case 'c', '\u0446' -> "c";
            case '\u0447' -> "ch";
            case '\u0448' -> "sh";
            case '\u0449' -> "sch";
            case '\u044a', '\u044c' -> "";
            case '\u044b' -> "y";
            case '\u044e' -> "yu";
            case '\u044f' -> "ya";
            case 'u' -> "u";
            case 'w' -> "w";
            case 'x' -> "x";
            case 'q' -> "q";
            case 'j' -> "j";
            default -> "";
        };
    }

    private String swapKeyboardLayout(String string) {
        String string2 = "qwertyuiop[]asdfghjkl;'zxcvbnm,.";
        String string3 = "\u0439\u0446\u0443\u043a\u0435\u043d\u0433\u0448\u0449\u0437\u0445\u044a\u0444\u044b\u0432\u0430\u043f\u0440\u043e\u043b\u0434\u0436\u044d\u044f\u0447\u0441\u043c\u0438\u0442\u044c\u0431\u044e";
        StringBuilder stringBuilder = new StringBuilder(string.length());
        for (char c : string.toLowerCase(Locale.ROOT).toCharArray()) {
            int n = "qwertyuiop[]asdfghjkl;'zxcvbnm,.".indexOf(c);
            int n2 = "\u0439\u0446\u0443\u043a\u0435\u043d\u0433\u0448\u0449\u0437\u0445\u044a\u0444\u044b\u0432\u0430\u043f\u0440\u043e\u043b\u0434\u0436\u044d\u044f\u0447\u0441\u043c\u0438\u0442\u044c\u0431\u044e".indexOf(c);
            if (n >= 0) {
                stringBuilder.append("\u0439\u0446\u0443\u043a\u0435\u043d\u0433\u0448\u0449\u0437\u0445\u044a\u0444\u044b\u0432\u0430\u043f\u0440\u043e\u043b\u0434\u0436\u044d\u044f\u0447\u0441\u043c\u0438\u0442\u044c\u0431\u044e".charAt(n));
                continue;
            }
            if (n2 >= 0) {
                stringBuilder.append("qwertyuiop[]asdfghjkl;'zxcvbnm,.".charAt(n2));
                continue;
            }
            stringBuilder.append(c);
        }
        return stringBuilder.toString();
    }

    private CheckSession getCheckForModerator(UUID uUID) {
        return this.checksByTarget.values().stream().filter(checkSession -> checkSession.moderatorId().equals(uUID)).findFirst().orElse(null);
    }

    private void loadChecks() {
        ConfigurationSection configurationSection = this.checksData.getConfigurationSection("sessions");
        if (configurationSection == null) {
            return;
        }
        long l = System.currentTimeMillis();
        ArrayList<CheckSession> arrayList = new ArrayList<CheckSession>();
        for (String object : configurationSection.getKeys(false)) {
            ConfigurationSection configurationSection2 = configurationSection.getConfigurationSection(object);
            if (configurationSection2 == null) continue;
            try {
                UUID uUID = UUID.fromString(configurationSection2.getString("target-uuid", object));
                UUID uUID2 = UUID.fromString(configurationSection2.getString("moderator-uuid"));
                String string = configurationSection2.getString("target-name");
                String string2 = configurationSection2.getString("moderator-name");
                long l2 = configurationSection2.getLong("started-at");
                long l3 = configurationSection2.getLong("ends-at");
                long l4 = configurationSection2.getLong("total-millis");
                if (string == null || string2 == null || l4 <= 0L) continue;
                CheckSession checkSession = new CheckSession(uUID, string, uUID2, string2, l2, l3, l4);
                this.checksByTarget.put(uUID, checkSession);
                if (l3 > l) continue;
                arrayList.add(checkSession);
            }
            catch (IllegalArgumentException illegalArgumentException) {
                this.getLogger().warning("Invalid record in active-checks.yml: " + object);
            }
        }
        for (CheckSession checkSession : arrayList) {
            this.punish(checkSession, 7, this.getMessagePlain("messages.ban-refusal-reason"), "messages.kick-refusal");
        }
        this.saveChecks();
    }

    private void saveChecks() {
        if (this.checksData == null || this.checksFile == null) {
            return;
        }
        this.checksData.set("sessions", null);
        for (CheckSession checkSession : this.checksByTarget.values()) {
            String string = "sessions." + String.valueOf(checkSession.targetId()) + ".";
            this.checksData.set(string + "target-uuid", (Object)checkSession.targetId().toString());
            this.checksData.set(string + "target-name", (Object)checkSession.targetName());
            this.checksData.set(string + "moderator-uuid", (Object)checkSession.moderatorId().toString());
            this.checksData.set(string + "moderator-name", (Object)checkSession.moderatorName());
            this.checksData.set(string + "started-at", (Object)checkSession.startedAt());
            this.checksData.set(string + "ends-at", (Object)checkSession.endsAt());
            this.checksData.set(string + "total-millis", (Object)checkSession.totalMillis());
        }
        try {
            this.checksData.save(this.checksFile);
        }
        catch (IOException iOException) {
            this.getLogger().severe("Could not save active-checks.yml: " + iOException.getMessage());
        }
    }

    private void sendUsage(CommandSender commandSender) {
        commandSender.sendMessage((Component)LEGACY.deserialize("&e/check <\u043d\u0438\u043a>"));
        commandSender.sendMessage((Component)LEGACY.deserialize("&e/check add <\u0432\u0440\u0435\u043c\u044f>&7 \u2014 \u043d\u0430\u043f\u0440\u0438\u043c\u0435\u0440, &f/check add 5m"));
        commandSender.sendMessage((Component)LEGACY.deserialize("&e/check remove <\u0432\u0440\u0435\u043c\u044f>&7 \u2014 \u043e\u0442\u043d\u044f\u0442\u044c \u0432\u0440\u0435\u043c\u044f, \u043d\u0430\u043f\u0440\u0438\u043c\u0435\u0440: &f/check remove 1m"));
        commandSender.sendMessage((Component)LEGACY.deserialize("&e/check stop"));
        commandSender.sendMessage((Component)LEGACY.deserialize("&e/check ban"));
    }

    private void send(CommandSender commandSender, String string, Map<String, String> map) {
        commandSender.sendMessage(this.component(string, map));
    }

    private void send(CommandSender commandSender, String string) {
        this.send(commandSender, string, Map.of());
    }

    private Component component(String string) {
        return this.component(string, Map.of());
    }

    private Component component(String string, Map<String, String> map) {
        return LEGACY.deserialize(this.replace(this.getConfig().getString(string, ""), map));
    }

    private String legacy(String string, Map<String, String> map) {
        return this.replace(this.getConfig().getString(string, ""), map).replace('&', '\u00a7');
    }

    private String getMessagePlain(String string) {
        return PlainTextComponentSerializer.plainText().serialize(this.component(string));
    }

    private Map<String, String> placeholder(String string, String string2) {
        return Map.of(string, string2);
    }

    private Map<String, String> placeholders(String string, String string2, String string3, String string4) {
        return Map.of(string, string2, string3, string4);
    }

    private Map<String, String> placeholders(String string, String string2, String string3, String string4, String string5, String string6) {
        return Map.of(string, string2, string3, string4, string5, string6);
    }

    private String replace(String string, Map<String, String> map) {
        String string2 = string == null ? "" : string;
        for (Map.Entry<String, String> entry : map.entrySet()) {
            string2 = string2.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return string2;
    }

    private String formatTime(long l) {
        long l2 = Math.max(0L, l / 1000L);
        long l3 = l2 % 60L;
        long l4 = l2 / 60L;
        long l5 = l4 % 60L;
        long l6 = l4 / 60L;
        return l6 > 0L ? String.format(Locale.ROOT, "%d:%02d:%02d", l6, l5, l3) : String.format(Locale.ROOT, "%02d:%02d", l5, l3);
    }
}

