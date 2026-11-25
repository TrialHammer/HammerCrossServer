package moe.hammer.cross.server.model;

import java.util.Objects;

public final class TextureData {
    public String name;
    public String value;
    public String signature;

    public TextureData() {
    }

    public TextureData(String name, String value, String signature) {
        this.name = Objects.requireNonNullElse(name, "textures");
        this.value = Objects.requireNonNullElse(value, "");
        this.signature = signature;
    }
}
