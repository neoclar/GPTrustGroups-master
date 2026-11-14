package com.neoclar.gptrustgroups.commands;

import com.neoclar.gptrustgroups.GPTrustGroups;
import com.neoclar.gptrustgroups.storage.Group;
import com.neoclar.gptrustgroups.storage.GroupManager;
import com.neoclar.gptrustgroups.storage.Messages;
import me.ryanhamshire.GriefPrevention.TextMode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.stream.Collectors;

public class GroupCommand implements CommandExecutor, TabCompleter {

    private final GPTrustGroups plugin;
    private final GroupManager manager;
    private final Messages messages;

    public GroupCommand(GPTrustGroups plugin) {
        this.plugin = plugin;
        this.manager = plugin.getGroupManager();
        this.messages = plugin.getMessages();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
        if (args.length == 0) { help(player, label); return true; }

        String sub = args[0].toLowerCase();
        try {
            switch (sub) {
                case "create" -> cmdCreate(player, args);
                case "delete" -> cmdDelete(player, args);
                case "add" -> cmdAdd(player, args);
                case "remove" -> cmdRemove(player, args);
                case "exclude" -> cmdExclude(player, args, true);
                case "unexclude" -> cmdExclude(player, args, false);
                case "list" -> cmdList(player, args);
                case "info" -> cmdInfo(player, args);
                case "my" -> cmdMy(player);
                default -> help(player, label);
            }
        } catch (Exception e) {
            send(player, TextMode.Err, "Error: " + e.getMessage());
            plugin.getLogger().warning("Error in /trustgroups " + sub + ": " + e);
        }
        return true;
    }

    private Group owned(Player player, String name) {
        return manager.get(player.getUniqueId(), name);
    }

    private void cmdCreate(Player player, String[] args) {
        if (args.length < 2) { send(player, TextMode.Err, messages.get("Create.Usage")); return; }
        String name = args[1].toLowerCase();
        if (GroupManager.RESERVED_NAMES.contains(name)) { send(player, TextMode.Err, messages.get("Create.ReservedName")); return; }
        if (manager.get(player.getUniqueId(), name) != null) { send(player, TextMode.Err, messages.format("Create.NameTaken", name)); return; }
        Group g = manager.create(args[1], player.getUniqueId());
        if (g == null) { send(player, TextMode.Err, messages.get("Create.InvalidName")); return; }
        send(player, TextMode.Success, messages.format("Create.Success", g.getName()));
    }

    private void cmdDelete(Player player, String[] args) {
        if (args.length < 2) { send(player, TextMode.Err, messages.get("Delete.Usage")); return; }
        Group g = owned(player, args[1]);
        if (g == null) { send(player, TextMode.Err, messages.format("General.GroupNotFound", args[1].toLowerCase())); return; }
        if (g.isPublic()) { send(player, TextMode.Err, messages.get("Delete.PublicForbidden")); return; }
        manager.delete(player.getUniqueId(), g.getName());
        send(player, TextMode.Success, messages.format("Delete.Success", g.getName()));
    }

    private void cmdAdd(Player player, String[] args) {
        if (args.length < 3) { send(player, TextMode.Err, messages.get("Add.Usage")); return; }
        Group g = owned(player, args[1]);
        if (g == null) { send(player, TextMode.Err, messages.format("General.GroupNotFound", args[1].toLowerCase())); return; }
        if (g.isPublic()) { send(player, TextMode.Err, messages.get("General.NotOwner")); return; }

        String target = args[2];
        if (target.startsWith("#")) {
            String child = stripHash(target);
            if (child == null) { send(player, TextMode.Err, messages.get("Add.Usage")); return; }
            String err = manager.addNested(g, child);
            if (err != null) {
                String msg = switch (err) {
                    case "cycle" -> messages.get("Add.Group.Cycle");
                    case "tooDeep" -> messages.get("Add.Group.TooDeep");
                    case "already" -> messages.format("Add.Group.Already", child, g.getName());
                    case "self" -> messages.get("Exclude.Group.SameGroup");
                    case "notFound" -> messages.format("General.GroupNotFound", child);
                    default -> err;
                };
                send(player, TextMode.Err, msg);
                return;
            }
            send(player, TextMode.Success, messages.format("Add.Group.Success", child, g.getName()));
        } else {
            UUID uuid = resolvePlayer(target);
            if (uuid == null) { send(player, TextMode.Err, messages.get("General.PlayerNotFound")); return; }
            if (!manager.addMember(g, uuid)) {
                send(player, TextMode.Warn, messages.format("Add.Player.Already", displayName(uuid), g.getName()));
                return;
            }
            send(player, TextMode.Success, messages.format("Add.Player.Success", displayName(uuid), g.getName()));
            if (g.getExcludedPlayers().contains(uuid)) {
                send(player, TextMode.Warn, messages.format("Add.Player.ExcludedWarning", displayName(uuid)));
            }
        }
    }

