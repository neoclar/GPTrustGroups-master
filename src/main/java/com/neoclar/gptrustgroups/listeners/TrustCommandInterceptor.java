package com.neoclar.gptrustgroups.listeners;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.TabCompleteEvent;

import com.neoclar.gptrustgroups.GPTrustGroups;
import com.neoclar.gptrustgroups.storage.Group;
import com.neoclar.gptrustgroups.storage.GroupManager;
import com.neoclar.gptrustgroups.storage.Messages;
import com.neoclar.gptrustgroups.util.GroupIdMapper;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.TextMode;

public class TrustCommandInterceptor implements Listener {

    @SuppressWarnings("unused")
	private final GPTrustGroups plugin;
    private final GroupManager groupManager;
    private final Messages messages;

    private record TrustCommand(String name, ClaimPermission permission, String levelKey) {}
    private static final List<TrustCommand> COMMANDS = List.of(
            new TrustCommand("trust", ClaimPermission.Build, "Build"),
            new TrustCommand("containertrust", containerPermission(), "Containers"),
            new TrustCommand("accesstrust", ClaimPermission.Access, "Access"),
            new TrustCommand("permissiontrust", ClaimPermission.Manage, "Manage")
    );

    /** В новых GP Inventory переименован в Container; Inventory оставлен как псевдоним и будет удалён. */
    private static ClaimPermission containerPermission() {
        try { return ClaimPermission.valueOf("Container"); }
        catch (IllegalArgumentException e) { return ClaimPermission.valueOf("Inventory"); }
    }

    public TrustCommandInterceptor(GPTrustGroups plugin) {
        this.plugin = plugin;
        this.groupManager = plugin.getGroupManager();
        this.messages = plugin.getMessages();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (!message.startsWith("/")) return;

        String[] parts = message.substring(1).split("\\s+", 3);
        if (parts.length < 1) return;

        String cmd = parts[0].toLowerCase();

        if (cmd.equals("trustlist") || cmd.equals("trustlistall")) {
            event.setCancelled(true);
            showTrustList(event.getPlayer());
            return;
        }

        if (parts.length < 2) return;
        String arg = parts[1];

        boolean isUntrust = cmd.equals("untrust");
        if (!isUntrust && COMMANDS.stream().noneMatch(c -> c.name().equals(cmd))) return;
        if (!arg.startsWith("#")) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        String groupName = arg.substring(1).toLowerCase();
        Group group = groupManager.get(player.getUniqueId(), groupName);
        if (group == null) {
            send(player, TextMode.Err, messages.format("Trust.GroupNotFound", groupName));
            return;
        }

        Claim claim = GriefPrevention.instance.dataStore.getClaimAt(player.getLocation(), false, null);
        if (claim == null) {
            send(player, TextMode.Err, messages.get("Trust.NoClaim"));
            return;
        }

        String groupId = group.getId().toString();

        if (isUntrust) {
            // Ищем группу во всех списках привата, без switch по ClaimPermission (Inventory → Container в новых GP)
            boolean removed = false;
            for (int i = 0; i < 4; i++) {
                if (getList(claim, i).contains(groupId)) removed = true;
            }
            if (removed) {
                claim.setPermission(groupId, null);
                GriefPrevention.instance.dataStore.saveClaim(claim);
                send(player, TextMode.Success, messages.format("Trust.GroupRevoked", groupName));
            } else {
                send(player, TextMode.Warn, messages.format("Trust.GroupNotHad", groupName));
            }
            return;
        }

        TrustCommand tc = COMMANDS.stream().filter(c -> c.name().equals(cmd)).findFirst().orElse(null);
        if (tc == null) return;

        claim.setPermission(groupId, tc.permission());
        GriefPrevention.instance.dataStore.saveClaim(claim);

        send(player, TextMode.Success,
                messages.format("Trust.GroupGranted", groupName, messages.get("Trust.Level." + tc.levelKey)));
    }

