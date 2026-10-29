package com.neoclar.gptrustgroups.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import com.neoclar.gptrustgroups.util.GroupIdMapper;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class GroupManager {

    public static final int MAX_NESTING_HEIGHT = 5;
    public static final Set<String> RESERVED_NAMES = Set.of("a", "e", "p", "r", "s", "public");
    private static final String NAME_REGEX = "^[a-z0-9_-]{2,32}$";

    public static final String PUBLIC_NAME = "public";
    public static final UUID PUBLIC_OWNER = new UUID(0L, 0L);
    public static final UUID PUBLIC_ID =
            UUID.nameUUIDFromBytes("GPTrustGroups:public-group".getBytes());

    public enum AccessResult { ALLOWED, DENIED, NONE }

    private final JavaPlugin plugin;
    private final File groupsFile;
    private FileConfiguration config;

    private final Map<UUID, Map<String, Group>> groupsByOwner = new HashMap<>();
    private final Map<UUID, Group> groupsById = new HashMap<>();

    public GroupManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.groupsFile = new File(plugin.getDataFolder(), "groups.yml");
        load();
        ensurePublicGroup();
    }

    // ---------- I/O ----------

    public void load() {
        if (!groupsFile.exists()) plugin.saveResource("groups.yml", false);
        this.config = YamlConfiguration.loadConfiguration(groupsFile);
        groupsByOwner.clear();
        groupsById.clear();
        GroupIdMapper.clear();

        ConfigurationSection section = config.getConfigurationSection("groups");
        if (section == null) {
            plugin.getLogger().info("No groups found.");
            return;
        }

        boolean needsSave = false;
        int count = 0;

        for (String ownerKey : section.getKeys(false)) {
            UUID owner;
            try { owner = UUID.fromString(ownerKey); }
            catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid owner UUID: " + ownerKey + " — skipping namespace.");
                continue;
            }

            ConfigurationSection nsSection = section.getConfigurationSection(ownerKey);
            if (nsSection == null) continue;

            Map<String, Group> ns = groupsByOwner.computeIfAbsent(owner, k -> new HashMap<>());

            for (String name : nsSection.getKeys(false)) {
                ConfigurationSection gs = nsSection.getConfigurationSection(name);
                if (gs == null) continue;

                UUID id = parseUuid(gs.getString("id"));
                if (PUBLIC_NAME.equalsIgnoreCase(name) && !PUBLIC_OWNER.equals(owner)) {
                    // Иначе такая группа получила бы PUBLIC_ID и подменила системную #public
                    plugin.getLogger().warning("Group 'public' of " + ownerKey + " uses a reserved name — skipping.");
                    continue;
                }
                if (PUBLIC_NAME.equalsIgnoreCase(name)) {
                    if (!PUBLIC_ID.equals(id)) { id = PUBLIC_ID; needsSave = true; }
                } else if (id == null) {
                    id = UUID.randomUUID();
                    needsSave = true;
                }

                Group g = new Group(id, name, owner);
                for (String s : gs.getStringList("members")) {
                    UUID u = parseUuid(s);
                    if (u != null) g.addMember(u);
                }
                for (String s : gs.getStringList("nested-groups")) g.addNested(s);
                for (String s : gs.getStringList("excluded-players")) {
                    UUID u = parseUuid(s);
                    if (u != null) g.addExcludedPlayer(u);
                }
                for (String s : gs.getStringList("excluded-groups")) g.addExcludedGroup(s);

                ns.put(name.toLowerCase(), g);
                groupsById.put(g.getId(), g);
                GroupIdMapper.register(g);
                count++;
            }
        }

        if (needsSave) save();
        plugin.getLogger().info("Loaded " + count + " group(s) for " + groupsByOwner.size() + " owner(s).");
    }

    public void save() {
        config.set("groups", null);
        for (Map.Entry<UUID, Map<String, Group>> entry : groupsByOwner.entrySet()) {
            String ownerKey = entry.getKey().toString();
            for (Group g : entry.getValue().values()) {
                String path = "groups." + ownerKey + "." + g.getName();
                config.set(path + ".id", g.getId().toString());
                config.set(path + ".members", toStrList(g.getMembers()));
                config.set(path + ".nested-groups", new ArrayList<>(g.getNestedGroups()));
                config.set(path + ".excluded-players", toStrList(g.getExcludedPlayers()));
                config.set(path + ".excluded-groups", new ArrayList<>(g.getExcludedGroups()));
            }
        }
        try {
            config.save(groupsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save groups.yml: " + e.getMessage());
        }
    }

    // ---------- Lookup ----------

    public Group getPublicGroup() { return groupsById.get(PUBLIC_ID); }

    public Group get(UUID owner, String name) {
        String key = name.toLowerCase();
        if (key.equals(PUBLIC_NAME)) return getPublicGroup();
        Map<String, Group> ns = groupsByOwner.get(owner);
        return ns == null ? null : ns.get(key);
    }

    public Group getById(UUID id) { return groupsById.get(id); }

    public Collection<Group> getAllFor(UUID owner) {
        Map<String, Group> ns = groupsByOwner.get(owner);
        return ns == null ? Collections.emptyList() : Collections.unmodifiableCollection(ns.values());
    }

    // ---------- CRUD ----------

    public Group create(String name, UUID owner) {
        String key = name.toLowerCase();
        if (!key.matches(NAME_REGEX)) return null;
        if (RESERVED_NAMES.contains(key)) return null;

        Map<String, Group> ns = groupsByOwner.computeIfAbsent(owner, k -> new HashMap<>());
        if (ns.containsKey(key)) return null;

        Group g = new Group(UUID.randomUUID(), name, owner);
        ns.put(key, g);
        groupsById.put(g.getId(), g);
        GroupIdMapper.register(g);
        save();
        return g;
    }

    public boolean delete(UUID owner, String name) {
        String key = name.toLowerCase();
        if (key.equals(PUBLIC_NAME)) return false;
        Map<String, Group> ns = groupsByOwner.get(owner);
        if (ns == null) return false;
        Group removed = ns.remove(key);
        if (removed == null) return false;

        groupsById.remove(removed.getId());
        GroupIdMapper.unregister(removed.getId());

        for (Group other : ns.values()) {
            other.removeNested(key);
            other.removeExcludedGroup(key);
        }
        save();
        return true;
    }

    // ---------- Members ----------

    public boolean addMember(Group group, UUID player) {
        boolean added = group.addMember(player);
        if (added) save();
        return added;
    }

    public boolean removeMember(Group group, UUID player) {
        boolean removed = group.removeMember(player);
        if (removed) save();
        return removed;
    }

    public boolean excludePlayer(Group group, UUID player) {
        if (group.getOwner().equals(player)) return false;
        boolean added = group.addExcludedPlayer(player);
        if (added) save();
        return added;
    }

    public boolean unexcludePlayer(Group group, UUID player) {
        boolean removed = group.removeExcludedPlayer(player);
        if (removed) save();
        return removed;
    }

    // ---------- Nesting / Exclusions (groups) ----------

    /** @return null при успехе, иначе "self" | "already" | "cycle" | "tooDeep" | "notFound". */
    public String addNested(Group parent, String childName) {
        String c = childName.toLowerCase();
        Group child = resolveNested(parent, c);
        if (child == null) return "notFound";
        if (child.getId().equals(parent.getId())) return "self";
        if (parent.getNestedGroups().contains(c)) return "already";
        if (canReach(child, parent, new HashSet<>())) return "cycle";

        int newHeight = Math.max(height(parent, new HashSet<>()), 1 + height(child, new HashSet<>()));
        if (newHeight > MAX_NESTING_HEIGHT) return "tooDeep";

        parent.addNested(c);
        save();
        return null;
    }

    public boolean removeNested(Group parent, String childName) {
        boolean removed = parent.removeNested(childName);
        if (removed) save();
        return removed;
    }

    public boolean excludeGroup(Group parent, String excludedName) {
        String e = excludedName.toLowerCase();
        Group child = resolveNested(parent, e);
        if (child == null) return false;
        if (child.getId().equals(parent.getId())) return false;
        boolean added = parent.addExcludedGroup(e);
        if (added) save();
        return added;
    }

    public boolean unexcludeGroup(Group parent, String excludedName) {
        boolean removed = parent.removeExcludedGroup(excludedName);
        if (removed) save();
        return removed;
    }

    // ---------- Access ----------

    public AccessResult getAccessStatus(Group group, UUID player) {
        if (group == null) return AccessResult.NONE;
        return checkAccess(group, player, new HashSet<>());
    }

    private AccessResult checkAccess(Group group, UUID player, Set<UUID> path) {
        if (path.contains(group.getId())) return AccessResult.NONE;
        if (group.getExcludedPlayers().contains(player)) return AccessResult.DENIED;
        if (group.getMembers().contains(player)) return AccessResult.ALLOWED;
        if (group.isPublic()) return AccessResult.ALLOWED;

        path.add(group.getId());
        boolean hasAllowed = false, hasDenied = false;
        try {
            for (String nestedName : group.getNestedGroups()) {
                Group nested = resolveNested(group, nestedName);
                if (nested == null) continue;
                AccessResult r = checkAccess(nested, player, path);
                if (r == AccessResult.DENIED) hasDenied = true;
                else if (r == AccessResult.ALLOWED) hasAllowed = true;
            }
        } finally {
            path.remove(group.getId());
        }

        if (hasDenied) return AccessResult.DENIED;
        if (hasAllowed) return AccessResult.ALLOWED;
        return AccessResult.NONE;
    }

    private Group resolveNested(Group parent, String nestedName) {
        if (nestedName.equalsIgnoreCase(PUBLIC_NAME)) return getPublicGroup();
        return get(parent.getOwner(), nestedName);
    }

    // ---------- Internals ----------

    private boolean canReach(Group from, Group target, Set<UUID> visited) {
        if (from.getId().equals(target.getId())) return true;
        if (!visited.add(from.getId())) return false;
        for (String nestedName : from.getNestedGroups()) {
            Group nested = resolveNested(from, nestedName);
            if (nested != null && canReach(nested, target, visited)) return true;
        }
        return false;
    }

    private int height(Group group, Set<UUID> visited) {
        if (!visited.add(group.getId())) return 0;
        if (group.getNestedGroups().isEmpty()) return 0;
        int max = 0;
        for (String nestedName : group.getNestedGroups()) {
            Group nested = resolveNested(group, nestedName);
            if (nested == null) continue;
            max = Math.max(max, height(nested, visited));
        }
        return max + 1;
    }

    private void ensurePublicGroup() {
        Map<String, Group> ns = groupsByOwner.computeIfAbsent(PUBLIC_OWNER, k -> new HashMap<>());
        Group existing = ns.get(PUBLIC_NAME);
        if (existing == null) {
            Group pub = new Group(PUBLIC_ID, PUBLIC_NAME, PUBLIC_OWNER);
            ns.put(PUBLIC_NAME, pub);
            groupsById.put(pub.getId(), pub);
            GroupIdMapper.register(pub);
            save();
            plugin.getLogger().info("Created system group #public.");
        } else {
            GroupIdMapper.register(existing);
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }

    private static List<String> toStrList(Set<UUID> uuids) {
        List<String> list = new ArrayList<>(uuids.size());
        for (UUID u : uuids) list.add(u.toString());
        return list;
    }
}