    private void cmdRemove(Player player, String[] args) {
        if (args.length < 3) { send(player, TextMode.Err, messages.get("Remove.Usage")); return; }
        Group g = owned(player, args[1]);
        if (g == null) { send(player, TextMode.Err, messages.format("General.GroupNotFound", args[1].toLowerCase())); return; }
        if (g.isPublic()) { send(player, TextMode.Err, messages.get("General.NotOwner")); return; }

        String target = args[2];
        if (target.startsWith("#")) {
            String child = stripHash(target);
            if (child == null) { send(player, TextMode.Err, messages.get("Remove.Usage")); return; }
            if (manager.removeNested(g, child)) {
                send(player, TextMode.Success, messages.format("Remove.Group.Success", child, g.getName()));
            } else {
                send(player, TextMode.Warn, messages.format("Remove.Group.NotPresent", child, g.getName()));
            }
        } else {
            UUID uuid = resolvePlayer(target);
            if (uuid == null) { send(player, TextMode.Err, messages.get("General.PlayerNotFound")); return; }
            if (manager.removeMember(g, uuid)) {
                send(player, TextMode.Success, messages.format("Remove.Player.Success", displayName(uuid), g.getName()));
            } else {
                send(player, TextMode.Warn, messages.format("Remove.Player.NotPresent", displayName(uuid), g.getName()));
            }
        }
    }

    private void cmdExclude(Player player, String[] args, boolean exclude) {
        String action = exclude ? "exclude" : "unexclude";
        if (args.length < 3) {
            send(player, TextMode.Err, messages.get(action.equals("exclude") ? "Exclude.Usage" : "Unexclude.Usage"));
            return;
        }
        Group g = owned(player, args[1]);
        if (g == null) { send(player, TextMode.Err, messages.format("General.GroupNotFound", args[1].toLowerCase())); return; }
        if (g.isPublic()) { send(player, TextMode.Err, messages.get("General.NotOwner")); return; }

        String target = args[2];
        if (target.startsWith("#")) {
            String child = stripHash(target);
            if (child == null) { send(player, TextMode.Err, messages.get("Exclude.Usage")); return; }

            if (exclude) {
                if (child.equalsIgnoreCase(g.getName())) {
                    send(player, TextMode.Err, messages.get("Exclude.Group.SameGroup"));
                } else if (manager.get(player.getUniqueId(), child) == null) {
                    send(player, TextMode.Err, messages.format("Exclude.Group.NotExists", child));
                } else if (manager.excludeGroup(g, child)) {
                    send(player, TextMode.Success, messages.format("Exclude.Group.Success", child, g.getName()));
                } else {
                    send(player, TextMode.Warn, messages.format("Exclude.Group.Already", child, g.getName()));
                }
            } else {
                if (manager.unexcludeGroup(g, child)) {
                    send(player, TextMode.Success, messages.format("Unexclude.Group.Success", child, g.getName()));
                } else {
                    send(player, TextMode.Warn, messages.format("Unexclude.Group.NotExcluded", child, g.getName()));
                }
            }
        } else {
            UUID uuid = resolvePlayer(target);
            if (uuid == null) { send(player, TextMode.Err, messages.get("General.PlayerNotFound")); return; }

            if (exclude) {
                if (g.getMembers().contains(uuid)) {
                    manager.removeMember(g, uuid);
                    send(player, TextMode.Success, messages.format("Exclude.Player.WasMember", displayName(uuid), g.getName()));
                } else if (g.getOwner().equals(uuid)) {
                    send(player, TextMode.Err, messages.get("Exclude.Player.Owner"));
                } else if (manager.excludePlayer(g, uuid)) {
                    send(player, TextMode.Success, messages.format("Exclude.Player.Success", displayName(uuid), g.getName()));
                } else {
                    send(player, TextMode.Warn, messages.format("Exclude.Player.Already", displayName(uuid), g.getName()));
                }
            } else {
                if (manager.unexcludePlayer(g, uuid)) {
                    send(player, TextMode.Success, messages.format("Unexclude.Player.Success", displayName(uuid), g.getName()));
                } else {
                    send(player, TextMode.Warn, messages.format("Unexclude.Player.NotExcluded", displayName(uuid), g.getName()));
                }
            }
        }
    }

