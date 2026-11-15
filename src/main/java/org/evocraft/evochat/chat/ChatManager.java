package org.evocraft.evochat.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.evocraft.evocore.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = "evochat", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChatManager {

    // Aici ținem timpul rămas în secunde pentru jucătorii ONLINE
    public static final Map<UUID, Integer> activeMutes = new ConcurrentHashMap<>();
    private static final Set<UUID> modifiedMutes = ConcurrentHashMap.newKeySet();

    private static ExecutorService dbExecutor = createExecutor();

    // Variabila pentru Global Mute (Când e event)
    public static volatile boolean isGlobalChatMuted = false;

    private static ExecutorService createExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "EvoChat Mute DB");
            t.setDaemon(true);
            return t;
        });
    }

    private static synchronized ExecutorService executor() {
        if (dbExecutor == null || dbExecutor.isShutdown()) {
            dbExecutor = createExecutor();
        }
        return dbExecutor;
    }

    // ==========================================
    // 1. INIȚIALIZARE BAZĂ DE DATE
    // ==========================================
    public static void initializeDB() {
        executor();
        synchronized (DatabaseManager.get()) {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                if (conn == null) return;
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("CREATE TABLE IF NOT EXISTS player_mutes (" +
                            "uuid VARCHAR(36) PRIMARY KEY, " +
                            "time_left INT DEFAULT 0)");
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    public static void setMute(UUID uuid, int seconds) {
        if (uuid == null || seconds <= 0) return;
        modifiedMutes.add(uuid);
        activeMutes.put(uuid, seconds);
        saveMuteAsync(uuid, seconds);
    }

    public static void clearMute(UUID uuid) {
        if (uuid == null) return;
        modifiedMutes.add(uuid);
        activeMutes.remove(uuid);
        saveMuteAsync(uuid, 0);
    }

    private static void saveMuteAsync(UUID uuid, int timeLeft) {
        final int safeTime = Math.max(0, timeLeft);
        try {
            executor().execute(() -> writeMuteSync(uuid, safeTime));
        } catch (Exception e) {
            writeMuteSync(uuid, safeTime);
        }
    }

    private static void writeMuteSync(UUID uuid, int timeLeft) {
        synchronized (DatabaseManager.get()) {
            try {
                Connection conn = DatabaseManager.get().getConnection();
                if (conn == null) return;
                boolean mySql = DatabaseManager.get().isMySql();
                String sql = mySql
                        ? "INSERT INTO player_mutes (uuid, time_left) VALUES (?, ?) ON DUPLICATE KEY UPDATE time_left = VALUES(time_left)"
                        : "INSERT INTO player_mutes (uuid, time_left) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET time_left = excluded.time_left";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, uuid.toString());
                    stmt.setInt(2, Math.max(0, timeLeft));
                    stmt.executeUpdate();
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    public static void flushAndShutdown() {
        for (Map.Entry<UUID, Integer> entry : activeMutes.entrySet()) {
            saveMuteAsync(entry.getKey(), entry.getValue());
        }
        ExecutorService exec = dbExecutor;
        if (exec != null && !exec.isShutdown()) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(5, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exec.shutdownNow();
            }
        }
        for (Map.Entry<UUID, Integer> entry : activeMutes.entrySet()) {
            writeMuteSync(entry.getKey(), entry.getValue());
        }
        activeMutes.clear();
        modifiedMutes.clear();
    }

    // ==========================================
    // 2. EVENIMENTE DE JOIN ȘI LEAVE (Optimizare RAM)
    // ==========================================
    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            modifiedMutes.remove(uuid);
            var server = player.getServer();
            executor().execute(() -> {
                int timeLeft = 0;
                synchronized (DatabaseManager.get()) {
                    try {
                        Connection conn = DatabaseManager.get().getConnection();
                        if (conn == null) return;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT time_left FROM player_mutes WHERE uuid = ?")) {
                            stmt.setString(1, uuid.toString());
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    timeLeft = rs.getInt("time_left");
                                }
                            }
                        }
                    } catch (Exception e) { e.printStackTrace(); }
                }
                final int loadedTime = timeLeft;
                if (loadedTime > 0 && server != null) {
                    server.execute(() -> {
                        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
                        if (online != null && !modifiedMutes.contains(uuid)) {
                            activeMutes.put(uuid, loadedTime);
                        }
                    });
                }
            });
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            int timeLeft = activeMutes.getOrDefault(uuid, 0);
            activeMutes.remove(uuid);
            modifiedMutes.remove(uuid);
            saveMuteAsync(uuid, timeLeft);
        }
    }

    // ==========================================
    // 3. SCURGEREA TIMPULUI DOAR CÂND E ONLINE
    // ==========================================
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side.isServer() && event.phase == TickEvent.Phase.END) {
            if (event.player.tickCount % 20 == 0) { // Se execută o dată pe secundă
                ServerPlayer player = (ServerPlayer) event.player;
                UUID uuid = player.getUUID();

                Integer current = activeMutes.get(uuid);
                if (current != null) {
                    int left = current - 1;
                    if (left <= 0) {
                        clearMute(uuid);
                        player.sendSystemMessage(Component.literal("§a✔ Your mute has expired! You can use chat again."));
                    } else {
                        activeMutes.put(uuid, left);
                    }
                }
            }
        }
    }

    // ==========================================
    // 4. BLOCAREA EFECTIVĂ A CHAT-ULUI
    // ==========================================
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerChat(ServerChatEvent event) {
        if (event.isCanceled()) return;
        ServerPlayer player = event.getPlayer();
        UUID uuid = player.getUUID();

        // Verificăm dacă e GLOBAL MUTE (Bypass pentru grade)
        if (isGlobalChatMuted && !hasPermission(player, "evochat.mutechat.bypass") && !hasPermission(player, "evocore.mutechat.bypass")) {
            player.sendSystemMessage(Component.literal("§c✖ Global chat is currently disabled for everyone!"));
            event.setCanceled(true);
            return;
        }

        // Verificăm dacă jucătorul are MUTE PERSONAL
        Integer timeLeftObj = activeMutes.get(uuid);
        if (timeLeftObj != null && timeLeftObj > 0) {
            int timeLeft = timeLeftObj;
            int minutes = timeLeft / 60;
            int seconds = timeLeft % 60;

            player.sendSystemMessage(Component.literal("§c✖ You are muted in chat!"));
            player.sendSystemMessage(Component.literal("§c✖ Time remaining: §e" + minutes + "m " + seconds + "s§c (you must stay online for the timer to decrease)."));
            event.setCanceled(true);
        }
    }

    // ==========================================
    // HELPER PENTRU LUCKPERMS
    // ==========================================
    public static boolean hasPermission(ServerPlayer player, String permission) {
        // Dacă e Operator (OP), are acces la tot din oficiu
        if (player.hasPermissions(2)) return true;

        // Verificăm permisiunea prin LuckPerms API
        try {
            net.luckperms.api.LuckPerms lp = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User user = lp.getUserManager().getUser(player.getUUID());
            if (user != null) {
                return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
            }
        } catch (Throwable e) {}

        return false;
    }
}
