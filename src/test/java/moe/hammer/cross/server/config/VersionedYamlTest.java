package moe.hammer.cross.server.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class VersionedYamlTest {
    @TempDir Path directory;

    @Test
    void upgradesUnversionedConfigWithoutOverwritingValuesOrLists() throws IOException {
        String original = """
                # administrator note
                server-id: "creative" # keep this ID
                redis:
                  password: 'custom$secret'
                chat:
                  item:
                    # custom triggers
                    placeholders: ["[slot]"]
                completion:
                  commands: []
                formats:
                  chat: '<white><player></white> <message>'
                kook:
                  token: "keep-me"
                symbols:
                  star: "☆"
                servers:
                  one:
                    display-name: "自定义服"
                custom-section:
                  future-key: true
                """;
        Path path = write("config.yml", original);
        VersionedYaml.Prepared prepared = prepare(path, ConfigManager.CONFIG_SCHEMA);
        assertEquals(original, Files.readString(path), "prepare does not write the migration");
        assertEquals(0, prepared.from());
        assertEquals(1, prepared.to());
        Path backup = prepared.save();
        assertNotNull(backup);
        assertTrue(backup.getFileName().toString().startsWith("config.yml.v0-"));
        assertEquals(original, Files.readString(backup), "backup is byte-for-byte the old file");

        YamlConfiguration yaml = prepare(path, ConfigManager.CONFIG_SCHEMA).yaml();
        assertEquals(1, yaml.getInt("config-version"));
        assertEquals("creative", yaml.getString("server-id"));
        assertEquals("custom$secret", yaml.getString("redis.password"));
        assertEquals("<white><player></white> <message>", yaml.getString("formats.chat"));
        assertEquals("keep-me", yaml.getString("kook.token"));
        assertEquals(false, yaml.get("kook.enabled"));
        assertNotNull(yaml.getString("formats.kook-chat"));
        assertEquals(List.of(), yaml.getList("completion.commands"));
        assertEquals(List.of("[slot]"), yaml.getStringList("chat.item.keys"));
        assertFalse(yaml.contains("chat.item.placeholders"));
        assertTrue(yaml.getComments("chat.item.keys").contains("custom triggers"));
        assertEquals(Set.of("one"), yaml.getConfigurationSection("servers").getKeys(false));
        assertEquals(Set.of("star"), yaml.getConfigurationSection("symbols").getKeys(false));
        assertEquals("☆", yaml.getString("symbols.star"));
        assertTrue(yaml.getBoolean("custom-section.future-key"));
        String saved = Files.readString(path);
        assertTrue(saved.contains("# administrator note"));
        assertTrue(saved.contains("# keep this ID"));
    }

    @Test
    void mergesBothItemKeySpellingsAndDoesNotAddExampleServers() throws IOException {
        Path path = write("config.yml", """
                config-version: 0
                chat:
                  item:
                    keys: ['[i]', '[new]']
                    placeholders: ['[old]', '[i]']
                """);
        VersionedYaml.Prepared prepared = prepare(path, ConfigManager.CONFIG_SCHEMA);
        assertEquals(List.of("[old]", "[i]", "[new]"), prepared.yaml().getStringList("chat.item.keys"));
        assertFalse(prepared.yaml().contains("chat.item.placeholders"));
        assertFalse(prepared.yaml().contains("servers"));
    }

    @Test
    void upgradesMessagesWhileKeepingTranslationsAndBlankPrefix() throws IOException {
        String original = "prefix: ''\nreload-success: '<green>自定义</green>'\nextra: keep\n";
        Path path = write("messages.yml", original);
        VersionedYaml.Prepared prepared = prepare(path, ConfigManager.MESSAGES_SCHEMA);
        assertEquals(original, Files.readString(prepared.save()));
        YamlConfiguration yaml = prepare(path, ConfigManager.MESSAGES_SCHEMA).yaml();
        assertEquals("", yaml.getString("prefix"));
        assertEquals("keep", yaml.getString("extra"));
        assertEquals("<green>自定义</green>", yaml.getString("reload-success"));
        assertTrue(yaml.contains("glist-empty"));
        String migrated = Files.readString(path);
        assertNull(prepare(path, ConfigManager.MESSAGES_SCHEMA).save());
        assertEquals(migrated, Files.readString(path));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count(), "there is no second backup or temporary file");
        }
    }

    @Test
    void createsNewFilesFromBundledDefaultsWithoutBackingThemUp() throws IOException {
        Path path = directory.resolve("new-folder/config.yml");
        VersionedYaml.Prepared prepared = prepare(path, ConfigManager.CONFIG_SCHEMA);
        assertFalse(Files.exists(path));
        assertNull(prepared.save());
        assertEquals(resource("config.yml"), Files.readString(path));
        assertEquals(1, prepare(path, ConfigManager.CONFIG_SCHEMA).from());
    }

    @Test
    void protectsInvalidAndNewerFilesWithoutWriting() throws IOException {
        for (String content : List.of("config-version: 999\n", "config-version: -1\n",
                "config-version: 'first'\n", "config-version: [1\n")) {
            Path path = write("config.yml", content);
            assertThrows(IllegalStateException.class, () -> prepare(path, ConfigManager.CONFIG_SCHEMA));
            assertEquals(content, Files.readString(path));
        }
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void doesNotOverwriteAnEditMadeAfterPreparingTheMigration() throws IOException {
        Path path = write("config.yml", "server-id: old\n");
        VersionedYaml.Prepared prepared = prepare(path, ConfigManager.CONFIG_SCHEMA);
        Files.writeString(path, "server-id: just-edited\n");
        assertThrows(IOException.class, prepared::save);
        assertEquals("server-id: just-edited\n", Files.readString(path));
    }

    @Test
    void runsAllIntermediateVersionsInOrderBeforeFillingNewDefaults() throws IOException {
        Path path = write("config.yml", "legacy-name: author\n");
        VersionedYaml.Schema schema = new VersionedYaml.Schema("config.yml", List.of(
                yaml -> yaml.set("intermediate", yaml.getString("legacy-name") + "-v1"),
                yaml -> {
                    yaml.set("name", yaml.getString("intermediate") + "-v2");
                    yaml.set("intermediate", null);
                    yaml.set("legacy-name", null);
                }), Set.of());
        VersionedYaml.Prepared updated = VersionedYaml.prepare(path, schema,
                "config-version: 2\nname: default\nnew-key: ready\n");
        assertEquals(2, updated.yaml().getInt("config-version"));
        assertEquals("author-v1-v2", updated.yaml().getString("name"));
        assertEquals("ready", updated.yaml().getString("new-key"));
        assertFalse(updated.yaml().contains("legacy-name"));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content);
    }

    private static VersionedYaml.Prepared prepare(Path path, VersionedYaml.Schema schema) throws IOException {
        return VersionedYaml.prepare(path, schema, resource(schema.resource()));
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = VersionedYamlTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
