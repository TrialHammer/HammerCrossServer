package moe.hammer.cross.server.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Prepares upgrades without touching disk, then saves each file as an atomic replacement. */
final class VersionedYaml {
    static final String VERSION_KEY = "config-version";

    private VersionedYaml() {
    }

    record Schema(String resource, List<Consumer<YamlConfiguration>> steps, Set<String> customMaps) {
        int current() {
            return steps.size();
        }
    }

    static Prepared prepare(Path file, Schema schema, String bundledText) {
        try {
            // A live config can be a symlink. Preserve the link and migrate its target.
            Path path = Files.isSymbolicLink(file) ? file.toRealPath() : file;
            YamlConfiguration defaults = parse(bundledText, "内置 " + schema.resource());
            int current = schema.current();
            if (readVersion(defaults, schema.resource()) != current) {
                throw new IllegalStateException("内置 " + schema.resource() + " 的版本与迁移步骤不一致");
            }
            if (Files.notExists(path)) {
                return new Prepared(path, defaults, null, bundledText.getBytes(StandardCharsets.UTF_8), 0, current);
            }
            byte[] before = Files.readAllBytes(path);
            YamlConfiguration yaml = parse(new String(before, StandardCharsets.UTF_8), schema.resource());
            int version = readVersion(yaml, schema.resource());
            if (version > current) {
                throw new IllegalStateException(schema.resource() + " 的 " + VERSION_KEY + "=" + version
                        + "，当前插件仅支持到 " + current + "。请使用对应版本的插件或恢复旧配置");
            }
            if (version == current) {
                return new Prepared(path, yaml, before, null, version, current);
            }

            for (int from = version; from < current; from++) {
                schema.steps().get(from).accept(yaml);
            }
            mergeMissing(yaml, defaults, schema.customMaps(), true);
            yaml.set(VERSION_KEY, current);
            copyComments(defaults, yaml, VERSION_KEY);
            return new Prepared(path, yaml, before, yaml.saveToString().getBytes(StandardCharsets.UTF_8),
                    version, current);
        } catch (IOException error) {
            throw new IllegalStateException("读取 " + schema.resource() + " 失败: " + error.getMessage(), error);
        }
    }

    private static int readVersion(YamlConfiguration yaml, String name) {
        Object raw = yaml.get(VERSION_KEY);
        if (raw == null) {
            return 0;
        }
        if (raw instanceof Integer version && version >= 0) {
            return version;
        }
        throw new IllegalStateException(name + " 的 " + VERSION_KEY + " 必须是非负整数");
    }

    private static YamlConfiguration parse(String yaml, String name) {
        YamlConfiguration config = new YamlConfiguration();
        config.options().parseComments(true);
        try {
            config.loadFromString(yaml);
            return config;
        } catch (InvalidConfigurationException error) {
            throw new IllegalStateException(name + " 不是合法的 YAML: " + error.getMessage(), error);
        }
    }

    private static void mergeMissing(ConfigurationSection target, ConfigurationSection defaults,
                                     Set<String> customMaps, boolean root) {
        for (String key : defaults.getKeys(false)) {
            if (root && (VERSION_KEY.equals(key) || customMaps.contains(key))) {
                continue;
            }
            Object bundled = defaults.get(key);
            Object existing = target.get(key);
            if (existing == null) {
                if (bundled instanceof ConfigurationSection section) {
                    mergeMissing(target.createSection(key), section, Set.of(), false);
                } else {
                    target.set(key, bundled);
                }
                copyComments(defaults, target, key);
            } else if (existing instanceof ConfigurationSection targetSection
                    && bundled instanceof ConfigurationSection defaultSection) {
                mergeMissing(targetSection, defaultSection, Set.of(), false);
            }
        }
    }

    static void copyComments(ConfigurationSection from, ConfigurationSection to, String key) {
        to.setComments(key, from.getComments(key));
        to.setInlineComments(key, from.getInlineComments(key));
    }

    record Prepared(Path path, YamlConfiguration yaml, byte[] original, byte[] upgraded, int from, int to) {
        /** Returns the backup path for an upgrade, or null when creating/loading a file. */
        Path save() throws IOException {
            if (upgraded == null) {
                return null;
            }

            Files.createDirectories(path.getParent());
            Path staged = Files.createTempFile(path.getParent(), "." + path.getFileName() + "-", ".tmp");
            try {
                Files.write(staged, upgraded);
                if (original != null && Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
                    Files.setPosixFilePermissions(staged, Files.getPosixFilePermissions(path));
                }
                ensureUnchanged();
                Path backup = null;
                if (original != null) {
                    backup = Files.createTempFile(path.getParent(),
                            path.getFileName() + ".v" + from + "-", ".bak");
                    try {
                        Files.write(backup, original);
                    } catch (IOException error) {
                        Files.deleteIfExists(backup);
                        throw error;
                    }
                }
                if (original == null) {
                    // A recently created operator file must not be replaced by the defaults.
                    Files.move(staged, path);
                } else {
                    replace(staged, path);
                }
                return backup;
            } finally {
                Files.deleteIfExists(staged);
            }
        }

        private void ensureUnchanged() throws IOException {
            boolean same = original == null ? Files.notExists(path) : Arrays.equals(original, Files.readAllBytes(path));
            if (!same) {
                throw new IOException(path.getFileName() + " 在迁移期间被修改，请重新加载");
            }
        }

        private static void replace(Path source, Path target) throws IOException {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
