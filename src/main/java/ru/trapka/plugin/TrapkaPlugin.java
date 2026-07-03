package ru.trapka.plugin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Light;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class TrapkaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Random random = new Random();
    private final List<ActiveTrap> activeTraps = new ArrayList<>();
    private NamespacedKey trapItemKey;

    @Override
    public void onEnable() {
        trapItemKey = new NamespacedKey(this, "trapka_item");
        saveDefaultConfig();

        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("trapka") != null) {
            getCommand("trapka").setExecutor(this);
            getCommand("trapka").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (ActiveTrap trap : new ArrayList<>(activeTraps)) {
            trap.restore();
        }
        activeTraps.clear();
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isTrapItem(item)) {
            return;
        }

        event.setCancelled(true);
        if (!player.hasPermission("trapka.use")) {
            send(player, message("no-permission"));
            return;
        }
        if (isInsideActiveTrap(player.getLocation())) {
            send(player, message("already-inside"));
            return;
        }

        if (createTrap(player)) {
            consumeOne(item, player);
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isProtectedTrapBlock(event.getBlock().getLocation()) || isInsideActiveTrap(event.getPlayer().getLocation())) {
            event.setCancelled(true);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        if (!sender.hasPermission("trapka.admin")) {
            send(sender, message("no-permission"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            send(sender, message("reloaded"));
            return true;
        }

        if (args[0].equalsIgnoreCase("give")) {
            handleGive(sender, args);
            return true;
        }

        sendHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("trapka.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("give", "help", "reload"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("1", "16", "32", "64"), args[2]);
        }
        return Collections.emptyList();
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, color("&cИспользование: /trapka give [игрок] [кол-во]"));
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, message("player-not-found"));
            return;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ignored) {
            send(sender, message("invalid-amount"));
            return;
        }

        if (amount < 1 || amount > 64) {
            send(sender, message("invalid-amount"));
            return;
        }

        target.getInventory().addItem(createTrapItem(amount)).values()
                .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));

        send(sender, message("give")
                .replace("%amount%", String.valueOf(amount))
                .replace("%player%", target.getName()));
        send(target, message("received").replace("%amount%", String.valueOf(amount)));
    }

    private boolean createTrap(Player player) {
        FileConfiguration config = getConfig();
        int width = positive(config.getInt("trap.width", 3), 3);
        int height = positive(config.getInt("trap.height", 3), 3);
        int length = positive(config.getInt("trap.length", 3), 3);
        int duration = positive(config.getInt("trap.duration-seconds", 10), 10);
        int maxStairs = Math.max(0, config.getInt("trap.max-stairs", 2));

        Location base = player.getLocation().getBlock().getLocation();
        World world = base.getWorld();
        if (world == null) {
            return false;
        }

        int innerMinX = base.getBlockX() - width / 2;
        int innerMaxX = innerMinX + width - 1;
        int innerMinY = base.getBlockY();
        int innerMaxY = innerMinY + height - 1;
        int innerMinZ = base.getBlockZ() - length / 2;
        int innerMaxZ = innerMinZ + length - 1;

        int outerMinX = innerMinX - 1;
        int outerMaxX = innerMaxX + 1;
        int outerMinY = innerMinY - 1;
        int outerMaxY = innerMaxY + 1;
        int outerMinZ = innerMinZ - 1;
        int outerMaxZ = innerMaxZ + 1;

        if (intersectsActiveTrap(world, outerMinX, outerMaxX, outerMinY, outerMaxY, outerMinZ, outerMaxZ)) {
            send(player, message("active-near"));
            return false;
        }

        ActiveTrap trap = new ActiveTrap(player.getUniqueId(), world, outerMinX, outerMaxX, outerMinY, outerMaxY, outerMinZ, outerMaxZ);
        List<Location> shellLocations = new ArrayList<>();
        List<Location> stairCandidates = new ArrayList<>();

        for (int x = outerMinX; x <= outerMaxX; x++) {
            for (int y = outerMinY; y <= outerMaxY; y++) {
                for (int z = outerMinZ; z <= outerMaxZ; z++) {
                    boolean inside = x >= innerMinX && x <= innerMaxX
                            && y >= innerMinY && y <= innerMaxY
                            && z >= innerMinZ && z <= innerMaxZ;
                    Location location = new Location(world, x, y, z);
                    if (inside) {
                        trap.capture(location.getBlock());
                    } else {
                        shellLocations.add(location);
                        boolean sideWall = y >= innerMinY && y <= innerMaxY
                                && (x == outerMinX || x == outerMaxX || z == outerMinZ || z == outerMaxZ);
                        if (sideWall) {
                            stairCandidates.add(location);
                        }
                    }
                }
            }
        }

        Collections.shuffle(shellLocations, random);
        Collections.shuffle(stairCandidates, random);
        int stairCount = Math.min(maxStairs, stairCandidates.size());
        Set<Location> stairSet = new HashSet<>();
        for (int i = 0; i < stairCount; i++) {
            stairSet.add(stairCandidates.get(i));
        }
        Material stairMaterial = material(config.getString("trap.stair-block"), Material.POLISHED_DEEPSLATE_STAIRS);

        for (Location location : shellLocations) {
            Block block = location.getBlock();
            trap.capture(block);
            if (stairSet.contains(location) && stairMaterial.name().endsWith("_STAIRS")) {
                block.setBlockData(createStairData(stairMaterial, location, base), false);
            } else {
                block.setType(randomWallMaterial(), false);
            }
        }

        for (int x = innerMinX; x <= innerMaxX; x++) {
            for (int y = innerMinY; y <= innerMaxY; y++) {
                for (int z = innerMinZ; z <= innerMaxZ; z++) {
                    new Location(world, x, y, z).getBlock().setType(Material.AIR, false);
                }
            }
        }

        Location lightLocation = new Location(world, base.getBlockX(), innerMinY + Math.max(0, height / 2), base.getBlockZ());
        Block lightBlock = lightLocation.getBlock();
        lightBlock.setBlockData(createLightData(), false);

        activeTraps.add(trap);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            trap.restore();
            activeTraps.remove(trap);
        }, duration * 20L);
        trap.setTask(task);

        send(player, message("used").replace("%duration%", String.valueOf(duration)));
        return true;
    }

    private BlockData createLightData() {
        int level = Math.max(0, Math.min(15, getConfig().getInt("trap.light-level", 15)));
        BlockData data = Bukkit.createBlockData(Material.LIGHT);
        if (data instanceof Light light) {
            light.setLevel(level);
        }
        return data;
    }

    private BlockData createStairData(Material material, Location stairLocation, Location center) {
        BlockData data = Bukkit.createBlockData(material);
        if (data instanceof Directional directional) {
            int dx = stairLocation.getBlockX() - center.getBlockX();
            int dz = stairLocation.getBlockZ() - center.getBlockZ();
            if (Math.abs(dx) > Math.abs(dz)) {
                directional.setFacing(dx > 0 ? BlockFace.WEST : BlockFace.EAST);
            } else {
                directional.setFacing(dz > 0 ? BlockFace.NORTH : BlockFace.SOUTH);
            }
        }
        return data;
    }

    private Material randomWallMaterial() {
        List<String> configured = getConfig().getStringList("trap.wall-blocks");
        if (configured.isEmpty()) {
            return random.nextBoolean() ? Material.DEEPSLATE : Material.POLISHED_DEEPSLATE;
        }
        String selected = configured.get(random.nextInt(configured.size()));
        return material(selected, Material.POLISHED_DEEPSLATE);
    }

    private ItemStack createTrapItem(int amount) {
        Material material = material(getConfig().getString("item.material"), Material.ECHO_SHARD);
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(getConfig().getString("item.name", "&8&lТрапка")));
            List<String> lore = getConfig().getStringList("item.lore").stream().map(this::color).toList();
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(trapItemKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isTrapItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(trapItemKey, PersistentDataType.BYTE);
    }

    private void consumeOne(ItemStack item, Player player) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private boolean isInsideActiveTrap(Location location) {
        return activeTraps.stream().anyMatch(trap -> trap.contains(location));
    }

    private boolean isProtectedTrapBlock(Location location) {
        return activeTraps.stream().anyMatch(trap -> trap.contains(location));
    }

    private boolean intersectsActiveTrap(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        return activeTraps.stream().anyMatch(trap -> trap.intersects(world, minX, maxX, minY, maxY, minZ, maxZ));
    }

    private void sendHelp(CommandSender sender) {
        send(sender, color("&8&m----------------&r &bTrapka &8&m----------------"));
        send(sender, color("&f/trapka give [игрок] [кол-во] &7- выдать трапку"));
        send(sender, color("&f/trapka reload &7- перезагрузить конфиг"));
        send(sender, color("&f/trapka help &7- помощь"));
    }

    private List<String> filter(List<String> values, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name);
        return material == null ? fallback : material;
    }

    private int positive(int value, int fallback) {
        return value <= 0 ? fallback : value;
    }

    private String message(String path) {
        String prefix = getConfig().getString("messages.prefix", "");
        return color(prefix + getConfig().getString("messages." + path, path));
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(text);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private final class ActiveTrap {
        private final UUID owner;
        private final World world;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final List<BlockState> states = new ArrayList<>();
        private final Set<String> captured = new HashSet<>();
        private BukkitTask task;
        private boolean restored;

        private ActiveTrap(UUID owner, World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.owner = owner;
            this.world = world;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        private void capture(Block block) {
            String key = block.getX() + ":" + block.getY() + ":" + block.getZ();
            if (captured.add(key)) {
                states.add(block.getState());
            }
        }

        private boolean contains(Location location) {
            return location.getWorld() != null
                    && location.getWorld().equals(world)
                    && location.getBlockX() >= minX && location.getBlockX() <= maxX
                    && location.getBlockY() >= minY && location.getBlockY() <= maxY
                    && location.getBlockZ() >= minZ && location.getBlockZ() <= maxZ;
        }

        private boolean intersects(World otherWorld, int otherMinX, int otherMaxX, int otherMinY, int otherMaxY, int otherMinZ, int otherMaxZ) {
            return world.equals(otherWorld)
                    && minX <= otherMaxX && maxX >= otherMinX
                    && minY <= otherMaxY && maxY >= otherMinY
                    && minZ <= otherMaxZ && maxZ >= otherMinZ;
        }

        private void setTask(BukkitTask task) {
            this.task = task;
        }

        private void restore() {
            if (restored) {
                return;
            }
            restored = true;
            if (task != null) {
                task.cancel();
            }
            for (int i = states.size() - 1; i >= 0; i--) {
                states.get(i).update(true, false);
            }
        }

        @SuppressWarnings("unused")
        private UUID owner() {
            return owner;
        }
    }
}