    // ---------- /trustlist ----------
private void showTrustList(Player player) {
    Claim claim = GriefPrevention.instance.dataStore.getClaimAt(player.getLocation(), false, null);

    if (claim == null) {
        GriefPrevention.sendMessage(player, TextMode.Err, messages.get("TrustList.NoClaim"));
        return;
    }

    // Только владелец/менеджер может смотреть список
    java.util.function.Supplier<String> errorMessage =
            claim.checkPermission(player, ClaimPermission.Manage, null);
    if (errorMessage != null) {
        GriefPrevention.sendMessage(player, TextMode.Err, errorMessage.get());
        return;
    }

    ArrayList<String> builders = new ArrayList<>();
    ArrayList<String> containers = new ArrayList<>();
    ArrayList<String> accessors = new ArrayList<>();
    ArrayList<String> managers = new ArrayList<>();
    claim.getPermissions(builders, containers, accessors, managers);

    // Заголовок — бирюзовый (TextMode.Info), как в GP
    GriefPrevention.sendMessage(player, TextMode.Info, messages.get("TrustList.Header"));

    // Строка 1: Manage — GOLD
    StringBuilder line = new StringBuilder();
    line.append(ChatColor.GOLD).append('>');
    for (String manager : managers) {
        line.append(formatEntry(manager, ChatColor.GOLD)).append(' ');
    }
    player.sendMessage(line.toString());

    // Строка 2: Build — YELLOW
    line = new StringBuilder();
    line.append(ChatColor.YELLOW).append('>');
    for (String builder : builders) {
        line.append(formatEntry(builder, ChatColor.YELLOW)).append(' ');
    }
    player.sendMessage(line.toString());

    // Строка 3: Containers — GREEN
    line = new StringBuilder();
    line.append(ChatColor.GREEN).append('>');
    for (String container : containers) {
        line.append(formatEntry(container, ChatColor.GREEN)).append(' ');
    }
    player.sendMessage(line.toString());

    // Строка 4: Access — BLUE
    line = new StringBuilder();
    line.append(ChatColor.BLUE).append('>');
    for (String accessor : accessors) {
        line.append(formatEntry(accessor, ChatColor.BLUE)).append(' ');
    }
    player.sendMessage(line.toString());

    // Легенда
    player.sendMessage(
            ChatColor.GOLD + messages.get("TrustList.Manage") + " " +
            ChatColor.YELLOW + messages.get("TrustList.Build") + " " +
            ChatColor.GREEN + messages.get("TrustList.Containers") + " " +
            ChatColor.BLUE + messages.get("TrustList.Access"));

    // Опционально — предупреждение о subclaim restriction
    if (claim.getSubclaimRestrictions()) {
        GriefPrevention.sendMessage(player, TextMode.Err, messages.get("TrustList.SubclaimRestriction"));
    }
}

/**
 * Форматирует запись для trustlist.
 * @param lineColor цвет текущей строки — чтобы после нашей группы вернуть цвет строки
 */
private String formatEntry(String entry, ChatColor lineColor) {
    try {
        UUID id = UUID.fromString(entry);
		Group g = GroupIdMapper.groupFor(id);
		if (g != null) {
			if (g.isPublic()) return messages.get("TrustList.Public") + lineColor;
			return ChatColor.AQUA + "#" + g.getName() + lineColor;
		}
        OfflinePlayer p = Bukkit.getOfflinePlayer(id);
        if (p.getName() != null) {
            return p.getName();
        }
        return messages.get("TrustList.UnknownPlayer");
    } catch (IllegalArgumentException e) {
        return entry;
    }
}
    // ---------- Автодополнение (без изменений) ----------
@EventHandler(priority = EventPriority.LOW)
public void onTabComplete(TabCompleteEvent event) {
    if (!(event.getSender() instanceof Player player)) return; // ← вот здесь теперь есть player

    String buffer = event.getBuffer();
    if (!buffer.startsWith("/")) return;

    String withoutSlash = buffer.substring(1);
    String[] parts = withoutSlash.split("\\s+", 2);
    String cmd = parts[0].toLowerCase();

    boolean isTrustCmd = cmd.equals("trust") || cmd.equals("containertrust")
            || cmd.equals("accesstrust") || cmd.equals("permissiontrust")
            || cmd.equals("untrust");
    if (!isTrustCmd) return;

    if (parts.length == 1 || parts[1].isEmpty() || parts[1].startsWith("#")) {
        String prefix = parts.length == 1 ? "" : parts[1];

        List<String> groups = new ArrayList<>();
        groupManager.getAllFor(player.getUniqueId()).forEach(g -> groups.add("#" + g.getName()));
        groups.add("#" + GroupManager.PUBLIC_NAME);

        List<String> filtered = groups.stream()
                .filter(s -> s.toLowerCase().startsWith(prefix.toLowerCase()))
                .sorted()
                .toList();
        event.getCompletions().addAll(filtered);
    }
}

    // ---------- Утилиты ----------

private void send(Player player, ChatColor color, String message) {
    player.sendMessage(color + message);
}

    private List<String> getList(Claim claim, int index) {
        var b = new ArrayList<String>();
        var c = new ArrayList<String>();
        var a = new ArrayList<String>();
        var m = new ArrayList<String>();
        claim.getPermissions(b, c, a, m);
        return switch (index) {
            case 0 -> b;
            case 1 -> c;
            case 2 -> a;
            case 3 -> m;
            default -> Collections.emptyList();
        };
    }
}