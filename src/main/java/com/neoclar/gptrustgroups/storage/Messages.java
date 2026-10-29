package com.neoclar.gptrustgroups.storage;

import com.neoclar.gptrustgroups.GPTrustGroups;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class Messages {

    private final GPTrustGroups plugin;
    private final File file;
    private FileConfiguration config;
    private final Map<String, String> cache = new HashMap<>();

    public Messages(GPTrustGroups plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
        // Ключи, добавленные в новых версиях, берём из messages.yml внутри jar
        InputStream bundled = plugin.getResource("messages.yml");
        if (bundled != null) {
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        cache.clear();
    }
public String get(String key) {
    return cache.computeIfAbsent(key, k -> {
        String val = config.getString(k);
        if (val == null) {
            plugin.getLogger().warning("Отсутствует ключ messages.yml: " + k);
            return k;
        }
        return ChatColor.translateAlternateColorCodes('$', val);
    });
}

    public String format(String key, Object... args) {
        String msg = get(key);
        for (int i = 0; i < args.length; i++) {
            msg = msg.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return msg;
    }
}