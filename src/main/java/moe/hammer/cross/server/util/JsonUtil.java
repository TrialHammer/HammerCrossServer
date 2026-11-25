package moe.hammer.cross.server.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

public final class JsonUtil {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private JsonUtil() {
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T fromJson(String json, Class<T> type) throws JsonSyntaxException {
        return GSON.fromJson(json, type);
    }
}
