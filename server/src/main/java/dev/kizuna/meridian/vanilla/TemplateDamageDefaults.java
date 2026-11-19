package dev.kizuna.meridian.vanilla;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tomlj.TomlTable;

/** 引用随包模板的四段默认值。每段独立合并，玩家给出的整段优先。 */
public final class TemplateDamageDefaults {
    private static final List<String> SECTIONS = List.of("armor.coverage", "anatomies", "guard", "aims");
    private final Map<String, Map<String, TomlTable>> additions = new HashMap<>();

    public void register(String template, String source) {
        if (template == null || template.isBlank() || source == null || source.isBlank()) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.TEMPLATE_DEFAULT);
        }
        var bundled = DamagePreferences.bundled();
        var sections = new HashMap<String, TomlTable>();
        for (String section : SECTIONS) {
            var table = bundled.getTable(section).getTable(List.of(source));
            if (table == null) {
                throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.TEMPLATE_DEFAULT + ": " + source);
            }
            sections.put(section, table);
        }
        if (additions.putIfAbsent(template, Map.copyOf(sections)) != null) {
            throw VanillaDamageErrors.invalidConfig(VanillaDamageErrors.TEMPLATE_DEFAULT + ": " + template);
        }
    }

    Map<String, TomlTable> tables(TomlTable configured, String section, String error) {
        var tables = new HashMap<String, TomlTable>();
        additions.forEach((template, defaults) -> tables.put(template, defaults.get(section)));
        for (String template : configured.keySet()) {
            if (!(configured.get(List.of(template)) instanceof TomlTable table)) {
                throw VanillaDamageErrors.invalidConfig(error + ": " + template);
            }
            tables.put(template, table);
        }
        return tables;
    }
}
