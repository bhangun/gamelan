package tech.kayys.gamelan.engine.payload;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defensive payload snapshots for workflow context, task, result, and event data.
 */
public final class ExecutionPayloads {

    private ExecutionPayloads() {
    }

    public static Map<String, Object> mutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return copy;
        }
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return copy;
    }

    public static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(mutableMap(source));
    }

    public static <K, V> Map<K, V> immutableMapCopy(Map<? extends K, ? extends V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Map<K, V> copy = new LinkedHashMap<>();
        source.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }

    public static <T> List<T> immutableListCopy(Collection<? extends T> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public static Map<String, Object> immutableStringKeyMap(Map<?, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                copy.put(stringKey, immutableValue(value));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    public static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> copy.put(key, immutableValue(nestedValue)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof Set<?> set) {
            Set<Object> copy = new LinkedHashSet<>();
            set.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableSet(copy);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> copy = new ArrayList<>(collection.size());
            collection.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(immutableValue(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
