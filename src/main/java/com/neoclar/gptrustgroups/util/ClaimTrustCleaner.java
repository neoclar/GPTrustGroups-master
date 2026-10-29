package com.neoclar.gptrustgroups.util;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;

import java.util.ArrayList;
import java.util.List;

public final class ClaimTrustCleaner {

    private ClaimTrustCleaner() {}

    /** Убирает id из доверия во всех клеймах и сабклеймах. @return сколько клеймов изменено. */
    public static int dropEverywhere(String id) {
        DataStore dataStore = GriefPrevention.instance.dataStore;

        // Сначала собираем, потом удаляем: dropPermission у родителя чистит и детей
        List<Claim> affected = new ArrayList<>();
        for (Claim top : dataStore.getClaims()) {
            if (hasEntry(top, id)) affected.add(top);
            for (Claim child : top.children) {
                if (hasEntry(child, id)) affected.add(child);
            }
        }
        for (Claim claim : affected) {
            claim.dropPermission(id);
            dataStore.saveClaim(claim);
        }
        return affected.size();
    }

    private static boolean hasEntry(Claim claim, String id) {
        ArrayList<String> builders = new ArrayList<>();
        ArrayList<String> containers = new ArrayList<>();
        ArrayList<String> accessors = new ArrayList<>();
        ArrayList<String> managers = new ArrayList<>();
        claim.getPermissions(builders, containers, accessors, managers);
        return builders.contains(id) || containers.contains(id) || accessors.contains(id) || managers.contains(id);
    }
}