    private void cmdList(Player player, String[] args) {
        if (args.length < 2) { send(player, TextMode.Err, messages.get("List.Usage")); return; }
        Group g = owned(player, args[1]);
        if (g == null) { send(player, TextMode.Err, messages.format("General.GroupNotFound", args[1].toLowerCase())); return; }

        send(player, TextMode.Info, messages.format("List.Header", g.getName()));
        if (g.isPublic()) {
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.get("List.System"));
        } else {
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("List.Owner", displayName(g.getOwner())));
        }
        if (!g.getMembers().isEmpty()) {
            String s = g.getMembers().stream().map(this::displayName).collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("List.Members", s));
        }
        if (!g.getNestedGroups().isEmpty()) {
            String s = g.getNestedGroups().stream().map(x -> "#" + x).collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("List.Nested", s));
        }
        if (!g.getExcludedPlayers().isEmpty()) {
            String s = g.getExcludedPlayers().stream().map(this::displayName).collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("List.ExcludedPlayers", s));
        }
        if (!g.getExcludedGroups().isEmpty()) {
            String s = g.getExcludedGroups().stream().map(x -> "#" + x).collect(Collectors.joining(", "));
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("List.ExcludedGroups", s));
        }
    }

    private void cmdInfo(Player player, String[] args) {
        if (args.length < 2) { send(player, TextMode.Err, messages.get("Info.Usage")); return; }
        Group g = owned(player, args[1]);
        if (g == null) { send(player, TextMode.Err, messages.format("General.GroupNotFound", args[1].toLowerCase())); return; }

        send(player, TextMode.Info, messages.format("Info.Header", g.getName()));
        if (g.isPublic()) {
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.get("Info.System"));
        } else {
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("Info.Owner", displayName(g.getOwner())));
        }
        player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("Info.Members", g.getMembers().size()));
        player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("Info.Nested", g.getNestedGroups().size()));
        player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("Info.ExcludedPlayers", g.getExcludedPlayers().size()));
        player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + messages.format("Info.ExcludedGroups", g.getExcludedGroups().size()));
        // Строка "You are in this group" убрана
    }

    private void cmdMy(Player player) {
        List<Group> owned = new ArrayList<>(manager.getAllFor(player.getUniqueId()));
        owned.sort(Comparator.comparing(Group::getName));
        if (owned.isEmpty()) { send(player, TextMode.Warn, messages.get("My.Empty")); return; }

        send(player, TextMode.Info, messages.get("My.Header"));
        for (Group g : owned) {
            String entry = messages.format("My.Entry", "#" + g.getName(), g.getMembers().size(), g.getNestedGroups().size());
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.WHITE + entry);
        }
    }

    private void help(Player player, String label) {
        send(player, TextMode.Info, messages.get("Help.Header"));
        String[] keys = {"Help.Create", "Help.Delete", "Help.Add", "Help.Remove",
                "Help.Exclude", "Help.Unexclude", "Help.List", "Help.Info", "Help.My"};
        for (String k : keys) {
            player.sendMessage(ChatColor.GOLD + "> " + ChatColor.YELLOW + "/" + label + " "
                    + ChatColor.WHITE + messages.get(k));
        }
        send(player, TextMode.Instr, messages.get("Help.Note"));
    }

    // ---------- TabCompleter ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!(sender instanceof Player player)) return Collections.emptyList();

        if (args.length == 1) {
            return filter(List.of("create", "delete", "add", "remove", "exclude", "unexclude",
                    "list", "info", "my"), args[0]);
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "create", "my" -> Collections.emptyList();
                case "delete", "add", "remove", "exclude", "unexclude" -> filter(myGroupNames(player), args[1]);
                case "list", "info" -> filter(myGroupNamesWithPublic(player), args[1]);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 3) {
            if (sub.equals("add") || sub.equals("remove") || sub.equals("exclude") || sub.equals("unexclude")) {
                List<String> options = new ArrayList<>(onlinePlayerNames());
                for (String gn : myGroupNamesWithPublic(player)) options.add("#" + gn);
                return filter(options, args[2]);
            }
        }
        return Collections.emptyList();
    }

    private List<String> myGroupNames(Player player) {
        return manager.getAllFor(player.getUniqueId()).stream().map(Group::getName).toList();
    }
    private List<String> myGroupNamesWithPublic(Player player) {
        List<String> names = new ArrayList<>(myGroupNames(player));
        names.add(GroupManager.PUBLIC_NAME);
        return names;
    }
    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
    private List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(p)).sorted().toList();
    }

    // ---------- Утилиты ----------

    private void send(Player player, ChatColor color, String message) { player.sendMessage(color + message); }

    private UUID resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        @SuppressWarnings("deprecation")
		OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    private String displayName(UUID uuid) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
        return p.getName() != null ? p.getName() : uuid.toString().substring(0, 8);
    }

    private String stripHash(String arg) {
        if (arg == null || !arg.startsWith("#") || arg.length() < 2) return null;
        return arg.substring(1).toLowerCase();
    }
}