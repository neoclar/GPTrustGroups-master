package com.neoclar.gptrustgroups.listeners;

import com.neoclar.gptrustgroups.GPTrustGroups;
import com.neoclar.gptrustgroups.storage.Group;
import com.neoclar.gptrustgroups.util.GroupIdMapper;
import com.neoclar.gptrustgroups.storage.GroupManager;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.events.ClaimPermissionCheckEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ClaimPermissionListener implements Listener {

    private static final boolean DEBUG = true;

    private final GPTrustGroups plugin;
    private final GroupManager groupManager;

    public ClaimPermissionListener(GPTrustGroups plugin) {
        this.plugin = plugin;
        this.groupManager = plugin.getGroupManager();
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClaimPermissionCheck(ClaimPermissionCheckEvent event) {
        if (event.getDenialReason() == null) return;

        Claim claim = event.getClaim();
        Player player = event.getCheckedPlayer();
        if (claim == null || player == null) return;

        ClaimPermission required = event.getRequiredPermission();
        if (required == null) return;

        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, managers);

        if (DEBUG) {
            plugin.getLogger().info("[DEBUG] player=" + player.getName()
                    + " perm=" + required
                    + " claim=" + claim.getID()
                    + " | builders=" + builders
                    + " | containers=" + containers
                    + " | accessors=" + accessors
                    + " | managers=" + managers);
        }

        // Проверяем все записи привата, а даёт ли запись нужный уровень — решает сам GP (см. grantsRequired).
        // Свой switch по ClaimPermission ломается: в новых GP Inventory переименован в Container.
        List<List<String>> listsToCheck = List.of(builders, containers, accessors, managers);


boolean anyDenied = false;
boolean anyAllowed = false;

for (List<String> list : listsToCheck) {
    for (String entry : list) {
        if (!grantsRequired(claim, entry, required, managers)) continue;
        UUID id = tryParseUuid(entry);
        if (id == null) continue;

        Group g = GroupIdMapper.groupFor(id);
		if (g == null) continue;

		GroupManager.AccessResult r = groupManager.getAccessStatus(g, player.getUniqueId());
        if (r == GroupManager.AccessResult.DENIED) {
            anyDenied = true;
            // if (DEBUG) plugin.getLogger().info("[DEBUG] → DENIED через #" + groupName);
        } else if (r == GroupManager.AccessResult.ALLOWED) {
            anyAllowed = true;
            // if (DEBUG) plugin.getLogger().info("[DEBUG] → ALLOWED через #" + groupName);
        }
    }
}

if (anyDenied) {
    if (DEBUG) plugin.getLogger().info("[DEBUG] → итог: DENIED (есть явное исключение)");
    return; // оставляем запрет GP
}
if (anyAllowed) {
    if (DEBUG) plugin.getLogger().info("[DEBUG] → итог: ALLOWED");
    event.setDenialReason(null);
}

        if (DEBUG) plugin.getLogger().info("[DEBUG] → групповой UUID не найден в списках");
    }

    /**
     * Даёт ли запись привата требуемый уровень — так же, как Claim#hasExplicitPermission в GP.
     * isGrantedBy учитывает иерархию и псевдоним Inventory → Container в новых GP.
     * В GP 18.0.0 менеджеры хранятся отдельным списком и дают только Manage.
     */
    private static boolean grantsRequired(Claim claim, String entry, ClaimPermission required, List<String> managers) {
        return required.isGrantedBy(claim.getPermission(entry))
                || (required == ClaimPermission.Manage && managers.contains(entry));
    }

    private static UUID tryParseUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); }
        catch (IllegalArgumentException e) { return null; }
    }
}